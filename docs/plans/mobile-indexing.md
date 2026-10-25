# Plan: indexing speed on phones (measure, review, decide)

_Written October 25 by the cloud session for review by Codex before any measurement runs. Implements nothing by itself; the outcome is an owner decision and, if the model changes, an amendment to [ADR 0002](../adr/0002-embedding-runtime.md) ("When to revisit")._

## Why

With today's defaults (`bge-small-en-v1.5` fp32, 512 tokens, sorted batches of 4, arena on), a Pixel 8 embeds a mixed-length note in about 93 to 100 ms and a 512-token note in 455 to 600 ms. That is about 8 minutes for 5,100 typical notes and 40 to 50 minutes for 5,100 long ones (ROADMAP, Milestone 5, October 24). The JVM and the emulator on a Mac are 2 to 3 times faster, so they cannot stand in for the phone. The batching and arena work removed padding waste. Model size, input length and runtime settings have not been measured, and neither has their effect on retrieval quality.

Codex reviewed the embedder plan and the first implementation (October 24). It did not review the batching, arena and indexing-time analysis that set today's defaults. That analysis is part of this review.

## Proposed targets (owner to confirm)

- First indexing of 5,000 typical notes on a Pixel 8-class phone in **5 minutes or less**, in the background with progress. A new or edited note embeds in **200 ms or less**.
- Retrieval quality at most **2 nDCG@10 points** below `bge-small` fp32 at 512 tokens, averaged over the evaluation sets. Hybrid search must stay at least as good as lexical search on every set.
- Peak memory while indexing: **500 MB or less** PSS on Android. The model file adds **50 MB or less** to an app that bundles it, if it can be met at the quality target.

## Variables

| Variable | Values | Notes |
|---|---|---|
| Model | `bge-small-en-v1.5` fp32 (baseline); `bge-small` int8; `all-MiniLM-L6-v2` fp32; `all-MiniLM-L6-v2` int8 | We quantize int8 bge ourselves from the pinned fp32 file (`onnxruntime.quantization.quantize_dynamic`, ORT 1.30.0, recorded settings) so provenance stays with the BAAI pin; `Xenova/bge-small-en-v1.5` `onnx/model_int8.onnx` is a cross-check, not a candidate. MiniLM (Apache-2.0, 6 layers, mean pooling, the same uncased vocabulary) uses its repository's `onnx/model.onnx` and `onnx/model_qint8_arm64.onnx` at a pinned revision. |
| Input limit | 512, 256, 128 tokens | Cost grows at least linearly with tokens; most typical notes are under 256. MiniLM was trained at 128 and is limited to 256. |
| Intra-op threads | ORT default, 1, 2, 4 | The Pixel 8 has 1 + 4 + 4 cores of three sizes; the default may schedule work on the small cores. |
| Execution provider (Android) | CPU, XNNPACK | XNNPACK ships in `onnxruntime-android`; int8 dynamic-quantized operators may fall back to CPU. NNAPI is deprecated and out of scope. |
| Batching, arena | Today's defaults | Rechecked only for the chosen variant. |

The full product is not run; the order below prunes it.

## Phase A: quality and JVM speed (cloud session)

Branch `phase-5-mobile-indexing`, from `phase-5-onnx-embedder` (or from `main` once it is merged).

1. **Variant builder.**
   - `kvid-embed-onnx/scripts/build_variants.py` downloads the pinned files and writes each variant.
   - Each variant gets its own pins (revision and SHA-256), a provenance record (tool versions and quantization settings) and its own parity fixture against Python ONNX Runtime running the same file. Parity is required at cosine ≥ 0.9999, exactly as for fp32.
   - Also recorded: the cosine of each variant with bge fp32 on the 194 fixture texts (int8 drift, MiniLM is a different model).
2. **Retrieval evaluation through the store.**
   - `RetrievalEvalTest` (JVM, enabled by `KVID_RETRIEVAL_EVAL_DIR`) loads a corpus into a `Kvid` store and indexes it with each variant.
   - It scores `find` (BM25), `findSimilar` and `findHybrid` (k = 60, 100 candidates) with nDCG@10, recall@10 and recall@100, so the numbers are kvid's own.
   - It also records time per document and tokens per document.
   - Datasets (BEIR, CC-BY-SA-4.0, downloaded at pinned dataset revisions by a script, never committed): SciFact (5,183 documents, 300 test queries), NFCorpus (3,633 documents, 323 queries), and a FiQA-2018 subset (all judged documents plus random distractors up to 20,000).
   - These are proxies for notes, not notes. A notes-style set is the next roadmap step (evaluation corpus); this plan reports which variants it should be run on.
3. **Prune.** Variants or input limits more than 2 nDCG@10 points below the baseline on average are dropped before Phase B, unless their speed gain is at least twice that of every surviving variant.
4. **Tuning-test extension** (compiled and run on the JVM here; the Android build is checked here; device runs are Phase B). `KVID_EMBEDDING_TUNE` gains `model=…`, `maxTokens=…`, `threads=…` and `ep=cpu|xnnpack`. Model variants are internal `BertEmbeddingModel` values, so the public API does not change before the decision.

## Phase B: device speed and memory (local agent)

- **Devices:** the Pixel 8 (decides) and the local API 35 emulator (reference). Also any second physical phone available, ideally a mid-range one.
- **Configurations:** for each surviving variant × input limit, with CPU and default threads: the `mixed` and `m3` corpora, 200 notes per run. Then threads 1, 2 and 4, and XNNPACK, on the best two variants only.
- **Protocol** (as in the cooled arena A/B of October 24):
  - start cool and record the battery temperature and `dumpsys thermalservice` status at the start and end of each run;
  - interleave the configurations being compared (A B A B), with at least 3 runs each, and report medians and the range;
  - screen on, charging state recorded, no other app in the foreground.
- **Reported per run:** milliseconds per note, tokens per second, PSS peak and after the call, open time, model file size, and the native library size if the EP changes it.
- **Output:** tables in the ROADMAP (Milestone 5), and the raw `[kvid-embedding-tune]` lines kept in the planning branch under `docs/measurements/`.

## Phase C: review and decision

- **Codex** reviews this plan before Phase A runs (questions below), and reviews the Phase A and B results and the recommendation before the owner decides.
- **Recommendation and decision.** The cloud session writes the recommendation: model, input limit, threads and EP, and whether long notes should be chunked rather than truncated. The owner decides; ADR 0002 is amended if the model changes.
- **Separate design: background indexing in the sample.** Whatever the variant, first indexing of thousands of notes needs this: WorkManager on Android, `BGProcessingTask` on iOS, progress from `VectorStatus.pending`, incremental indexing on edit, and closing the embedder after bulk work. It is a separate design item that this plan does not settle.

## Questions for Codex

1. Are the variants and ranges right? Is something worth adding: fp16, ORT's O4-optimized MiniLM, other small English models (`e5-small-v2`, `gte-small`), or static rather than dynamic int8?
2. Is our own `quantize_dynamic` of the pinned fp32 model sound (per-channel or per-tensor, `reduce_range`, which operators), and is cosine with fp32 on 194 texts enough to quantify drift?
3. Are SciFact, NFCorpus and a FiQA subset reasonable proxies for personal notes, and is the pruning rule (2 nDCG points, unless twice as fast) sensible? What should the notes-style evaluation set contain, and how do we keep its author (an LLM) from favouring one model?
4. Is the device protocol sufficient for thermal noise, interleaving, repetitions and statistics? What would make a result inconclusive?
5. Are the proposed targets (5 minutes for 5,000 notes, 200 ms per note, 2 nDCG points, 500 MB, 50 MB) reasonable for an embedded notes store? Which matter most?
6. An independent read of the October 24 batching and arena analysis (ROADMAP tables): are the conclusions (sorted batches of 4, arena on by default) supported by the data?
