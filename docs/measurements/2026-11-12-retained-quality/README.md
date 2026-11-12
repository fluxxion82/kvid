# Quality of the retained candidates under the frozen rule (November 12, 2026)

The 128-token candidates the frozen rule keeps (Codex's first P2 on Phase A), on full FiQA. Then the decision analysis for all three retained candidates, `bge-fp32@128`, `minilm-fp32@128` and `minilm-fp32@256`, against `bge-fp32@512` on SciFact, NFCorpus and full FiQA, with raw queries. The rule is the one preregistered in [docs/plans/mobile-indexing.md](../../plans/mobile-indexing.md) at planning `13262ad` and is unchanged. Results and reading: "Quality of the retained candidates" in that plan.

## Runs

- **The new runs:** `bge-fp32@128` and `minilm-fp32@128` on full FiQA, instruction off. FiQA has 57,600 indexed documents and 648 test queries, the same as Phase A's full-FiQA runs.
  - The tooling is `phase-5-mobile-indexing` `586cbde`, Phase A's, so the records are comparable.
  - Platform: the JVM (OpenJDK 17.0.20.1) on Linux x86_64, 4 cores (Xeon 2.1 GHz) and 15 GB, with ONNX Runtime 1.30.0 on the CPU provider.
  - Duration: 35 minutes in one Gradle invocation.
  - The model files are Phase A's (`variants.json` there), with the same digests.
- **Reused from Phase A**, from its archived per-query records:
  - SciFact and NFCorpus for all five runs (lexical, baseline, three candidates);
  - full FiQA for lexical, the baseline and `minilm-fp32@256`.
- **Analysis.** `analyze_retrieval_eval.py` from `phase-5-mobile-indexing` after `586cbde`, which adds per-set intervals against the baseline (Codex's third P2 on Phase A).
  - The macro rule's code is unchanged.
  - On Phase A's records it reproduces Phase A's final table byte for byte.

| File | Content |
|---|---|
| `fiqa-full-128-summary.jsonl` | RetrievalEvalTest summary records of the new runs |
| `fiqa-full-128-queries.jsonl.gz` | Their per-query records (gzip; uncompressed digest in `SHA256SUMS`) |
| `combine_retained.py` | Selects the records the analysis uses from Phase A's and the new outputs |
| `report.md`, `.json` | The decision analysis |
| `SHA256SUMS` | Digests of the records, compressed and uncompressed |

## Reproducing the analysis

From the archived records, no model runs needed:

```sh
S=work; mkdir -p $S/eval-out $S/eval-full-out $S/eval-full-128-out
gunzip -c ../2026-11-09-phase-a/records/screening-queries.jsonl.gz > $S/eval-out/queries.jsonl
cp ../2026-11-09-phase-a/screening-summary.jsonl $S/eval-out/summary.jsonl
gunzip -c ../2026-11-09-phase-a/records/fiqa-full-queries.jsonl.gz > $S/eval-full-out/queries.jsonl
cp ../2026-11-09-phase-a/fiqa-full-summary.jsonl $S/eval-full-out/summary.jsonl
gunzip -c fiqa-full-128-queries.jsonl.gz > $S/eval-full-128-out/queries.jsonl
cp fiqa-full-128-summary.jsonl $S/eval-full-128-out/summary.jsonl
python3 -I combine_retained.py $S                       # writes $S/retained-out
# DATA holds scifact/ and nfcorpus/ from prepare_retrieval_eval.py (screening) and fiqa/ from the full preparation
python3 -I analyze_retrieval_eval.py $S/retained-out DATA --report report.md
```

Phase A's screening records contain the FiQA-subset lexical block twice. The selection drops FiQA-subset records altogether, so the duplicate does not reach this analysis. (`analyze_retrieval_eval.py` keys records by dataset, run, instruction, mode and query, so a repeated record would replace the earlier one rather than count twice.)

## What to know when reading the numbers

- **The bootstrap draws from one seeded stream, in candidate order.** Two candidates now come before `minilm-fp32@256`, so its interval differs from Phase A's in the fourth decimal: −0.0122 (−0.0204, −0.0040) against (−0.0205, −0.0041). That is Monte Carlo variation; the verdict is the same. The per-set intervals use a second stream (seed + 1), so adding them changes nothing in the macro table.
- **The per-set intervals are reported beside the rule, not as part of it.** The verdict is the macro one alone.
- **The JVM times per document are diagnostics only.**
