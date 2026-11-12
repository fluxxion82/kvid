#!/usr/bin/env python3
"""Check [kvid-index-measure] and [kvid-embedding-tune] lines against the reporting invariants.

Usage: check_measure_lines.py FILE... (or - for stdin). Every line containing one of the two tags is
checked, with the [kvid-index-samples] lines of the same run (same open_epoch_ms) anywhere in the input;
other lines are ignored. Prints one verdict per result line and exits 1 if any line fails, 2 if no result
line was found.

The invariants (mobile indexing study, answer-5 measurements):
- open_epoch_ms is present and a plausible epoch time in milliseconds;
- phase_start_s starts at open:0.0, lists the test's phases in run order with non-decreasing times and
  ends with end; a phase is listed exactly when it ran;
- where memory is measured: mem_peak names the method; every phase field of the test is present, numeric
  for a phase that ran and not-run exactly for a phase whose setting is 0 (never none: every phase that
  runs ends with an explicit reading); mem_lifecycle_peak_kb is at least every other mem_*_kb value on the
  line; mem_peak_kb equals the indexing or embedding phase's peak; mem_boundary_samples <= mem_samples;
- index lines with queries: query_store_notes equals notes and query_order is williams4;
- index lines with queries or edits: a [kvid-index-samples] series for every counted call (query_embed_ms,
  query_lexical_ms, query_similar_ms, query_hybrid_ms; edit_total_ms, edit_save_ms, edit_write_ms,
  edit_tokens, edit_kind), complete in its parts, with as many values as the setting asks for, and the
  nearest-rank p50 and p95 recomputed from them equal to the line's (edit_kind: half put, half update).
Needs only the Python standard library.
"""
import math
import re
import sys

METHOD = "sampled-100ms+readings-from-before-open"
INDEX_PHASES = ["open", "index", "pause", "queries", "edits", "close"]
TUNE_PHASES = ["open", "embed", "close", "idle"]
OPTIONAL = {"pause": "pause", "queries": "queries", "edits": "edits"}  # phase -> setting that turns it on


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
            return int(value)
    return 0


def nearest_rank(values, p):
    ordered = sorted(values)
    return ordered[max(0, min(len(ordered) - 1, math.ceil(p * len(ordered)) - 1))]


def read_samples(lines):
    """(open_epoch_ms, series) -> values, from [kvid-index-samples] lines; parts must be complete."""
    parts = {}
    for line in lines:
        if "[kvid-index-samples]" not in line:
            continue
        f = fields(line[line.index("[kvid-"):])
        k, total = (int(x) for x in f["part"].split("/"))
        parts.setdefault((f["open_epoch_ms"], f["series"]), {})[k] = (total, int(f["n"]), f["values"].split(","))
    out = {}
    for key, got in parts.items():
        total, n = next(iter(got.values()))[:2]
        if sorted(got) != list(range(1, total + 1)):
            out[key] = ("incomplete", f"parts {sorted(got)} of {total}")
            continue
        values = [v for k in range(1, total + 1) for v in got[k][2]]
        out[key] = ("ok", values) if len(values) == n else ("incomplete", f"{len(values)} values, n={n}")
    return out


SERIES = {
    "queries": ["query_embed_", "query_lexical_", "query_similar_", "query_hybrid_"],
    "edits": ["edit_total_", "edit_save_", "edit_write_"],
}


def check_samples(f, config, samples, problems):
    run = f.get("open_epoch_ms")
    for setting_name, prefixes in SERIES.items():
        count = setting(config, setting_name)
        if count == 0:
            continue
        extra = ["edit_tokens", "edit_kind"] if setting_name == "edits" else []
        for name in [p + "ms" for p in prefixes] + extra:
            state, values = samples.get((run, name), ("missing", None))
            if state != "ok":
                problems.append(f"samples {name}: {state} {values or ''}".strip())
                continue
            if len(values) != count:
                problems.append(f"samples {name}: {len(values)} values for {setting_name}={count}")
                continue
            if name == "edit_kind":
                if values.count("put") != values.count("update") or set(values) != {"put", "update"}:
                    problems.append(f"samples edit_kind: {values.count('put')} put, {values.count('update')} update")
                continue
            if name == "edit_tokens":
                continue
            prefix = name[:-2]
            numbers = [float(v) for v in values]
            for p, label in ((0.5, "p50"), (0.95, "p95")):
                printed = f.get(f"{prefix}{label}_ms")
                recomputed = f"{nearest_rank(numbers, p):.1f}"
                if printed != recomputed:
                    problems.append(f"{prefix}{label}_ms={printed} but the samples give {recomputed}")


def check(line, samples):
    tag = "index" if "[kvid-index-measure]" in line else "tune"
    f = fields(line)
    problems = []
    config = f.get("config", "")
    phases = INDEX_PHASES if tag == "index" else TUNE_PHASES
    ran = [p for p in phases if tag == "tune" or p not in OPTIONAL or setting(config, OPTIONAL[p]) > 0]

    epoch = f.get("open_epoch_ms")
    if epoch is None or not epoch.isdigit() or not 1_600_000_000_000 < int(epoch) < 4_000_000_000_000:
        problems.append(f"open_epoch_ms missing or implausible: {epoch}")

    starts = f.get("phase_start_s")
    if starts is None:
        problems.append("phase_start_s missing")
    else:
        pairs = [item.split(":") for item in starts.split(",")]
        names = [name for name, _ in pairs]
        times = [float(t) for _, t in pairs]
        if names != ran + ["end"]:
            problems.append(f"phase_start_s lists {names}, expected {ran + ['end']}")
        if pairs and pairs[0] != ["open", "0.0"]:
            problems.append(f"phase_start_s does not start at open:0.0: {starts}")
        if any(b < a for a, b in zip(times, times[1:])):
            problems.append(f"phase_start_s decreases: {starts}")

    if f.get("mem") == "not-measured":
        pass
    else:
        if f.get("mem_peak") != METHOD:
            problems.append(f"mem_peak is {f.get('mem_peak')}, expected {METHOD}")
        for p in phases:
            key = f"mem_{p}_peak_kb"
            value = f.get(key)
            if value is None:
                problems.append(f"{key} missing")
            elif p in ran and not value.isdigit():
                problems.append(f"{key}={value} for a phase that ran")
            elif p not in ran and value != "not-run":
                problems.append(f"{key}={value} for a phase that did not run")
        lifecycle = f.get("mem_lifecycle_peak_kb", "")
        if not lifecycle.isdigit():
            problems.append(f"mem_lifecycle_peak_kb missing or not numeric: {lifecycle}")
        else:
            for key, value in f.items():
                if key.startswith("mem_") and key.endswith("_kb") and value.isdigit() and int(value) > int(lifecycle):
                    problems.append(f"{key}={value} exceeds mem_lifecycle_peak_kb={lifecycle}")
        own = "mem_index_peak_kb" if tag == "index" else "mem_embed_peak_kb"
        if f.get("mem_peak_kb") != f.get(own):
            problems.append(f"mem_peak_kb={f.get('mem_peak_kb')} differs from {own}={f.get(own)}")
        sampled, boundary = f.get("mem_samples", ""), f.get("mem_boundary_samples", "")
        if not (sampled.isdigit() and boundary.isdigit()) or int(boundary) > int(sampled):
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
        stream = sys.stdin if path == "-" else open(path, encoding="utf-8")
        lines = stream.read().splitlines()
        samples = read_samples(lines)
        for line in lines:
            if "[kvid-index-measure]" not in line and "[kvid-embedding-tune]" not in line:
                continue
            found += 1
            tag, problems = check(line[line.index("[kvid-"):].strip(), samples)
            if problems:
                failed += 1
                print(f"FAIL {path} {tag}: " + "; ".join(problems))
            else:
                print(f"OK   {path} {tag}")
    if not found:
        print("no [kvid-index-measure] or [kvid-embedding-tune] line found")
        return 2
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
