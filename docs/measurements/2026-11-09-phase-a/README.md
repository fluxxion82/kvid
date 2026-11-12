# Phase A of the mobile indexing study: retrieval quality and JVM time (November 9, 2026)

Preregistered in [docs/plans/mobile-indexing.md](../../plans/mobile-indexing.md) at planning `13262ad`, run by the cloud session at `phase-5-mobile-indexing` `586cbde`. The runs used the JVM (OpenJDK 17) on a Linux x86_64 container with 4 cores (Xeon 2.1 GHz) and 15 GB, with ONNX Runtime 1.30.0 on the CPU provider. Times here are diagnostics only; device runs decide speed.

| File | Content |
|---|---|
| `variants.json` | The seven model variants: recipe, file size and digest, operator counts, initializer bytes by type, and drift from the fp32 reference (fixture texts) |
| `screening-summary.jsonl` | RetrievalEvalTest summary records: SciFact, NFCorpus and the frozen FiQA subset (20,000 documents), every run and instruction policy |
| `screening-report.md`, `.json` | `analyze_retrieval_eval.py` on the screening records |
| `fiqa-full-summary.jsonl` | The frontier on full FiQA (57,638 documents), raw queries |
| `final-report.md`, `.json` | The decision analysis: SciFact, NFCorpus and full FiQA, frontier runs, raw queries |
| `records/screening-queries.jsonl.gz` | The original per-query records of the screening runs (48 MB uncompressed): per query, each metric and the top 100 |
| `records/fiqa-full-queries.jsonl.gz` | The same for the full-FiQA runs (10 MB uncompressed) |
| `records/SHA256SUMS`, `records/SHA256SUMS-uncompressed` | Their digests, compressed and after `gunzip` |

**The per-query records were archived on November 12** at Codex's request in its review of Phase A. They are the files the reports above were computed from, not a rerun. With them, the paired intervals, overlaps, length buckets and the `trec_eval` cross-check can be audited without running a model; [2026-11-12-retained-quality](../2026-11-12-retained-quality/README.md) shows how. Both files contain their FiQA lexical block twice, because each of the two FiQA invocations wrote the lexical run (corrected after Codex's review; this said the screening file only). The copies are identical, and the analysis keys records by dataset, run, instruction, mode and query, so the second replaces the first.

To regenerate everything from scratch instead, from the code branch:

```sh
pip install -r kvid-embed-onnx/scripts/requirements-study.txt
python -I kvid-embed-onnx/scripts/build_variants.py VARIANTS
python -I kvid-embed-onnx/scripts/prepare_retrieval_eval.py EVAL                                    # screening sets
python -I kvid-embed-onnx/scripts/prepare_retrieval_eval.py EVAL_FULL --datasets fiqa --max-docs 1000000
KVID_MODEL_VARIANTS_DIR=VARIANTS KVID_RETRIEVAL_EVAL_DIR=EVAL KVID_RETRIEVAL_EVAL_OUT=OUT \
  KVID_RETRIEVAL_EVAL='datasets=scifact,runs=bge-fp32@512+...,instruction=off+on' \
  ./gradlew :kvid-embed-onnx:jvmTest --tests com.kvid.embed.RetrievalEvalTest
python -I kvid-embed-onnx/scripts/analyze_retrieval_eval.py OUT EVAL --report report.md
```

Each Gradle test task is capped at 90 minutes. One invocation per dataset fits, and FiQA needs two. The datasets are BEIR's (CC-BY-SA-4.0), at the revisions and digests in `kvid-embed-onnx/scripts/retrieval-datasets.json`.
