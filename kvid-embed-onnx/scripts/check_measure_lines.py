#!/usr/bin/env python3
"""Check [kvid-index-measure] and [kvid-embedding-tune] lines against the reporting invariants.

Usage: check_measure_lines.py FILE... (or - for stdin). Every line containing one of the two tags is
checked, with the [kvid-index-samples] lines of the same run (same open_epoch_ms) anywhere in the same file;
other lines are ignored. Prints one verdict per result line, and one FAIL line per file for samples lines
that belong to no result line or for two result lines of one run. Exits 1 if anything fails, 2 if no
result line was found. Malformed input is reported as a failure, never as a crash.

The invariants (mobile indexing study, answer-5 measurements):
- open_epoch_ms is present and a plausible epoch time in milliseconds;
- phase_start_s starts at exactly open:0.0, lists the test's phases in run order with finite, non-negative,
  non-decreasing times and ends with end; a phase is listed exactly when it ran;
- where memory is measured: mem_peak names the method; every phase field of the test is present, numeric
  for a phase that ran and not-run exactly for a phase whose setting is 0 (none, which a phase prints only
  if every memory read in it failed, is a failure); mem_lifecycle_peak_kb is at least every other mem_*_kb
  value on the line; mem_peak_kb equals the indexing or embedding phase's peak; mem_boundary_samples <=
  mem_samples;
- index lines with queries: query_store_notes equals notes and query_order is williams4;
- index lines with queries or edits, the [kvid-index-samples] lines:
  - exactly the series the settings call for (query_embed_ms, query_lexical_ms, query_similar_ms,
    query_hybrid_ms; edit_total_ms, edit_save_ms, edit_write_ms, edit_tokens, edit_kind);
  - each series complete: every part carries the same part count and n, part numbers are unique and run
    from 1 to the count, every part but the last has SAMPLES_PER_LINE values, and there are n values in all,
    n being the setting's count;
  - values well formed: milliseconds with one decimal, token counts from 1 to max_input_tokens, kinds put or
    update alternating from update (the harness's five warm-up edits make the first counted edit an update);
  - the nearest-rank p50 and p95 recomputed from them equal the line's, overall for every timed series and,
    for edit_total_ and edit_save_, per token-length bucket (le64: 1 to 64 tokens, le128: 65 to 128, le256:
    129 to 256, gt256: above), with each bucket's count; a bucket without values prints no fields.
The tuning line's by-length latency fields (latency=on) have no samples and are not recomputed.
Needs only the Python standard library.
"""
import math
import re
import sys

METHOD = "sampled-100ms+readings-from-before-open"
INDEX_PHASES = ["open", "index", "pause", "queries", "edits", "close"]
TUNE_PHASES = ["open", "embed", "close", "idle"]
OPTIONAL = {"pause": "pause", "queries": "queries", "edits": "edits"}  # phase -> setting that turns it on
SAMPLES_PER_LINE = 200  # IndexingMeasurementTest prints at most this many values per samples line
BUCKETS = [("le64", 1, 64), ("le128", 65, 128), ("le256", 129, 256), ("gt256", 257, math.inf)]
TIMED = {
    "queries": ["query_embed_", "query_lexical_", "query_similar_", "query_hybrid_"],
    "edits": ["edit_total_", "edit_save_", "edit_write_"],
}
BUCKETED = ["edit_total_", "edit_save_"]
MS = re.compile(r"^\d+\.\d$")
COUNT = re.compile(r"^\d+$")


def fields(line):
    body = line[line.index("]") + 1:]
    out = {}
    for token in body.split():
        if "=" in token:
            key, _, value = token.partition("=")
            out.setdefault(key, value)
    return out


def setting(config, key):
    for item in re.split(r"[,+]", config):
        name, _, value = item.partition("=")
        if name.strip() == key:
            return int(value) if COUNT.match(value.strip()) else -1
    return 0


def nearest_rank(values, p):
    ordered = sorted(values)
    return ordered[max(0, min(len(ordered) - 1, math.ceil(p * len(ordered)) - 1))]


def read_samples(lines):
    """(open_epoch_ms, series) -> ("ok", values) or ("bad", reason), from [kvid-index-samples] lines."""
    parts = {}
    malformed = []
    for line in lines:
        if "[kvid-index-samples]" not in line:
            continue
        f = fields(line[line.index("[kvid-"):])
        part = re.match(r"^(\d+)/(\d+)$", f.get("part", ""))
        if not (part and "open_epoch_ms" in f and "series" in f and COUNT.match(f.get("n", "")) and "values" in f):
            malformed.append(line.strip()[:120])
            continue
        k, m = int(part.group(1)), int(part.group(2))
        parts.setdefault((f["open_epoch_ms"], f["series"]), []).append((k, m, int(f["n"]), f["values"].split(",")))
    out = {}
    for key, got in parts.items():
        numbers = sorted(k for k, _, _, _ in got)
        metas = {(m, n) for _, m, n, _ in got}
        if len(metas) != 1:
            out[key] = ("bad", f"parts disagree on part count or n: {sorted(metas)}")
            continue
        (m, n), = metas
        if len(set(numbers)) != len(numbers):
            out[key] = ("bad", f"duplicate part numbers {numbers}")
            continue
        if numbers != list(range(1, m + 1)):
            out[key] = ("bad", f"parts {numbers} of {m}")
            continue
        ordered = [values for _, _, _, values in sorted(got)]
        if any(len(v) != SAMPLES_PER_LINE for v in ordered[:-1]) or not 1 <= len(ordered[-1]) <= SAMPLES_PER_LINE:
            out[key] = ("bad", f"part sizes {[len(v) for v in ordered]}, expected {SAMPLES_PER_LINE} in every part but the last")
            continue
        values = [v for part_values in ordered for v in part_values]
        out[key] = ("ok", values) if len(values) == n else ("bad", f"{len(values)} values, n={n}")
    return out, malformed


def expected_series(config):
    names = []
    if setting(config, "queries") > 0:
        names += [p + "ms" for p in TIMED["queries"]]
    if setting(config, "edits") > 0:
        names += [p + "ms" for p in TIMED["edits"]] + ["edit_tokens", "edit_kind"]
    return names


def compare_percentiles(f, prefix, numbers, problems):
    for p, label in ((0.5, "p50"), (0.95, "p95")):
        printed = f.get(f"{prefix}{label}_ms")
        recomputed = f"{nearest_rank(numbers, p):.1f}"
        if printed != recomputed:
            problems.append(f"{prefix}{label}_ms={printed} but the samples give {recomputed}")


def check_samples(f, config, samples, problems):
    run = f.get("open_epoch_ms")
    wanted = expected_series(config)
    present = {series for (r, series) in samples if r == run}
    for series in sorted(present - set(wanted)):
        problems.append(f"samples {series}: not expected with these settings")
    series = {}
    for name in wanted:
        state, values = samples.get((run, name), ("missing", None))
        if state != "ok":
            problems.append(f"samples {name}: {state} {values or ''}".strip())
            continue
        count = setting(config, "queries" if name.startswith("query_") else "edits")
        if len(values) != count:
            problems.append(f"samples {name}: {len(values)} values for a setting of {count}")
            continue
        series[name] = values

    for setting_name, prefixes in TIMED.items():
        for prefix in prefixes:
            values = series.get(prefix + "ms")
            if values is None:
                continue
            bad = [v for v in values if not MS.match(v)]
            if bad:
                problems.append(f"samples {prefix}ms: malformed values {bad[:3]}")
                series.pop(prefix + "ms")
                continue
            compare_percentiles(f, prefix, [float(v) for v in values], problems)

    kinds = series.get("edit_kind")
    if kinds is not None:
        expected = ["update" if j % 2 == 0 else "put" for j in range(len(kinds))]
        if kinds != expected:
            first = next(j for j in range(len(kinds)) if kinds[j] != expected[j])
            problems.append(f"samples edit_kind: not alternating from update (value {first + 1} is {kinds[first]}; "
                            f"{kinds.count('put')} put, {kinds.count('update')} update)")

    tokens = series.get("edit_tokens")
    if tokens is not None:
        limit = int(f["max_input_tokens"]) if COUNT.match(f.get("max_input_tokens", "")) else None
        if limit is None or any(not COUNT.match(t) or not 1 <= int(t) <= limit for t in tokens):
            problems.append(f"samples edit_tokens: values outside 1 to max_input_tokens={f.get('max_input_tokens')}")
            tokens = None
    if tokens is None:
        return
    lengths = [int(t) for t in tokens]
    for prefix in BUCKETED:
        values = series.get(prefix + "ms")
        if values is None:
            continue
        numbers = [float(v) for v in values]
        for name, low, high in BUCKETS:
            bucket = [x for x, t in zip(numbers, lengths) if low <= t <= high]
            keys = [f"{prefix}n_{name}", f"{prefix}{name}_p50_ms", f"{prefix}{name}_p95_ms"]
            if not bucket:
                for key in keys:
                    if key in f:
                        problems.append(f"{key}={f[key]} but the samples have no edit of that length")
                continue
            if f.get(keys[0]) != str(len(bucket)):
                problems.append(f"{keys[0]}={f.get(keys[0])} but the samples give {len(bucket)}")
            compare_percentiles(f, f"{prefix}{name}_", bucket, problems)


def check_phase_starts(starts, ran, problems):
    if starts is None:
        problems.append("phase_start_s missing")
        return
    names, times = [], []
    for item in starts.split(","):
        name, colon, value = item.partition(":")
        try:
            time = float(value) if colon else math.nan
        except ValueError:
            time = math.nan
        if not math.isfinite(time) or time < 0:
            problems.append(f"phase_start_s has a time that is not finite and non-negative: {item}")
            return
        names.append(name)
        times.append(time)
    if names != ran + ["end"]:
        problems.append(f"phase_start_s lists {names}, expected {ran + ['end']}")
    if not starts.startswith("open:0.0,"):
        problems.append(f"phase_start_s does not start at open:0.0: {starts}")
    if any(b < a for a, b in zip(times, times[1:])):
        problems.append(f"phase_start_s decreases: {starts}")


def check(line, samples):
    tag = "index" if "[kvid-index-measure]" in line else "tune"
    f = fields(line)
    problems = []
    config = f.get("config", "")
    phases = INDEX_PHASES if tag == "index" else TUNE_PHASES
    for name in ("pause", "queries", "edits"):
        if setting(config, name) < 0:
            problems.append(f"config: {name} is not a count")
    ran = [p for p in phases if tag == "tune" or p not in OPTIONAL or setting(config, OPTIONAL[p]) > 0]

    epoch = f.get("open_epoch_ms")
    if epoch is None or not COUNT.match(epoch) or not 1_600_000_000_000 < int(epoch) < 4_000_000_000_000:
        problems.append(f"open_epoch_ms missing or implausible: {epoch}")

    check_phase_starts(f.get("phase_start_s"), ran, problems)

    if f.get("mem") != "not-measured":
        if f.get("mem_peak") != METHOD:
            problems.append(f"mem_peak is {f.get('mem_peak')}, expected {METHOD}")
        for p in phases:
            key = f"mem_{p}_peak_kb"
            value = f.get(key)
            if value is None:
                problems.append(f"{key} missing")
            elif p in ran and not COUNT.match(value):
                problems.append(f"{key}={value} for a phase that ran")
            elif p not in ran and value != "not-run":
                problems.append(f"{key}={value} for a phase that did not run")
        lifecycle = f.get("mem_lifecycle_peak_kb", "")
        if not COUNT.match(lifecycle):
            problems.append(f"mem_lifecycle_peak_kb missing or not numeric: {lifecycle}")
        else:
            for key, value in f.items():
                if key.startswith("mem_") and key.endswith("_kb") and COUNT.match(value) and int(value) > int(lifecycle):
                    problems.append(f"{key}={value} exceeds mem_lifecycle_peak_kb={lifecycle}")
        own = "mem_index_peak_kb" if tag == "index" else "mem_embed_peak_kb"
        if f.get("mem_peak_kb") != f.get(own):
            problems.append(f"mem_peak_kb={f.get('mem_peak_kb')} differs from {own}={f.get(own)}")
        sampled, boundary = f.get("mem_samples", ""), f.get("mem_boundary_samples", "")
        if not (COUNT.match(sampled) and COUNT.match(boundary)) or int(boundary) > int(sampled):
            problems.append(f"mem_samples={sampled} mem_boundary_samples={boundary}")

    if tag == "index" and setting(config, "queries") > 0:
        if f.get("query_store_notes") != f.get("notes"):
            problems.append(f"query_store_notes={f.get('query_store_notes')} differs from notes={f.get('notes')}")
        if f.get("query_order") != "williams4":
            problems.append(f"query_order={f.get('query_order')}")
    if tag == "index":
        check_samples(f, config, samples, problems)
    return tag, problems


def main(paths):
    found = failed = 0
    for path in paths or ["-"]:
        if path == "-":
            lines = sys.stdin.read().splitlines()
        else:
            with open(path, encoding="utf-8") as stream:
                lines = stream.read().splitlines()
        samples, malformed = read_samples(lines)
        runs = []
        for line in lines:
            if "[kvid-index-measure]" not in line and "[kvid-embedding-tune]" not in line:
                continue
            found += 1
            result = line[line.index("[kvid-"):].strip()
            tag, problems = check(result, samples)
            if tag == "index":
                runs.append(fields(result).get("open_epoch_ms"))
            if problems:
                failed += 1
                print(f"FAIL {path} {tag}: " + "; ".join(problems))
            else:
                print(f"OK   {path} {tag}")
        file_problems = [f"malformed samples line: {line}" for line in malformed]
        file_problems += [f"two index lines with open_epoch_ms={run}" for run in sorted({r for r in runs if runs.count(r) > 1})]
        orphans = sorted({run for run, _ in samples} - set(runs))
        file_problems += [f"samples lines with open_epoch_ms={run} belong to no index line" for run in orphans]
        if file_problems:
            failed += 1
            print(f"FAIL {path} file: " + "; ".join(file_problems))
    if not found:
        print("no [kvid-index-measure] or [kvid-embedding-tune] line found")
        return 2
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
