"""Analyzes RetrievalEvalTest output with the study's preregistered decision rule.

Usage:
  python -I analyze_retrieval_eval.py RESULTS_DIR DATASETS_DIR [--baseline bge-fp32@512] [--margin 0.02]
      [--bootstrap 10000] [--seed 20261108] [--report PATH]

RESULTS_DIR holds summary.jsonl and queries.jsonl written by RetrievalEvalTest (KVID_RETRIEVAL_EVAL_OUT);
DATASETS_DIR holds the datasets of prepare_retrieval_eval.py, whose qrels.tsv files are read again here.

1. Recomputes nDCG@10, recall@10 and recall@100 of every query from its top 100 and the qrels with this
   script's own implementation, and with trec_eval through pytrec_eval when it is installed
   (requirements-study.txt), and fails if any value differs from the Kotlin one by more than 1e-9.
2. Decision rule (planning branch, docs/plans/mobile-indexing.md, from Codex's review), applied to every run
   and instruction policy against the baseline run with raw queries:
   - primary metric: hybrid nDCG@10; guardrails: semantic nDCG@10 and hybrid recall@100;
   - delta per dataset = mean over its queries of (candidate - baseline), paired by query; the macro delta
     weighs datasets equally; 95% intervals come from resampling queries within each dataset (bootstrap);
   - PASS when the macro interval's lower bound is at least -margin, FAIL when its upper bound is below
     -margin, INCONCLUSIVE otherwise; a dataset whose point loss exceeds the margin is flagged;
   - the per-set guardrail hybrid >= lexical is reported as a point comparison with its paired interval.
3. Also reports scores by the token length of each query's longest relevant document, and for variants of
   one model the mean top-10 overlap with their reference (ranking change, a diagnostic).
Writes a Markdown report (stdout, or --report) and the same numbers as JSON next to it. Needs only the
standard library; pytrec_eval adds the trec_eval cross-check.
"""
import argparse
import collections
import json
import math
import os
import random
import sys

MODES = ("hybrid", "semantic")
BUCKETS = ((128, "<=128"), (256, "129-256"), (512, "257-512"), (math.inf, ">512"))


def read_jsonl(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def read_qrels(path):
    qrels = collections.defaultdict(dict)
    with open(path, encoding="utf-8") as f:
        next(f)
        for line in f:
            query, doc, grade = line.rstrip("\n").split("\t")
            qrels[query][doc] = int(grade)
    return qrels


def ndcg_at(ranked, relevant, k):
    gains = [relevant.get(doc, 0) for doc in ranked[:k]]
    dcg = sum(g / math.log2(rank + 1) for rank, g in enumerate(gains, start=1))
    ideal = sorted(relevant.values(), reverse=True)[:k]
    idcg = sum(g / math.log2(rank + 1) for rank, g in enumerate(ideal, start=1))
    return dcg / idcg if idcg > 0 else 0.0


def recall_at(ranked, relevant, k):
    return len(set(ranked[:k]) & set(relevant)) / len(relevant)


def cross_check(queries, datasets_dir):
    qrels_by_set = {}
    worst = 0.0
    for q in queries:
        qrels = qrels_by_set.setdefault(q["dataset"], read_qrels(os.path.join(datasets_dir, q["dataset"], "qrels.tsv")))
        relevant = qrels[q["query"]]
        for name, value in (("ndcg10", ndcg_at(q["top100"], relevant, 10)), ("recall10", recall_at(q["top100"], relevant, 10)),
                            ("recall100", recall_at(q["top100"], relevant, 100))):
            worst = max(worst, abs(value - q[name]))
    if worst > 1e-9:
        sys.exit(f"metric cross-check failed: the Kotlin and Python values differ by up to {worst}")
    return worst, trec_eval_check(queries, qrels_by_set)


def trec_eval_check(queries, qrels_by_set):
    """The largest difference between the Kotlin values and trec_eval's, or None without pytrec_eval."""
    try:
        import pytrec_eval
    except ImportError:
        return None
    measures = {"ndcg_cut_10": "ndcg10", "recall_10": "recall10", "recall_100": "recall100"}
    groups = collections.defaultdict(list)
    for q in queries:
        groups[(q["dataset"], q["run"], q["instruction"], q["mode"])].append(q)
    worst = 0.0
    for (dataset, *_), records in groups.items():
        qrels = {qid: dict(docs) for qid, docs in qrels_by_set[dataset].items() if any(q["query"] == qid for q in records)}
        # trec_eval ranks by score: the rank order becomes descending scores.
        run = {q["query"]: {doc: float(len(q["top100"]) - rank) for rank, doc in enumerate(q["top100"])} for q in records}
        scores = pytrec_eval.RelevanceEvaluator(qrels, set(measures)).evaluate(run)
        for q in records:
            values = scores.get(q["query"], {})
            for measure, name in measures.items():
                worst = max(worst, abs(values.get(measure, 0.0) - q[name]))
    if worst > 1e-9:
        sys.exit(f"metric cross-check failed: the Kotlin and trec_eval values differ by up to {worst}")
    return worst


def interval(per_set_pairs, resamples, rng):
    """Point macro delta and 95% bootstrap interval; per_set_pairs maps a dataset to its list of paired deltas."""
    sets = sorted(per_set_pairs)
    point = sum(sum(per_set_pairs[s]) / len(per_set_pairs[s]) for s in sets) / len(sets)
    draws = []
    for _ in range(resamples):
        total = 0.0
        for s in sets:
            values = per_set_pairs[s]
            n = len(values)
            total += sum(values[rng.randrange(n)] for _ in range(n)) / n
        draws.append(total / len(sets))
    draws.sort()
    return point, draws[int(0.025 * resamples)], draws[int(0.975 * resamples) - 1]


DEFAULT_LIMIT = {"bge": "512", "minilm": "256"}


def reference_of(run, runs):
    """The run a variant's ranking is compared with: its family's fp32 run at the same input limit (else at the
    model's own limit); for the fp32 run at a shorter limit, the fp32 run at the model's own limit."""
    variant, limit = run.split("@")
    family = variant.split("-")[0]
    base = f"{family}-fp32"
    options = [f"{base}@{limit}", f"{base}@{DEFAULT_LIMIT.get(family)}"] if variant != base else [f"{base}@{DEFAULT_LIMIT.get(family)}"]
    return next((r for r in options if r in runs and r != run), None)


def verdict(lower, upper, margin):
    if lower >= -margin:
        return "PASS"
    if upper < -margin:
        return "FAIL"
    return "INCONCLUSIVE"


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("results")
    ap.add_argument("datasets")
    ap.add_argument("--baseline", default="bge-fp32@512")
    ap.add_argument("--margin", type=float, default=0.02)
    ap.add_argument("--bootstrap", type=int, default=10000)
    ap.add_argument("--seed", type=int, default=20261108)
    ap.add_argument("--report")
    args = ap.parse_args()

    queries = read_jsonl(os.path.join(args.results, "queries.jsonl"))
    summary = read_jsonl(os.path.join(args.results, "summary.jsonl"))
    worst, trec = cross_check(queries, args.datasets)

    # (dataset, run, instruction, mode) -> {query: record}; a later duplicate (a rerun) replaces an earlier one.
    table = collections.defaultdict(dict)
    for q in queries:
        table[(q["dataset"], q["run"], q["instruction"], q["mode"])][q["query"]] = q
    datasets = sorted({k[0] for k in table})
    candidates = sorted({(k[1], k[2]) for k in table if k[3] in MODES} - {(args.baseline, "off")})
    rng = random.Random(args.seed)

    def pairs(run, instruction, mode, metric, base_run=args.baseline, base_instruction="off", base_mode=None):
        out = {}
        for d in datasets:
            cand = table.get((d, run, instruction, mode))
            base = table.get((d, base_run, base_instruction, base_mode or mode))
            if cand and base:
                if set(cand) != set(base):
                    sys.exit(f"{d}: {run}/{instruction} and its baseline scored different queries")
                out[d] = [cand[q][metric] - base[q][metric] for q in sorted(cand)]
        return out

    results = {"baseline": args.baseline, "margin": args.margin, "bootstrap": args.bootstrap, "seed": args.seed,
               "metric_cross_check_max_abs_diff": worst, "trec_eval_max_abs_diff": trec, "datasets": datasets, "candidates": []}
    lines = [f"# Retrieval evaluation against {args.baseline} (raw queries)", "",
             f"Datasets: {', '.join(datasets)}. Metrics recomputed independently: maximum difference {worst:.1e}; against trec_eval: "
             f"{'not run (pytrec_eval missing)' if trec is None else f'{trec:.1e}'}. "
             f"95% intervals from {args.bootstrap} paired, query-resampled bootstraps (seed {args.seed}); margin {args.margin}.", "",
             "| Run | Instruction | Hybrid nDCG@10 delta (95% CI) | Verdict | Semantic nDCG@10 delta (95% CI) | Hybrid recall@100 delta | Sets losing > margin (hybrid) |",
             "|---|---|---|---|---|---|---|"]
    for run, instruction in candidates:
        primary = pairs(run, instruction, "hybrid", "ndcg10")
        if set(primary) != set(datasets):
            continue  # a candidate is judged only on every dataset
        p, lo, hi = interval(primary, args.bootstrap, rng)
        sp, slo, shi = interval(pairs(run, instruction, "semantic", "ndcg10"), args.bootstrap, rng)
        rp, rlo, rhi = interval(pairs(run, instruction, "hybrid", "recall100"), args.bootstrap, rng)
        per_set = {d: sum(v) / len(v) for d, v in primary.items()}
        flagged = [d for d, delta in per_set.items() if delta < -args.margin]
        entry = {"run": run, "instruction": instruction, "verdict": verdict(lo, hi, args.margin),
                 "hybrid_ndcg10": {"delta": p, "low": lo, "high": hi, "per_set": per_set},
                 "semantic_ndcg10": {"delta": sp, "low": slo, "high": shi},
                 "hybrid_recall100": {"delta": rp, "low": rlo, "high": rhi}, "flagged_sets": flagged}
        results["candidates"].append(entry)
        lines.append(f"| {run} | {instruction} | {p:+.4f} ({lo:+.4f}, {hi:+.4f}) | {entry['verdict']} | "
                     f"{sp:+.4f} ({slo:+.4f}, {shi:+.4f}) | {rp:+.4f} ({rlo:+.4f}, {rhi:+.4f}) | {', '.join(flagged) or '-'} |")

    lines += ["", "## Absolute scores", "", "| Dataset | Run | Instruction | Mode | nDCG@10 | Recall@10 | Recall@100 | ms/doc | Truncated |", "|---|---|---|---|---|---|---|---|---|"]
    latest = {}
    for record in summary:
        latest[(record["dataset"], record["run"], record["instruction"], record["mode"])] = record
    for key in sorted(latest):
        r = latest[key]
        lines.append(f"| {r['dataset']} | {r['run']} | {r['instruction']} | {r['mode']} | {r['ndcg10']:.4f} | {r['recall10']:.4f} | "
                     f"{r['recall100']:.4f} | {r.get('ms_per_doc', '-')} | {r.get('truncated', '-')} |")

    lines += ["", "## Hybrid against lexical (per set, paired)", "", "| Dataset | Run | Instruction | Hybrid - lexical nDCG@10 (95% CI) |", "|---|---|---|---|"]
    results["hybrid_vs_lexical"] = []
    for run, instruction in [(args.baseline, "off")] + candidates:
        for d in datasets:
            cand, lex = table.get((d, run, instruction, "hybrid")), table.get((d, "lexical", "-", "lexical"))
            if not cand or not lex:
                continue
            p, lo, hi = interval({d: [cand[q]["ndcg10"] - lex[q]["ndcg10"] for q in sorted(cand)]}, args.bootstrap, rng)
            results["hybrid_vs_lexical"].append({"dataset": d, "run": run, "instruction": instruction, "delta": p, "low": lo, "high": hi})
            lines.append(f"| {d} | {run} | {instruction} | {p:+.4f} ({lo:+.4f}, {hi:+.4f}) |")

    lines += ["", "## Hybrid nDCG@10 by the length of the longest relevant document (tokens)", "",
              "| Dataset | Run | Instruction | " + " | ".join(f"{name} (n)" for _, name in BUCKETS) + " |", "|---|---|---|" + "---|" * len(BUCKETS)]
    for d in datasets:
        for run, instruction in [(args.baseline, "off")] + candidates:
            cand = table.get((d, run, instruction, "hybrid"))
            if not cand:
                continue
            cells = []
            for limit, name in BUCKETS:
                lower = max([b for b, _ in BUCKETS if b < limit], default=0)
                values = [r["ndcg10"] for r in cand.values() if lower < r["longest_relevant_tokens"] <= limit]
                cells.append(f"{sum(values) / len(values):.3f} ({len(values)})" if values else "-")
            lines.append(f"| {d} | {run} | {instruction} | " + " | ".join(cells) + " |")

    lines += ["", "## Top-10 overlap with the reference run (semantic, raw queries)", "", "| Dataset | Run | Reference | Mean overlap@10 |", "|---|---|---|---|"]
    runs = sorted({k[1] for k in table if k[3] == "semantic"})
    for run in runs:
        reference = reference_of(run, runs)
        if reference is None:
            continue
        for d in datasets:
            a, b = table.get((d, run, "off", "semantic")), table.get((d, reference, "off", "semantic"))
            if a and b:
                overlap = sum(len(set(a[q]["top100"][:10]) & set(b[q]["top100"][:10])) / 10 for q in a) / len(a)
                lines.append(f"| {d} | {run} | {reference} | {overlap:.3f} |")

    report = "\n".join(lines) + "\n"
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            f.write(report)
        with open(os.path.splitext(args.report)[0] + ".json", "w", encoding="utf-8") as f:
            json.dump(results, f, indent=2)
            f.write("\n")
    print(report)


if __name__ == "__main__":
    main()
