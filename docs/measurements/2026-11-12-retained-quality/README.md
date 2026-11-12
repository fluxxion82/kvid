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

_Changed after Codex's review (finding 9): the commands now say where the analyzer is, where to run them from, and what must be prepared first._

No model runs are needed, but the analyzer re-reads each dataset's `qrels.tsv`, so the datasets must be prepared once (they are BEIR's, CC-BY-SA-4.0, never committed). Run everything from a checkout of the code branch at the analyzer's commit. `A` is this directory's parent in a checkout of `planning`.

```sh
git checkout 6e41d47          # phase-5-mobile-indexing; the analyzer is unchanged at 31f1e0c
A=/path/to/planning/docs/measurements
python3 -m venv venv && venv/bin/pip install -r kvid-embed-onnx/scripts/requirements-study.txt   # pytrec-eval for the cross-check

# Prepared datasets: SciFact and NFCorpus from the screening preparation, FiQA from the full one
venv/bin/python -I kvid-embed-onnx/scripts/prepare_retrieval_eval.py EVAL
venv/bin/python -I kvid-embed-onnx/scripts/prepare_retrieval_eval.py EVAL_FULL --datasets fiqa --max-docs 1000000
mkdir -p DATA && ln -s "$PWD/EVAL/scifact" DATA/scifact && ln -s "$PWD/EVAL/nfcorpus" DATA/nfcorpus && ln -s "$PWD/EVAL_FULL/fiqa" DATA/fiqa

# The archived records, in the layout combine_retained.py expects
W=work; mkdir -p $W/eval-out $W/eval-full-out $W/eval-full-128-out
gunzip -c $A/2026-11-09-phase-a/records/screening-queries.jsonl.gz > $W/eval-out/queries.jsonl
cp $A/2026-11-09-phase-a/screening-summary.jsonl $W/eval-out/summary.jsonl
gunzip -c $A/2026-11-09-phase-a/records/fiqa-full-queries.jsonl.gz > $W/eval-full-out/queries.jsonl
cp $A/2026-11-09-phase-a/fiqa-full-summary.jsonl $W/eval-full-out/summary.jsonl
gunzip -c $A/2026-11-12-retained-quality/fiqa-full-128-queries.jsonl.gz > $W/eval-full-128-out/queries.jsonl
cp $A/2026-11-12-retained-quality/fiqa-full-128-summary.jsonl $W/eval-full-128-out/summary.jsonl

python3 -I $A/2026-11-12-retained-quality/combine_retained.py $W        # writes $W/retained-out
venv/bin/python -I kvid-embed-onnx/scripts/analyze_retrieval_eval.py $W/retained-out DATA --report report.md
diff report.md $A/2026-11-12-retained-quality/report.md                # no output: identical
```

The uncompressed records can be checked against `SHA256SUMS-uncompressed` here and in `../2026-11-09-phase-a/records/`.

**Duplicates** _(corrected after Codex's review)_. Both of Phase A's record files contain an identical duplicate FiQA lexical block: each of the two FiQA invocations wrote the lexical run.
- The selection drops the screening file's FiQA-subset records altogether.
- The full-FiQA duplicate does reach this analysis. `analyze_retrieval_eval.py` keys records by dataset, run, instruction, mode and query, so the second copy replaces the first and nothing counts twice. Codex checked the deduplication.

## What to know when reading the numbers

- **The bootstrap draws from one seeded stream, in candidate order.** Two candidates now come before `minilm-fp32@256`, so its interval differs from Phase A's in the fourth decimal: −0.0122 (−0.0204, −0.0040) against (−0.0205, −0.0041). That is Monte Carlo variation; the verdict is the same. The per-set intervals use a second stream (seed + 1), so adding them changes nothing in the macro table.
- **The per-set intervals are reported beside the rule, not as part of it.** The verdict is the macro one alone.
- **The JVM times per document are diagnostics only.**
