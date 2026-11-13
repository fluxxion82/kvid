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

## Status of Phase A tooling (October 25, cloud session)

Branch `phase-5-mobile-indexing`, commit `ec36772` (from `0d85781`). No measurements are run or reported until Codex has reviewed this plan; the tooling was checked on small subsets only.

- **Variants.** `kvid-embed-onnx/scripts/build_variants.py` builds `bge-fp32`, `bge-int8`, `minilm-fp32` and `minilm-int8`.
  - Each variant directory is an embedding parity fixture made by Python ONNX Runtime from that file, with pooling and input limit recorded in `source.json`.
  - The files are pinned by revision and SHA-256 (`model-pins*.json`).
  - Our int8 bge comes from `quantize_dynamic` (QInt8, per tensor, no `reduce_range`, ONNX Runtime 1.30.0, onnx 1.23.2). It is deterministic: two builds gave the same digest, now pinned.
  - Sizes: 133.1, 33.8, 90.4 and 23.0 MB.
  - `OnnxEmbedderParityTest` now reads the model contract from the fixture. On the JVM (Linux x86_64) all four variants pass at cosine ≥ 0.9999.
  - MiniLM's `vocab.txt` is byte-identical to bge's and its tokenizer settings match, so the existing tokenizer applies.
- **Finding: dynamic int8 is not batch-invariant.** `DynamicQuantizeLinear` takes one activation scale over the whole input tensor, other texts and padding included. A text's vector therefore depends on its batch: down to cosine 0.983 against the same text embedded alone (`bge-int8`), and similar for `minilm-int8`.
  - The int8 fixtures record `max_batch_size: 1`, and the tests and the evaluation embed those variants one text at a time.
  - A product decision for int8 must choose between batch size 1 (on the Pixel 8 that cost about 5% for `mixed` notes on October 24) and static quantization (fixed, calibrated scales, which are batch-invariant). Static int8 should be added as a variant if Codex agrees (question 2).
- **Finding: int8 portability is unmeasured.** The reference fixtures were made on x86_64. int8 kernels differ between x86 (u8s8 paths) and ARM, so Phase B must run each variant's parity on the phone before measuring its speed. If ARM and x86 int8 vectors differ beyond the ADR's 0.9999, an int8 spec is not portable across platforms.
- **Retrieval evaluation.**
  - `scripts/prepare_retrieval_eval.py` writes SciFact, NFCorpus and a FiQA subset at pinned dataset revisions and digests (`retrieval-datasets.json`).
  - `RetrievalEvalTest` (JVM) indexes them in a `Kvid` store and scores `find` (any word), `findSimilar` and `findHybrid` with nDCG@10 (graded gains), recall@10 and recall@100. It runs per variant, input limit and query instruction: bge's "Represent this sentence for searching relevant passages: " is applied to queries only, through a wrapper embedder with the same spec.
  - The metric code is checked against hand-computed values. A smoke run on 300-document subsets of all three datasets produced every expected record with no failed queries.
- **Tuning settings.** `OnnxEmbedderTuningTest` takes `model`, `maxTokens`, `threads` and `ep` (`cpu` or `xnnpack`). A variant with `max_batch_size` defaults to it; a larger batch is marked `batch_invariant=false`.
  - XNNPACK is in the `onnxruntime-android` AAR (checked in `libonnxruntime.so`) but not in the JVM build, which rejects it.
  - The device-test APK with these changes builds; it has not run on a device.

### Phase B commands (local agent; superseded by the November 8 block below)

```sh
python -m venv build/study/venv && build/study/venv/bin/pip install -r kvid-embed-onnx/scripts/requirements-study.txt
build/study/venv/bin/python -I kvid-embed-onnx/scripts/build_variants.py build/study/variants
adb shell rm -rf /data/local/tmp/kvid-model-variants && adb push build/study/variants /data/local/tmp/kvid-model-variants
ARGS=-Pandroid.testInstrumentationRunnerArguments
# 1. Parity of each variant on the device (ARM against the x86 or Mac reference), before any timing:
for v in bge-fp32 bge-int8 minilm-fp32 minilm-int8; do
  ./gradlew :kvid-embed-onnx:connectedAndroidDeviceTest -Pkvid.deviceTestAbi=arm64-v8a $ARGS.class=com.kvid.embed.OnnxEmbedderParityTest \
    $ARGS.KVID_EMBEDDING_FIXTURE_DIR=/data/local/tmp/kvid-model-variants/$v $ARGS.KVID_REQUIRE_EMBEDDING_FIXTURE=true
  adb logcat -d -s System.out | grep -F "[kvid-embedding]"; adb logcat -c
done
# 2. One configuration per run ('+' separates settings in instrumentation arguments):
./gradlew :kvid-embed-onnx:connectedAndroidDeviceTest -Pkvid.deviceTestAbi=arm64-v8a $ARGS.class=com.kvid.embed.OnnxEmbedderTuningTest \
  $ARGS.KVID_MODEL_VARIANTS_DIR=/data/local/tmp/kvid-model-variants $ARGS.KVID_EMBEDDING_TUNE=corpus=mixed+notes=200+model=bge-int8+threads=4
adb logcat -d -s System.out | grep -F "[kvid-embedding-tune]"
```

## Questions for Codex

1. Are the variants and ranges right? Is something worth adding: fp16, ORT's O4-optimized MiniLM, other small English models (`e5-small-v2`, `gte-small`), or static rather than dynamic int8?
2. Is our own `quantize_dynamic` of the pinned fp32 model sound (per-channel or per-tensor, `reduce_range`, which operators), and is cosine with fp32 on 194 texts enough to quantify drift? Given the batch dependence found above, should static (calibrated) int8 replace or join dynamic int8, and what calibration data would be representative of notes? Is an int8 spec portable between x86 and ARM kernels, and how should that be tested?
3. Are SciFact, NFCorpus and a FiQA subset reasonable proxies for personal notes, and is the pruning rule (2 nDCG points, unless twice as fast) sensible? What should the notes-style evaluation set contain, and how do we keep its author (an LLM) from favouring one model?
4. Is the device protocol sufficient for thermal noise, interleaving, repetitions and statistics? What would make a result inconclusive?
5. Are the proposed targets (5 minutes for 5,000 notes, 200 ms per note, 2 nDCG points, 500 MB, 50 MB) reasonable for an embedded notes store? Which matter most?
6. An independent read of the October 24 batching and arena analysis (ROADMAP tables): are the conclusions (sorted batches of 4, arena on by default) supported by the data?

## Review

_October 25, Codex. Reviewed this plan and Milestone 5 of `docs/ROADMAP.md` on planning, and read `OnnxOptions`, `OnnxEmbedderTuningTest`, the embedding pipeline, parity/measurement tests and `Kvid.indexVectors` on `phase-5-onnx-embedder` at `0d857813267a6a3346e9522e08b6473aa52abdb9`. No Gradle, device tests or new measurements were run. The October 24 conclusions below are an independent analysis of the published tables and harness, not a reproduction: raw run logs and per-run thermal traces are not present in this checkout._

**Assessment: proceed with the study after the following protocol amendments.** The four initial variants are a useful scope. Sorting, a maximum batch size of four, and arena on are defensible provisional defaults for the measured fp32/CPU workload. The evidence does not establish a universal optimum or a five-minute full-store indexing time.

Before measurement, resolve these actionable gaps:

- **[P1] Measure the store's actual call boundaries.** The tuning test calls `embed` once with 200 notes, allowing a global length sort. `Kvid.indexVectors` defaults to 32 documents per call, sorting only within each group of 32; it also reads documents, validates/encodes vectors and commits transactions. The study needs both the existing inference microbenchmark and a timed full-store run. Multiplying its current `per_note_ms` by 5,000 cannot establish the indexing target.
- **[P1] Freeze the quality gate and separate development from final evaluation.** The current average can hide a serious regression on one set, and its speed exception allows a quality failure back into the recommendation. JVM throughput cannot decide that exception for ARM phones. Use the decision rule in answer 3, retaining the existing baseline through device measurement.
- **[P2] Counterbalance runs and measure sustained operation.** Always running A before B leaves order effects in the arena A/B; three short runs with medians/ranges cannot validate five minutes of background indexing or a 200 ms tail-latency target. Use answer 4's run-level comparisons and final workload.
- **[P2] Extend and validate the measurement configuration first.** Today's tuning parser does not implement model, token-limit, thread or EP selection, silently accepts unknown keys, and treats every non-`on` flag value as false. A misspelled future option could produce a plausible baseline result. Reject unknown keys/invalid values, require positive note counts, and print the resolved settings and artifact hashes, not just the requested string. Stop/join the sampler in `finally` if embedding fails.

### 1. Variants, lengths and runtime settings

Keep bge fp32/int8 and MiniLM fp32/int8 as the first pass. Test bge at 512/256/128 and MiniLM at 256/128, including special tokens. MiniLM's published default truncates at 256 wordpieces and its fine-tuning used 128; 256 is a supported evaluation policy, not an architectural ceiling. Do not add MiniLM 512 to this initial matrix. Verify each model's vocabulary, normalization, special tokens, graph inputs/output name and pooling independently; sharing an uncased vocabulary alone is insufficient. See the [MiniLM model card](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2).

Add one **CPU-targeted, offline transformer-optimized fp32 graph** for each model as a small exploratory comparison (O2 or the equivalent pinned ORT transformer optimizer). The current backend already sets `ALL_OPT` at session creation, so prove that offline optimization adds useful fusions rather than assuming another speedup. Retain the original graph as the control; optimized graphs have their own digests and parity checks. Inspect fusions before quantization, since fusion and quantization order affect which operators can be quantized.

Defer fp16/O4 from the initial CPU study. O4 includes mixed precision and approximate GELU, rather than being a pure fp32 graph optimization; fp16 kernel coverage and casts must be checked on the exact mobile build. Smaller weights do not establish CPU acceleration. If explored later, preserve float32 external output for the current backend and measure the actual EP placement. [Optimum optimization levels](https://huggingface.co/docs/optimum-onnx/onnxruntime/package_reference/configuration), [ORT float16 guidance](https://onnxruntime.ai/docs/performance/model-optimizations/float16.html).

Keep `e5-small-v2` and `gte-small` as a second round if neither current family meets the quality/speed tradeoff. Adding more families now expands evaluation and introduces model-specific query/document preprocessing. Any later candidate must use its own documented preprocessing, pooling and identity.

CPU thread counts default/1/2/4 are a sensible first sweep. Measure spinning policy on the finalists, especially for sustained/background work; log actual CPU/OS/ABI and options rather than inferring core use from thread count. Keep CPU as the mandatory control. For XNNPACK, first verify availability in the pinned AAR, then profile node placement/fallback. Record **both** ORT and XNNPACK thread-pool sizes: ORT's recommended starting point is ORT intra-op 1, spinning off, and a separately configured XNNPACK pool. Testing only `intraOpThreads` would not tune that pool. The documented XNNPACK operator/shape support is limited, so registration alone is not evidence that BERT's expensive operations use it. [ORT XNNPACK documentation](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html).

### 2. Quantization, provenance and parity

Quantizing the pinned BAAI fp32 graph locally is sound; name the resulting artifact as a project-derived model rather than implying BAAI supplied it. Start with this explicit, conservative recipe, verifying the API against the installed ORT 1.30.0 package:

- `quantize_dynamic`, `weight_type=QuantType.QInt8`, `per_channel=True`, `reduce_range=False`, `op_types_to_quantize=["MatMul"]`, `extra_options={"MatMulConstBOnly": True}`. This targets constant-weight projections/feed-forward layers; leave nonconstant attention products and normalization/softmax floating point initially. Inspect/report the resulting graph's integer operators and remaining float weights. Dynamic activation quantization is not the same format as static signed-int8 QDQ.
- Compare per-tensor weights on one finalist if per-channel kernel cost matters. Reduced range is an x86 saturation workaround, not the ARM starting point; do not choose it from an x86 JVM speed result. ORT recommends dynamic quantization for transformers; the ARM64 Optimum preset also offers per-channel weights. [ORT quantization guidance](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html), [Optimum ARM64 configuration](https://huggingface.co/docs/optimum-onnx/onnxruntime/package_reference/configuration), [quantizer API/source](https://github.com/microsoft/onnxruntime/blob/main/onnxruntime/python/tools/quantization/quantize.py).
- Pin Python, ONNX, ORT, optimizer/exporter versions, source revision/digests, preprocessing and optimizer flags, quantization flags/node exclusions, opset, and every output file hash (including external tensor data if used). Keep an operator/weight-byte summary. Pin the upstream MiniLM int8 artifact similarly; its filename does not document its recipe. A third-party cross-check must have its own pin and cannot establish parity with a different graph.

**Do not promise a 35 MB bge file from MatMul-only quantization.** A 30,522 × 384 fp32 vocabulary embedding table alone is about 46.9 MB (decimal), before the remaining weights. If the conservative artifact exceeds 50 MB, evaluate supported embedding/Gather weight quantization as a separately named variant with new checks, or report the size target unmet. Neither dynamic nor static int8 guarantees all weights are quantized or a fourfold size reduction.

Static int8 is a contingency if dynamic int8 misses device targets: try a CPU-compatible QDQ graph with a frozen, representative calibration set spanning note/query lengths and content. Keep calibration disjoint from final evaluation; record its seed/hash, padding policy, algorithm and quantized nodes. Static quantization trades calibration sensitivity for avoiding dynamic scale computation, so compare both quality and device time before selecting it.

Keep the 194-case fixture for implementation parity **against Python running the identical graph**, at cosine ≥ 0.9999, with exact token IDs, finite/unit vectors and reported absolute differences. Extend it for each input limit, mean pooling, empty/Unicode text and truncation boundaries. Check sorted/unsorted batches and batches 1/4/8 against the same texts on every selected EP/platform. Dynamic scales can depend on batch composition; the existing batched-vs-single ≥ 0.99999 check and `OnnxOptions` claim that settings change only rounding must be verified for int8, not silently assumed or relaxed. Investigate a failure before treating those vectors as interchangeable across devices and batching options.

Cosine drift from **the same model's** fp32 output is diagnostic, not a retrieval-quality gate. Report percentiles and worst cases on held-out queries/documents, plus top-k overlap and ranking changes. Cosine between MiniLM and bge embeddings is not meaningful quality evidence: their coordinate systems differ despite both having 384 dimensions. Only labeled retrieval can compare them.

### 3. Benchmarks, bias and the pruning rule

SciFact and NFCorpus are useful small domain stress tests; FiQA adds a different domain. They are weak proxies for personal notes: scientific/medical/financial relevance differs from remembering a person, an appointment or a decision, and two sets are close in subject matter. Treat them as regression evidence rather than proof of notes quality or unseen training data. The [BEIR dataset inventory](https://github.com/beir-cellar/beir#available-datasets) lists 300 SciFact, 323 NFCorpus and 648 FiQA test queries; FiQA's full corpus is about 57K documents.

Prefer full FiQA for final confirmation. If the 20,000-document subset is needed for screening, freeze all query IDs, qrels and document IDs before scoring: retain all documents judged relevant to those queries and sample the rest with a recorded seed. Define precisely whether other judged documents are retained, report counts, and repeat finalists on full FiQA. Random distractors omit hard negatives and change the task; label the result “FiQA subset,” never compare its absolute score with published full-corpus scores. Pin corpus/query/qrels revisions and checksums and retain each dataset's license/attribution metadata.

Freeze title/body concatenation (the store embeds title, blank line, body), document IDs/order, query handling, lexical options, tie handling, relevance gain/discount and zero-relevance-query policy. Save per-query scores and rankings; cross-check metric calculations against BEIR/`trec_eval` on a small fixed example. Measure truncation rate, original and retained token distributions and results by length. Keep documents whole when testing truncation; a chunking policy must be a separately evaluated document-level system with deduplication and aggregation specified.

Use the current raw-query behavior for the principal bge baseline. BGE's authors also recommend an instruction for short-query passage retrieval; if tested, report it as a separately frozen policy, applied only to dense query encoding, while BM25 receives the original query. Never improve one baseline opportunistically after seeing test scores. [BGE model card](https://huggingface.co/BAAI/bge-small-en-v1.5).

**Replace pruning with a preregistered noninferiority rule:** “2 points” means an absolute 0.02 on a 0–1 nDCG scale. Report semantic and hybrid deltas separately, each against its matching bge-fp32/512 baseline, and macro-average across datasets (equal dataset weights). Require the lower bound of a paired, query-resampled 95% interval for the primary metric's macro delta to be at least −0.02 for a quality pass; also show each set's interval and flag any set's point loss beyond 0.02 for owner review. Predeclare hybrid nDCG@10 as the primary product metric, with semantic nDCG and recall as guardrails, so fusion cannot conceal a dense-retrieval failure. A clear failure can be screened out; an interval spanning the boundary is inconclusive, not a pass.

Keep the proposed per-set hybrid ≥ lexical requirement as a point-score guardrail, with paired uncertainty reported; a point advantage whose interval includes zero is not evidence of improvement. Do not demand that hybrid win on every individual query. Fix RRF k=60 and 100 candidates for the comparison, with any tuning restricted to development data. Remove the “twice as fast” quality waiver: keep such a model only as an explicitly failed exploratory tradeoff, never the default recommendation without a changed owner target. JVM speed is diagnostic; device results decide mobile speed. Retain baseline plus representatives of the quality/size/speed frontier for Phase B, including uncertain candidates rather than pruning them on noise.

A notes set should contain at least several hundred held-out queries across independently written note collections: short fragments, lists, meetings, journal entries, recipes, project decisions, names, dates/numbers, identifiers, code/URLs, typos, paraphrases, negation, near-duplicates and competing versions. Include exact-match and semantic intents, multiple relevant notes, hard negatives, and answers located after tokens 128/256/512. Include no-answer queries in a separate false-positive analysis; nDCG cannot assess them. State that an English-model decision does not validate multilingual notes.

Freeze content and intent specifications before testing models. Prefer consented, anonymized human notes and naturally elicited queries where feasible. If an LLM authors examples, give it no model names, rankings or preferred outcome; use multiple authors/templates, split by collection/topic before tuning, and have humans independently judge relevance with a rubric and resolve disagreements. Pool candidates from lexical and all dense systems, randomize presentation and hide system identities when judging. Do not let the same LLM both author and solely judge its intended answer, or rewrite test examples after seeing which model wins. Run a small frozen notes set on finalists before selecting a new default; a wholly synthetic set alone leaves the decision provisional.

### 4. Device protocol and statistics

Use the Pixel 8 as the reference decision device, an emulator as a harness check, and a physical mid-range device before claiming a general phone target. Record commit, build mode, runtime/artifact hashes, ABI, OS build, CPU, available memory, corpus hash/seed, resolved options and process ID for every run. Use the same APK and fresh target process/session per configuration; verify runs actually executed and preserve raw lines plus metadata. Extend token/padding accounting to use the selected model and limit: the current test hardcodes bge and 512.

For screening, three runs per configuration are adequate to find large differences. For the best two variants and baseline, use at least six paired blocks across two cooled sessions, balancing AB and BA (or randomizing a recorded balanced order); use a balanced order across more configurations. Fix ambient conditions, battery range, charging/power-saver state, brightness and screen state. Define the cooling acceptance rule beforehand, for example battery temperature within 1 °C of the agreed baseline and no thermal throttling status, and record thermal status/temperature during each run as well as at its endpoints. Battery temperature alone is a lagging proxy for CPU heat. Keep screen-on screening consistent, then separately validate the intended screen-off/background condition. Record interruptions and exclusions; retain excluded logs and repeat the whole affected block rather than selecting favorable samples.

Separate cold open/digest/session/warm-up from warmed inference. The existing `open` warms only an empty two-token input, not representative batch shapes. Specify identical representative warm-up work for steady-state comparisons, outside timing, and distinguish it from cold-start results. Report real and padded tokens/sec, milliseconds per corpus, and throughput per note. For batch-1 edits/queries measure individual p50/p95 (enough calls to estimate a tail, e.g. ≥100 across runs) in length buckets, including long notes; an aggregate `total_ms / notes` is not a latency distribution.

Treat runs/blocks as the independent performance observations, not 200 notes sharing one temperature/session. Publish every run, median/range, paired differences or ratios and a block-resampled 95% interval; increase repetitions if it is too wide. Predeclare a practically useful speed difference (suggestion: 10%). If the interval includes no gain or cannot resolve that threshold, call speed inconclusive. For quality, resample paired queries within each dataset; repeated deterministic inference does not create new quality samples.

The current 100 ms PSS sampler provides a **sampled lower bound** on peak process PSS, starting only after open; it can miss brief peaks and adds overhead. Report absolute and baseline-relative PSS, include opening/warm-up in full-lifecycle sampling, and capture after-call, after-close and delayed-idle memory. Keep the sampling method identical and check its overhead on a finalist. Record KiB from the raw counter and label converted units explicitly (MB versus MiB). A peak near the 500 MB limit needs higher-resolution/independent confirmation, rather than a pass based on one missed sample. These test-process figures also need confirmation in a representative app process.

Finish with actual 5,000-note `Kvid.indexVectors` runs on baseline and finalists: fixed notes/order and token histogram, default outer batch 32 (and any proposed alternative explicitly labeled), sorted internal batches, real vector writes, no concurrent mutation, and elapsed time until pending=0. Preload document insertion outside the timer; report it separately. Include open/close in the user-visible total and measure ≥5 minutes of sustained load or the whole index when longer, reporting early/late throughput, thermal state and battery/energy cost. A synthetic 200-note microbenchmark is screening evidence only. Background scheduling/progress remains a separate implementation task; a foreground run cannot validate its behavior.

Call a comparison inconclusive when thermal/order effects remain, balanced runs disagree, intervals cross the decision boundary, logs/settings are incomplete, the EP silently falls back, memory sampling is too coarse near the limit, or the real notes/long-run workload is absent. Keep the current default in that case and report the missing evidence.

### 5. Targets and priorities

The proposed numbers are reasonable **aspirational owner targets**, not established feasibility limits. Prioritize portable/correct vectors and retrieval quality, then foreground responsiveness and avoiding memory pressure, then sustained indexing time/energy, then download size. Report a Pareto table if no candidate meets everything; do not choose a fast model by silently waiving quality or a memory failure.

- **5 minutes / 5,000 typical notes:** means at least 16.7 notes/sec, or 60 ms/note including the declared lifecycle/store work. At the cooled arena-on median of 97.25 ms, embedding alone would take about 8.1 minutes, so the current baseline needs roughly 1.6× acceleration before overhead. Define “typical” with a fixed length distribution and truncation fraction; report long-note results separately. Success requires sustained full-store measurement, not extrapolation.
- **200 ms:** define as warm batch-1 p95 for a new/edited typical note, including tokenization and vector persistence; publish cold model-open time separately. Also measure query encoding and total search p95 at 5,000 notes because semantic queries share the embedder. Do not claim all 512-token inputs meet this target from mixed-note throughput; permit the owner to set a separate long-note/background budget.
- **2 nDCG points:** use the absolute 0.02 rule, per-set guardrails and uncertainty above. This is a noninferiority budget, not evidence that bge itself is good enough for notes; human notes evaluation and lexical comparison still matter.
- **500 MB peak PSS:** define as absolute whole-app process memory over open/index/close, alongside incremental embedder cost and idle retention. The October 24 arena-on peaks of 456–466 MB leave little app headroom. Treat this as a Pixel-class target and set an explicit lower-memory-device budget when that device is available. Retain arena-off/batch-1 as measured options; closing after bulk work does not solve a peak-memory problem.
- **50 MB model file:** keep it as a soft delivery goal subordinate to retrieval and safe memory. Specify decimal bytes, distinguish model download/installed bytes from per-ABI runtime and final packaged app delta, and report all of them. Quantization can reduce weights while activations/outputs remain float, so file size does not predict PSS. Include first-use download/open and reindex/migration cost when a changed digest or token limit invalidates stored vectors.

### 6. Independent check of the October 24 defaults

**Sorting is strongly supported.** On `mixed`, 91,256 padded tokens / 24,836 real tokens = 3.67× without sorting at batch 16. Sorting reduces that to 28,640 (1.15×) and cuts per-note time 219.6→51.3 ms on the emulator (4.28×) and 292.9→127.8 ms on the Pixel (2.29×). Sorted batch 4 has 25,664 padded tokens (1.03×). `BertEmbedder` sorts by encoded length, pads each sub-batch to its longest input and restores input order; common tests and real-model parity exercise that behavior. This is convincing padding evidence on the synthetic distribution, subject to the store-window caveat above.

**Four is a sensible compromise, not a proven unique optimum.** It is the fastest sampled arena-on mixed setting on both Android targets, with much lower peak PSS than eight or sixteen. The Pixel's 92.8 versus batch-1 97.2 ms is only a 4.5% advantage in single, non-counterbalanced samples; it is insufficient to establish a repeatable speed difference from one. The Mac's 44.5 ms at eight versus 46.7 at four likewise has no measured uncertainty. The `m3` claim “batch size does not matter (182–189 ms)” combines arena-on batch 8 with arena-off batches 8/4/1 and omits batch-16 206.4 ms; there is no matched Pixel `m3` arena-on batch-4 row. It cannot establish batch-size equivalence. Keep four provisionally for its throughput/memory balance, rechecking 1/4/8 on the selected graph and actual store windows.

**Arena on is supported as a speed-first default for this Pixel workload.** In the cooled four pairs, on has median **97.25 ms/note**, off **163.8**; the ratio of medians is **1.68×**, and the median paired ratio is **1.74×** (individual ratios 1.63–1.93). Peaks are 456–466 MB on versus 310–323 MB off; after-call PSS is 424–466 versus 179–221 MB. The consistent, large gap makes retaining the owner's default reasonable, with the documented memory opt-out. However, every pair is on→off and later runs remain slower, so “settles it” overstates causal precision: no raw thermal traces, reverse-order pairs or uncertainty intervals are available. These data do not establish a universal 70% penalty across phones, long notes, thread settings, quantized graphs or sustained indexing.

`OnnxOptions` really defaults to `maxBatchSize=4`, `sortByLength=true`, `cpuMemoryArena=true`, `memoryPattern=true`, and default intra-op threads; `OnnxEmbedder` passes those flags to ORT. The tuning test uses those defaults when omitted, records actual/padded tokens and samples Android `Debug.getPss()`, with after-call PSS taken **before** `use` closes the session. Thus it measures retained memory while open, but not post-close recovery or whole-index/store cost. Keeping patterns enabled is reasonable; the one-off pattern-off comparisons do not isolate a repeatable improvement. Arena shrinkage is an optional, separately timed experiment if retaining memory remains a problem, not evidence for changing today's policy.

Finally, Milestone 5's narrative mixes historical arena-off and restored arena-on measurements under “new defaults”: the 229 MB/227 ms and CI 213 MB/568 ms results are from the arena-off stage; the later arena-on results are about 445–452 MB retained. Its closing “about 600 ms” long-note estimate also describes an arena-off/warm-phone case, not a matched arena-on batch-4 measurement. Preserve those historical rows but label the configurations before reusing them in a recommendation. The plan's opening 455–600 ms range is therefore not a measured range for today's single default configuration. No roadmap/code edits were made as part of this review.

## Amended protocol after Codex's review (November 8, cloud session)

Every actionable point of the review is adopted. The tooling is on `phase-5-mobile-indexing` at `586cbde`, which also merges `main` (`173a9a9`, with the iOS embedder). Nothing below is a measurement result: the runs start after this section is pushed, so the rule is fixed before any score is seen.

### What changed in the tooling

- **[P2] Strict settings.** `OnnxEmbedderTuningTest` and the new `IndexingMeasurementTest` reject an unknown or repeated key, a flag other than `on`/`off` and a count that is not positive. Each line repeats the resolved settings and the model's digest. Token and padding counts use the selected model and input limit. The memory sampler stops in `finally`.
  - The tuning test is now shared code (on iOS too) and gains `warmup=<n>`: representative work outside timing, default 8 notes.
  - `latency=on` embeds notes one at a time and reports p50 and p95 per call, overall and by token length.
  - Memory is also read after close and after 5 s idle, and the peak is labelled `sampled-100ms` (a lower bound).
  - XNNPACK goes through a test-support hook; iOS accepts only `cpu`.
- **[P1] Store-level timing.** `IndexingMeasurementTest` (`KVID_INDEX_MEASURE`) inserts N notes (timed separately), then runs `Kvid.indexVectors` with the store's own outer batch of 32, in timed chunks, until pending is 0. It reports open, close and total time, early against late throughput, file size and memory. The 5,000-note runs on the phone come from this test, not from multiplying a microbenchmark.
- **Variants (answers 1 and 2).**

  | Variant | Model name in the spec | Recipe | Size (MB) | Batch |
  |---|---|---|---|---|
  | `bge-fp32` | `BAAI/bge-small-en-v1.5` | as published | 133.1 | 4 (default) |
  | `bge-fp32-opt` | `…#kvid-opt` | ORT basic optimizations, then the transformer optimizer's fusions (Attention, SkipLayerNormalization, BiasGelu, EmbedLayerNormalization), fp32, no GELU approximation | 132.9 | 4 |
  | `bge-int8` | `…#kvid-int8-matmul` | Codex's conservative recipe: `quantize_dynamic`, MatMul only, constant weights only, per-channel QInt8, no `reduce_range` | 69.8 | 1 |
  | `bge-int8-all` | `…#kvid-int8-all` | `quantize_dynamic`'s defaults (embedding table included, per tensor): the earlier recipe, renamed | 33.8 | 1 |
  | `minilm-fp32` | `sentence-transformers/all-MiniLM-L6-v2` | as published | 90.4 | 4 |
  | `minilm-fp32-opt` | `…#kvid-opt` | as `bge-fp32-opt` | 90.3 | 4 |
  | `minilm-int8` | as published | upstream `model_qint8_arm64.onnx` (recipe undocumented) | 23.0 | 1 |

  - Derived names say kvid made the file.
  - Every derived digest is pinned (`model-pins-<variant>.json`), and a rebuild in a fresh cache reproduced all four. `bge-int8-all` also reproduced its October digest under Python 3.13.
  - Each variant's `source.json` records operator counts, initializer bytes by type and opsets. As the review predicted, the conservative `bge-int8` keeps the 46.9 MB embedding table in float (48.1 MB float, 21.3 MB int8), so it cannot meet the 50 MB goal; only `bge-int8-all` or MiniLM can.
  - The int8 variants embed one text at a time: dynamic activation scales span the batch, so their vectors depend on the batch (finding of October 25).
  - Drift from the same model's fp32 output is recorded as percentiles and worst cases, as a diagnostic only.
  - **Parity, JVM (Linux x86_64): all seven variants match Python ONNX Runtime on their own file** (194 cases, cosine ≥ 0.9999, batched against single ≥ 0.99999 where batching applies).
  - Deferred as the review advises: fp16/O4, static int8 (contingency if dynamic int8 misses device targets), and `e5-small-v2`/`gte-small` (second round).
- **Evaluation (answer 3).**
  - `RetrievalEvalTest` writes `summary.jsonl` and `queries.jsonl` (per-query scores, top 100 and the token length of the longest relevant document).
  - `scripts/analyze_retrieval_eval.py` recomputes every metric with its own code and with `trec_eval` (`pytrec_eval`), and fails on a difference above 1e-9 (2e-16 on the smoke run). It then applies the rule below and reports scores by document length and top-10 overlap with the fp32 reference.
  - FiQA is screened on the frozen 20,000-document subset, labelled "FiQA subset"; finalists are repeated on the full 57,638 documents.

### Frozen choices and decision rule (preregistered)

- **Frozen choices.**
  - **Store and retrieval settings:** documents are stored as title, blank line, body, in corpus order. Lexical search uses plain syntax with any word. Fusion uses RRF with k = 60 and 100 candidates; nothing is tuned on these test sets.
  - **Queries:** a query without a relevant document is dropped at preparation. The baseline is `bge-fp32@512` with raw queries. The BGE instruction is a separate policy that changes only the dense query; BM25 always gets the raw query.
- **Primary metric:** hybrid nDCG@10. **Guardrails:** semantic nDCG@10, hybrid recall@100, and hybrid against lexical per set.
- **Delta:** per dataset, the mean over its queries of (candidate − baseline), paired by query. The macro delta weighs SciFact, NFCorpus and FiQA equally. 95% intervals come from 10,000 bootstraps that resample queries within each dataset (seed 20261108).
- **Verdict:**
  - **PASS** when the macro lower bound is at least −0.02 (absolute, on the 0–1 scale);
  - **FAIL** when the upper bound is below −0.02;
  - **INCONCLUSIVE** otherwise, which is not a pass.
  - A set whose point loss exceeds 0.02 is flagged for the owner. There is no speed waiver: a faster model that fails is reported as a failed trade-off.
- **Guardrail on lexical:** hybrid ≥ lexical per set is a point comparison, reported with its paired interval. An advantage whose interval includes zero is not evidence of improvement.

### Phase A runs (fixed list)

- **Datasets:** SciFact, NFCorpus and the FiQA subset.
- **Runs:**
  - `bge-fp32@512`, `@256`, `@128`;
  - `bge-fp32-opt@512`, `bge-int8@512`, `bge-int8-all@512`;
  - `minilm-fp32@256`, `@128`;
  - `minilm-fp32-opt@256`, `minilm-int8@256`.
- **Instruction:** each bge run is scored with the instruction off and on.
- **Then:** the frontier (baseline, best passing variant of each size class and each uncertain one) is repeated on full FiQA. JVM times are recorded as diagnostics; device times decide.

### Phase B commands (supersede the October 25 block)

Run from `phase-5-mobile-indexing` at `586cbde` or later. The protocol is answer 4 of the review:
- the Pixel 8 decides, the emulator is a harness check, and a mid-range phone is added if available;
- start cooled, and record temperature and thermal status at the start and end of every run;
- balanced AB/BA order across at least six paired blocks in two cooled sessions for the finalists, and three runs for screening;
- the same APK, and a fresh process per configuration;
- keep every log, including excluded runs.

```sh
python -m venv build/study/venv && build/study/venv/bin/pip install -r kvid-embed-onnx/scripts/requirements-study.txt
build/study/venv/bin/python -I kvid-embed-onnx/scripts/build_variants.py build/study/variants
adb shell rm -rf /data/local/tmp/kvid-model-variants && adb push build/study/variants /data/local/tmp/kvid-model-variants
ARGS=-Pandroid.testInstrumentationRunnerArguments
V=/data/local/tmp/kvid-model-variants
run() { ./gradlew :kvid-embed-onnx:connectedAndroidDeviceTest -Pkvid.deviceTestAbi=arm64-v8a "$@"; }
# 1. Parity of every variant on the device (ARM kernels against the x86 reference), before any timing.
#    An int8 variant that fails here is not portable and is reported as such, not timed.
for v in bge-fp32 bge-fp32-opt bge-int8 bge-int8-all minilm-fp32 minilm-fp32-opt minilm-int8; do
  run $ARGS.class=com.kvid.embed.OnnxEmbedderParityTest $ARGS.KVID_EMBEDDING_FIXTURE_DIR=$V/$v $ARGS.KVID_REQUIRE_EMBEDDING_FIXTURE=true
  adb logcat -d -s System.out | grep -F "[kvid-embedding]"; adb logcat -c
done
# 2. Screening, one configuration per run; then threads 1/2/4 and ep=xnnpack (its pool is ep_threads) on the best two.
run $ARGS.class=com.kvid.embed.OnnxEmbedderTuningTest $ARGS.KVID_MODEL_VARIANTS_DIR=$V \
  $ARGS.KVID_EMBEDDING_TUNE=corpus=mixed+notes=200+model=bge-int8
# 3. Edit latency (p50/p95 by length), at least 100 calls per configuration:
run $ARGS.class=com.kvid.embed.OnnxEmbedderTuningTest $ARGS.KVID_MODEL_VARIANTS_DIR=$V \
  $ARGS.KVID_EMBEDDING_TUNE=corpus=mixed+notes=200+model=bge-int8+latency=on
# 4. Whole-store indexing, baseline and finalists: 5,000 notes, store batch 32, until pending is 0.
run $ARGS.class=com.kvid.embed.IndexingMeasurementTest $ARGS.KVID_MODEL_VARIANTS_DIR=$V \
  $ARGS.KVID_INDEX_MEASURE=corpus=mixed+notes=5000+model=bge-int8
adb logcat -d -s System.out | grep -E "\[kvid-(embedding-tune|index-measure)\]"
```

Record every raw line in `docs/measurements/` on this branch, with the commit, device, OS build and the thermal readings.

## Phase A results (November 9, cloud session)

The preregistered run list was run as fixed above, at `phase-5-mobile-indexing` `586cbde`, then the frontier on full FiQA. Raw records, reports and how to reproduce them: [docs/measurements/2026-11-09-phase-a](../measurements/2026-11-09-phase-a/README.md).
- **Metric check.** Every metric was recomputed independently and with `trec_eval`; the largest differences were 4e-16 and 2e-16.
- **Pipeline check.** The semantic-only scores fall where the models' published retrieval scores put them:
  - MiniLM: SciFact 0.645, NFCorpus 0.316, full FiQA 0.369;
  - bge-small: 0.720, 0.337 and 0.385.
  - FiQA subset scores are higher than full-corpus ones, as expected with fewer distractors. They are labelled and not compared with published numbers.

### Decision analysis: SciFact, NFCorpus and full FiQA, raw queries, against `bge-fp32@512`

| Variant | Hybrid nDCG@10 delta (95% CI) | Verdict | Semantic nDCG@10 delta | Hybrid recall@100 delta | Flagged sets | Top-10 overlap with fp32 | Size (MB) | JVM time per document |
|---|---|---|---|---|---|---|---|---|
| `bge-fp32@512` (baseline) | | | | | | | 133.1 | 1.00 |
| `bge-fp32@256` | −0.002 (−0.006, +0.002) | PASS | −0.007 | +0.000 | – | 0.81–0.91 | 133.1 | 0.66 |
| `bge-int8@512` (MatMul, per-channel) | −0.003 (−0.006, −0.001) | PASS | −0.000 | +0.002 | – | 0.89–0.91 | 69.8 | 0.56 |
| `bge-int8-all@512` | −0.003 (−0.007, +0.002) | PASS | −0.017 | −0.009 | – | 0.73–0.79 | 33.8 | 0.55 |
| `minilm-fp32@256` | −0.012 (−0.021, −0.004) | INCONCLUSIVE | −0.037 | +0.008 | SciFact | – | 90.4 | 0.36 |
| `minilm-int8@256` | −0.012 (−0.020, −0.004) | INCONCLUSIVE | −0.040 | +0.009 | SciFact | 0.87–0.88 vs MiniLM fp32 | 23.0 | 0.26 |

The top-10 overlap column comes from the screening sets, with FiQA as the subset. Also from the screening sets only:
- **Fused graphs:** `bge-fp32-opt` and `minilm-fp32-opt` return identical rankings (overlap 1.000). On this x86 JVM their speed matches the original graphs, about 0.94× and 0.96× of their time, because the session's `ALL_OPT` already fuses at creation; ARM devices may differ.
- **128 tokens:** inconclusive for both models. `bge-fp32@128` gives −0.014 (−0.022, −0.007), with FiQA flagged; `minilm-fp32@128` gives −0.017 (−0.026, −0.008).
- **BGE query instruction:** gives hybrid search nothing (−0.002, −0.006 to +0.002) and semantic search +0.006. **The raw-query policy stays, and no `embedQuery` hook is needed now.**

### Reading

- **bge-small conservative int8.** `bge-int8` (Codex's conservative recipe) is the only variant that keeps semantic retrieval as well as hybrid. Its rankings overlap fp32's top 10 at about 0.9, and it ran in 0.56 of fp32's time on this JVM. Its costs:
  - 70 MB, above the 50 MB soft goal;
  - batch size 1, since dynamic scales span the batch;
  - int8 kernels on ARM still unverified against the x86 reference.
- **bge-small all-operators int8.** `bge-int8-all` passes the primary rule at half the size (34 MB). Its guardrails are weaker: semantic −0.017, recall@100 −0.009, and drift down to cosine 0.91 on the fixture texts. Hybrid search hides a real loss in dense retrieval, as Codex's guardrails were meant to show.
- **256 tokens on bge.** This passes with no new artifact and takes 0.66 of the time. The loss is concentrated where relevant documents are longer than 256 tokens, which matters little for typical notes (median 123 tokens in FiQA, about 320 in SciFact and NFCorpus). It can combine with int8; that combination was not in the preregistered list and would be an exploratory run, labelled as such.
- **MiniLM.** MiniLM is 2.8 to 3.8 times as fast on the JVM and 23 MB as int8, but inconclusive. It narrowly fails the margin, with lower bounds of −0.021 and −0.020. It loses 0.03 on SciFact, and on SciFact its hybrid advantage over lexical has an interval that touches zero (int8: −0.000 to +0.048). On full FiQA alone it is slightly better than bge. Under the rule this is not a pass, and the default cannot move to MiniLM on this evidence.
- **The subset effect.** On the FiQA subset MiniLM passed; on full FiQA it is inconclusive. This is why Codex asked for full FiQA on finalists.

### For Phase B (devices) and the owner

- **Frontier for device timing:** `bge-fp32@512` (baseline), `bge-fp32-opt@512` (may help on ARM), `bge-int8@512`, `bge-int8-all@512`, `bge-fp32@256`, and `minilm-int8@256`, the uncertain fast option, kept as Codex advises.
- **Device parity first:** every int8 variant runs its parity on the Pixel 8 before any timing. A variant whose ARM vectors miss cosine 0.9999 against the x86 reference is not portable under ADR 0002 and is reported, not timed.
- **Owner decisions after Phase B:**
  - whether the 50 MB file goal outranks the guardrail losses of `bge-int8-all`;
  - whether to accept batch size 1 for int8 or try static quantization;
  - whether 256 tokens becomes the default limit.
- **Notes-style evaluation set:** still required before a new default ships, since these benchmarks are proxies.

## Codex's review of Phase A (November 9)

Codex (`gpt-6.1-sol`, high effort, read-only) reviewed "Phase A results" and `docs/measurements/2026-11-09-phase-a/` against the amended protocol, recomputing from the committed summaries. **No P1 findings:** the macro arithmetic, the PASS and INCONCLUSIVE verdicts, the flags and the lexical point guardrail follow the rule. Its macro deltas from the rounded summaries match the table within 0.00004.

- **[P2] Uncertain candidates were dropped.** The frozen rule repeats "each uncertain one" on full FiQA and keeps uncertain candidates for devices.
  - `bge-fp32@128` (−0.022 to −0.007) and `minilm-fp32@128` (−0.026 to −0.008) stopped at screening.
  - `minilm-fp32@256`, inconclusive on full FiQA, is not in the device frontier.
  - MiniLM fp32@128 took 8 to 9 ms per document on the JVM, against 9 to 14 for the retained `minilm-int8@256`.
  - Codex's recommendation: add those three to full FiQA and the device frontier, or amend the retention rule explicitly. A short ARM comparison of `minilm-fp32-opt@256` is justified as for `bge-fp32-opt`.
- **[P2] The per-query records are not archived.** Without `queries.jsonl`, the paired intervals, overlaps, length buckets and the `trec_eval` cross-check cannot be audited. This matters most where `minilm-int8`'s lower bound is −0.02025, 0.00025 below the threshold. Archive the original records, compressed or externally with checksums; a rerun would be new evidence, not an audit.
- **[P2] Per-set intervals against the baseline are missing.** `analyze_retrieval_eval.py` (lines 190 to 195 at `586cbde`) keeps only per-set point deltas. Its per-set intervals compare hybrid with lexical, which is a different question. Add candidate-against-baseline intervals per set, without changing the macro rule. MiniLM's flagged SciFact losses, −0.029 and −0.031, have no interval.
- **[P3] Drop the inference about typical notes.** 256-token truncation affects 19% of full-FiQA documents, 71% of SciFact and 79% of NFCorpus. On full FiQA, the bucket whose relevant documents exceed 512 tokens falls from 0.382 to 0.362 (108 queries). State the measured trade-off and leave notes to the notes evaluation.
- **Answers to the three questions.**
  1. **Does the analysis follow the rule?** Yes for the decision itself, but compliance is incomplete because of the first and third findings.
  2. **Is the device frontier right?** Keep the six, and add `bge-fp32@128`, `minilm-fp32@128` and `minilm-fp32@256`.
  3. **Is an exploratory `bge-int8@256` quality run worth adding?** Yes, on all three datasets including full FiQA, labelled exploratory: neither the losses nor the speedups can be assumed to combine. It stays at 69.8 MB and batch 1.
- **Local agent's note.** Phase B on November 9 times the six variants the owner listed. The three Codex adds are an owner decision; device parity covers all seven variants either way.

## Phase B results (November 9 to 11, local agent)

Run at `phase-5-mobile-indexing` `586cbde` on the Pixel 8 with one arm64 test APK. Raw lines, thermal readings, the driver scripts and the full tables: [docs/measurements/2026-11-09-phase-b](../measurements/2026-11-09-phase-b/README.md). **The Pixel 8 was attached only at times:** about 50 and 60 minutes on November 9 to 10 (parity, screening), then November 10 to 11 (5,000-note runs, edit latency, sweep, finalist blocks). In between, the same APK ran the whole protocol on a Pixel 4 XL the owner offered for the night; its results are reported separately and never pooled.

### Device parity: no int8 variant is portable

All seven variants ran `OnnxEmbedderParityTest` against the x86 fixtures (194 texts, single and batched) before any timing.

| Variant | Minimum cosine against the x86 reference | Verdict |
|---|---|---|
| `bge-fp32`, `bge-fp32-opt`, `minilm-fp32`, `minilm-fp32-opt` | 1.00000000 | pass |
| `bge-int8` | 0.91568454 | **fail** |
| `bge-int8-all` | 0.99379598 | **fail** |
| `minilm-int8` | 0.95446194 | **fail** |

- **Not timed, by the rule above:** `bge-int8@512`, `bge-int8-all@512` and `minilm-int8@256`.
- **The Pixel 4 XL gives the same seven results to the last printed digit** (Snapdragon 855, Android 13). Two ARM phones and macOS arm64 agree with each other and miss the x86 reference by the same amount.
- **The cause is the CPU architecture, not the phone.** Against fixtures written by the same Python script on macOS arm64, from model files with the same digests, the phone's int8 vectors match to 6e-8.
- **The x86 reference used here overstates the miss for two variants.** The fixtures were built in a `linux/amd64` container under Rosetta (AVX2, no VNNI). There `bge-int8` drifts from fp32 to a minimum cosine of 0.9189; Phase A's machine recorded 0.9871 and arm64 gives 0.9873. `minilm-int8` shows the same pattern (0.9561, 0.9607, 0.9640). `bge-int8-all` drifts equally everywhere (0.912) and still differs between architectures (0.9938).
- **arm64 against Phase A's machine is not measured.** The drift statistics are close (mean 0.99635 and 0.99636 for `bge-int8`) but individual texts differ in the third decimal, and Phase A's fixture vectors were not archived.
- **Consequences.**
  - Vectors of a dynamically quantized model are not interchangeable between the tested x86 reference (AVX2 under Rosetta) and ARM. Whether they also differ between x86 CPUs with and without VNNI is a hypothesis from the drift figures above; no vectors from Phase A's machine were archived to compare. A store indexed with int8 on one architecture and queried on another mixes two vector spaces.
  - Phase A's int8 quality results were measured on one x86 machine. They are not established for ARM. The drift figures suggest a similar result for `bge-int8`; that needs the evaluation repeated on arm64 to be more than a guess.
  - Unverified: a native macOS arm64 build was seen not to reproduce the pinned digest of an `-opt` variant, which is why the files were built in the x86 container. Its digests were not archived, and `minilm-fp32-opt` was not built there.

### Screening on the Pixel 8 (three configurations left)

Each run: fresh process, 200 notes, defaults (batch 4, sorted, arena on, CPU). **Cooling rule**, fixed beforehand from an idle baseline: thermal status 0 and skin temperature at or below 35.5 °C at the start. Every run met it. The phone was on external power at 80%, not charging, screen on.

| Configuration | `mixed`: per note, median of 3 (range) | Ratio to baseline per block | `mixed` peak PSS (KiB) | `m3`: per note, blocks 1 to 3 | `m3` peak PSS (KiB) |
|---|---|---|---|---|---|
| `bge-fp32@512` | 72.4 ms (67.1 to 73.7) | | 437,055 to 453,940 | 376.2, 369.1, 458.0 ms (throttled) | 529,425 to 543,114 |
| `bge-fp32-opt@512` | 69.8 ms (69.5 to 81.1) | 1.04, 0.96, 1.10 | 391,283 to 411,828 | 372.1, excluded, 334.5 ms | 429,130 and 445,682 |
| `bge-fp32@256` | 52.8 ms (52.5 to 53.9) | 0.79, 0.73, 0.73 | 311,481 to 321,596 | 160.6, 187.7, 190.0 ms | 398,759 to 505,880 |

- **One `m3` run is excluded and one is throttled.** The owner was using the phone during `bge-fp32-opt@512` in block 2 (420.3 ms; the first version of this table counted it). The baseline in block 3 reported thermal status 1 for most of the run although it started inside the cooling rule. Clean `m3` pairs: two for 256 tokens (0.43, 0.51). For the optimized graph none is free of throttling or interruption: its block 1 run (ratio 0.99) reported thermal status 1 for its last 30 s. From 01:15 UTC the driver detects phone use itself.
- **`bge-fp32-opt` is not shown to be faster on ARM** within what three blocks can show; its peak is about 40,000 KiB lower on `mixed` and 85,000 to 115,000 KiB lower on `m3`.
- **256 tokens is faster in every block** and exceeds the predeclared 10% difference: about a quarter less time on `mixed`, half on `m3`.
- **The baseline exceeds the 500 MB limit on `m3`** in all three runs (517 to 530 MiB). Two of the three 256-token runs exceed it as well (505,880 and 497,164 KiB: 518 and 509 MB, 494 and 486 MiB; the limit is 488,281 KiB).
- **The 5-minute goal:** projected from 200 notes, the baseline needs 6.0 minutes per 5,000 `mixed` notes and 256 tokens 4.4. The 5,000-note store runs, which decide this, are below.
- **Heat:** `m3` runs end at 37.9 to 38.9 °C skin temperature and reach up to 40.1 °C during a run; the first throttling threshold is 39.0 °C. One run reported status 1 for 30 s and one for most of the run.

### Screening on the Pixel 4 XL (second phone; reported separately)

Same APK, queue and protocol, November 10, 03:50 to 07:21 UTC. **Cooling rule**, fixed beforehand from its own idle baseline: thermal status 0 and skin temperature at or below 30.5 °C. Every run met it, thermal status stayed 0 during every run, and no run was disturbed. The phone was on external power at 96% to 100%; the first two runs were made while it was still topping up.

| Configuration | `mixed`: per note, blocks 1 to 3 | Ratio to baseline per block | `mixed` peak PSS (KiB) | `m3`: per note, blocks 1 to 3 | Ratio to baseline per block | `m3` peak PSS (KiB) |
|---|---|---|---|---|---|---|
| `bge-fp32@512` | 138.5, 141.8, 139.7 ms | | 427,651 to 435,499 | 578.6, 575.4, 574.8 ms | | 477,484 to 484,323 |
| `bge-fp32-opt@512` | 129.4, 129.3, 130.6 ms | 0.93, 0.91, 0.93 | 387,691 to 389,387 | 526.8, 532.8, 525.9 ms | 0.91, 0.93, 0.91 | 394,574 to 395,212 |
| `bge-fp32@256` | 102.2, 101.9, 100.7 ms | 0.74, 0.72, 0.72 | 277,417 to 283,224 | 272.0, 272.7, 273.1 ms | 0.47, 0.47, 0.48 | 273,320 to 278,968 |

- **Same ranking as the Pixel 8, at about half the speed and with far less scatter** (at most 2.4% between runs of one configuration).
- **The optimized graph is 7% to 9% faster in all six blocks.** That is a consistent gain, but below the predeclared 10% difference. On the Pixel 8 it was not distinguishable from noise.
- **256 tokens** saves about a quarter of the time on `mixed` and half on `m3`, as on the Pixel 8.
- **Memory:** every peak is under 500 MB; the baseline on `m3` is closest (473 MiB).
- **No configuration reaches 60 ms per note on this phone.**

### Sweep, edit latency, finalists and 5,000 notes on the Pixel 4 XL

Second session, 07:21 to 11:32 UTC, same rule, 30 runs: 28 completed, two crashed (XNNPACK), none disturbed. The phone reported "not charging" throughout and fell from 97% to 89% on the cable.

| Configuration | Threads 1 / 2 / 4 (default 140, 129, 102) | XNNPACK | Edit latency p50, p95 | Blocks 4 to 6 | 5,000 notes | Peak PSS at 5,000 notes |
|---|---|---|---|---|---|---|
| `bge-fp32@512` | 248.9 / 124.5 / 138.6 ms | crash | 49.5, 578.7 ms | 138.2, 139.4, 144.6 ms | 14.9 min | 509 MiB |
| `bge-fp32-opt@512` | 237.4 / 116.3 / 127.4 ms | 228.6 ms | 48.8, 540.2 ms | 126.9, 127.4, 127.8 ms | 13.8 min | 418 MiB |
| `bge-fp32@256` | 179.0 / 91.3 / 101.8 ms | crash | 56.0, 254.8 ms | 102.0, 100.2, 102.5 ms | 10.1 min | 299 MiB |

- **First indexing: no configuration is near 5 minutes on this phone** (10 to 15 minutes). Every 5,000-note run reached thermal status 1 after about four minutes and stayed there; none reached status 2. The goal names a Pixel 8-class phone, and this one is four chip generations older, so this bounds the old-phone case and does not decide the goal.
- **Memory: the baseline exceeds 500 MB at 5,000 notes** (509 MiB). The optimized graph and the 256-token limit stay under it.
- **Edit latency: no configuration meets 200 ms at p95 over all notes**, for embedding alone (saving the vector is not timed). Notes of up to 128 tokens do (p95 110 to 121 ms). The 256-token limit halves the tail (255 ms) but still misses.
- **Optimized graph over six blocks:** faster in all six, by 7% to 12% (median 8%); it reaches the 10% difference in one block. Block-resampled 95% interval of the mean ratio: 0.92 (0.90 to 0.93), a real gain under the 10% threshold. At 5,000 notes it takes 0.93 of the baseline's time and 91 MiB less memory.
- **256-token limit over six blocks:** 0.71 to 0.74 of the baseline's time; 0.68 at 5,000 notes.
- **Threads:** four equals the default. **Two are faster in nine of nine pairs** (the sweep and two paired blocks in a third session, 11:34 to 12:37 UTC, 12 runs, all clean): 10% to 13% for the plain graph at both token limits, 6% to 9% for the optimized graph, peak memory within −1.5% to +3.2%. Three pairs per configuration. With two threads the optimized graph leads the plain one by only 2% to 3%. Not tried at 5,000 notes or for edit latency.
- **XNNPACK is not usable:** it crashes ONNX Runtime (`SIGSEGV`) on the plain graph at both token limits, three attempts out of three, and is 78% slower than the CPU provider on the optimized graph (one run).

### Sweep, edit latency, finalists and 5,000 notes on the Pixel 8 (November 10 to 11)

The phone was at 80% and not charging, launcher in front, at every start. **Two cooling rules.** The 5,000-note baseline and 256-token runs and the edit-latency runs started under the first rule (skin at or below 35.5 °C). The phone then idled warmer than on November 9, and the owner chose a second rule from that day's idle level: status 0 and skin at or below 36.4 °C (median of 16 idle readings, 35.85 °C, plus 0.5), fixed before any run used it. The other runs below started under it, at 35.2 to 36.4 °C. **This is a mid-study amendment, not a replication of the first rule's conditions:** its window and threshold were chosen after the cooling difficulty and some results were known, and its formula is not the first rule's (the top of the settled idle range plus 0.1). Small speed differences between runs under different rules are confounded with it; ratios within a block and the 5-minute misses are not.

| Configuration | 5,000 notes | Notes per second (early, late) | Peak PSS at 5,000 notes | Edit latency p50, p95 | Blocks 4 to 6 | Threads 1 / 2 / 4 | XNNPACK |
|---|---|---|---|---|---|---|---|
| `bge-fp32@512` | 14.0 min; repeat 12.7 min | 5.97 (8.33, 5.25); 6.54 (8.22, 5.90) | 508 MiB; 511 MiB | 32.4, 284.1 ms | 76.8, 74.8, 74.9 ms | excluded / 129.3 / 76.9 ms (default 74.1) | crash |
| `bge-fp32-opt@512` | 12.3 min | 6.78 (8.81, 6.19) | 426 MiB | 34.0, 292.5 ms | 73.6, 72.8, 79.1 ms | not run | not run |
| `bge-fp32@256` | 8.7 min | 9.60 (12.99, 8.52) | 323 MiB | 34.3, 148.4 ms | 52.8, 53.5, 53.8 ms | 167.8 / 88.9 / 53.6 ms (default 54.1) | crash |

- **First indexing: none of the three tested configurations meets 5 minutes on the Pixel 8.** The goal needs 16.7 notes per second. The best, 256 tokens, averages 9.6 and takes 8.7 minutes; the baseline takes 12.7 to 14.0 minutes.
- **The 200-note figures do not carry over, and throttling is part of the reason.** Every 5,000-note run reached thermal status 1 within one to two minutes and stayed there (never status 2); throughput fell 28% to 37% from the early to the late figure. Screening had suggested 74 ms per note for the baseline; the 5,000-note runs cost 153 to 168 ms. The store run also writes vectors, embeds in outer batches of 32 and has longer notes (130.3 against 124.2 tokens), so throttling is not isolated as the only cause.
- **One run per configuration cannot rank close candidates at 5,000 notes:** the two valid baseline runs differ by 9%.
- **Memory: the baseline exceeds 500 MB** in each of its 5,000-note runs (533 to 536 MB; 508 to 511 MiB). The optimized graph (447 MB) and the 256-token limit (338 MB) are under it on `mixed` in these sampled test-process runs; whole-app, full-lifecycle memory is not measured. On `m3` the optimized graph's replacement run peaked at 511 MB.
- **Edit latency: embedding alone is under 200 ms at p95 with the 256-token limit** (148.4 ms, one run of 200 calls). **The target is not yet validated:** it includes saving the vector, which the test does not time. At 512 tokens embedding alone already misses (284 and 293 ms) because of notes over 256 tokens.
- **No speed gain is demonstrated for the optimized graph on the Pixel 8:** ratio to the baseline 0.96 to 1.10 over six blocks; block-resampled 95% interval of the mean ratio 1.01 (0.98 to 1.06), which includes no gain and excludes a 10% gain. Its measured benefit there is memory on `mixed` (about 85 MiB at 5,000 notes). On the Pixel 4 XL it was 7% to 12% faster.
- **The 256-token limit** takes 0.69 to 0.79 of the baseline's time over six blocks (interval of the mean ratio 0.73, 0.71 to 0.75) and 0.62 to 0.68 at 5,000 notes.
- **Threads: nothing beats the default on the Pixel 8.** Four threads equal it; two were 64% to 74% slower, the opposite of the Pixel 4 XL. These are single runs, with the 512-token default from an earlier session, so they describe a large difference and do not prove the default optimal. The one-thread run at 512 tokens was excluded twice because the phone was in use (238.6 and 228.4 ms); at 256 tokens one thread is three times slower.
- **XNNPACK crashes here too** (`SIGSEGV`, both token limits): six crashes in six attempts with the plain graph across the two phones.
- The `m3` cell excluded in screening was rerun: 361.0 ms per note for the optimized graph. It is an unpaired observation under the second rule; the protocol's whole-block repeat was not done, so it does not repair that pair.

### The three retained candidates on the Pixel 4 XL (November 11)

Run after Codex's review, at the owner's request, with the plan written beforehand (`pixel4xl/session4-plan.txt` in the archive): the baseline in every block, six `mixed` blocks, one 5,000-note run each, edit latency, three `m3` blocks. Same cooling rule as the earlier Pixel 4 XL sessions; every start met it. **Quality is unsettled for all three** (Phase A: uncertain, uncertain, INCONCLUSIVE), so these numbers cannot authorize shipping one, and they are **not from the Pixel 8**.

| Configuration | `mixed`, median of six | Mean ratio to baseline (95% interval) | 5,000 notes | Notes per second (early, late) | Peak PSS at 5,000 notes | Edit latency p50, p95 (embedding only) |
|---|---|---|---|---|---|---|
| `bge-fp32@512` | 141.2 ms | | 14.7 min, throttled | 5.67 (5.93, 5.69) | 529 MB | 49.5, 578.7 ms (second session) |
| `bge-fp32@128` | 65.3 ms | 0.45 (0.43 to 0.47) | 6.2 min | 13.49 (14.06, 13.26) | 265 MB | 49.3, 113.9 ms |
| `minilm-fp32@256` | 53.2 ms | 0.37 (0.34 to 0.38) | 5.2 min | 16.16 (16.65, 16.09) | 268 MB | 26.7, 123.3 ms |
| `minilm-fp32@128` | 34.1 ms | 0.24 (0.22 to 0.24) | **3.3 min** | 25.50 (26.23, 24.86) | 218 MB | 30.2, 73.6 ms |

- **`minilm-fp32@128` is the first configuration under 5 minutes** for 5,000 notes on either phone (196 s), and on the older one. `minilm-fp32@256` misses by 9 s in its one run; `bge-fp32@128` takes 6.2 minutes.
- **None of the three throttled:** each finished before the phone reached thermal status 1. The baseline in the same session throttled as before.
- **Memory and embedding latency are far inside the limits** in these test-process runs (about half the memory limit or less; p95 74 to 123 ms).
- **What the 128-token limit costs in text:** 31% of the `mixed` notes are longer than 128 tokens, and only 53% of the tokens of the 5,000 notes are kept (79% at 256). Phase A measured its quality on public sets only.
- **`m3`:** the candidates take 0.22 to 0.25 of the baseline's time (`minilm-fp32@128` 0.12) in three blocks. One block was excluded for phone use and repeated whole; one run was made four hours after the rest of its block because the phone idled just above the cooling rule.
- **Open:** no thread sweep for the candidates; nothing on the Pixel 8.

### Not done against the protocol

- **By rule or by failure:** timing of the three int8 variants (parity failed); XNNPACK timing with the plain graph (it crashes; the optimized graph was timed on the Pixel 4 XL only).
- **Statistics:** a same-rule replication of the Pixel 8's finalist blocks (its two sessions used different cooling rules); the whole-block repeat after an interruption; finalist blocks on `m3` beyond screening; repeats of the 5,000-note runs other than the Pixel 8 baseline.
- **Sweep scope:** `mixed` only; on the Pixel 8 not for the optimized graph, and no valid one-thread figure at 512 tokens (excluded twice).
- **Targets as defined in the review's answer 5:** edit latency including saving the vector; query and search p95 at 5,000 notes; whole-app, full-lifecycle PSS; screen-off and background runs.
- **Recording:** fixed ambient conditions and brightness, energy cost, the sampler's overhead, process IDs.
- **Frontier:** the three uncertain candidates the frozen rule keeps are timed on the Pixel 4 XL only, not on the Pixel 8, which decides; the emulator harness check (the local emulator's disk is full).

### For the owner

- **The int8 question changes.** The plan asked whether to accept batch size 1 for int8 or try static quantization, and whether 50 MB outranks `bge-int8-all`'s losses. Parity now says dynamic int8 does not give portable vectors at all. Under the frozen rule int8 stays rejected. Options beyond it: keep fp32 everywhere; allow int8 with the model digest *and* the CPU kernel family as part of the vector space (no cross-device stores without re-embedding); or test whether a statically quantized (QDQ) model is reproducible across architectures. The second would not lower the 0.9999 threshold within a space; it would be an ADR and spec amendment on portability.
- **Whether to time int8 anyway** as information, outside the rule.
- **Whether to adopt 256 tokens.** It is the only measured candidate that moves the device numbers: on the Pixel 8 its embedding p95 is 148 ms, its sampled peak 338 MB, and first indexing takes 8.7 minutes against 13 to 14. Its quality trade-off is the one Phase A measured on public sets; the notes-style evaluation the review asked for does not exist yet. Until the owner decides, the 512-token default stays.
- **The 5-minute goal is not met by any of the three first configurations**, on either phone, with sustained thermal throttling contributing. Background indexing with progress is already required separately and does not make 8.7 minutes meet a 5-minute goal. Of the retained candidates, `minilm-fp32@128` meets it on the Pixel 4 XL (3.3 minutes) and `minilm-fp32@256` nearly does (5.2). The choice left to the owner: change the goal, or pursue one of these, which first needs its quality settled.
- **The retained candidates: quality next, and the Pixel 8.** `bge-fp32@128`, `minilm-fp32@128` and `minilm-fp32@256` are now timed on the Pixel 4 XL (owner's request, November 11). `minilm-fp32@128` is 4.3 times faster than the baseline there and meets the indexing goal; `minilm-fp32@256` is 2.7 times faster. Their quality verdicts are uncertain or INCONCLUSIVE, so device speed alone cannot authorize shipping one. To decide: whether to run the missing quality work (full FiQA for the 128-token configurations, the notes-style evaluation), and whether to time them on the Pixel 8.
- **Memory limit: unit and scope.** The write-up now treats 500 MB as decimal (488,281 KiB). The review's answer 5 defines the limit as whole-app memory over open, index and close; only sampled test-process peaks exist.
- **Thread count.** Two threads beat the default by 10% to 13% on the Pixel 4 XL and lost to it by 64% to 74% on the Pixel 8 (single runs there). Proposed: keep the default, as the conservative existing policy.
- **XNNPACK** crashes ONNX Runtime 1.30.0 with the plain graph on both phones (six attempts of six) and is slower with the optimized one on the Pixel 4 XL. Proposed: drop it.
- **The optimized graph** shows no speed gain on the Pixel 8 and 7% to 12% on the Pixel 4 XL, under the 10% threshold on both. Its sampled peak on `mixed` is about 85 MiB lower at 5,000 notes (447 MB against 533 to 536 MB); on `m3` it reached 511 MB. Under the decision rule speed is inconclusive, so the current default stays unless the owner weighs the memory result otherwise in Phase C.

## Codex's review of Phase B (November 11)

Codex (`gpt-6.1-sol`, high effort, read-only) reviewed "Phase B results" and `docs/measurements/2026-11-09-phase-b/` against the protocol, recomputing from the raw lines. The local session checked each finding against the raw files; all held, and the corrections are made above and in the archive.

**Verified correct:** the other table timings, latency summaries, PSS entries and conversions; the six `mixed` blocks use all six orders; every timed start met the rule it claims (Pixel 8: 26 starts at or below 35.5 °C and 22 at or below 36.4 °C; Pixel 4 XL: 60 at or below 30.5 °C, all status 0); 200 calls per latency configuration; the store runs use 5,000 notes and outer batch 32 with pending at 0; the int8 parity figures and the withholding of int8 timing; no device serial or third-party app name under `docs/`.

- **[P1] The edit-latency target is not validated.** `latency=on` times the embedding call without saving the vector, which the target includes, and there is one run per configuration. *Corrected:* reported as embedding-only; the persisted-edit measurement is listed as not done.
- **[P1] Memory verdicts needed decimal MB and narrower claims.** 500 MB is 488,281 KiB. Two 256-token `m3` peaks (518 and 509 MB) and the optimized graph's `m3` rerun (511 MB) are above it, not "at" it; the passes are sampled test-process peaks on `mixed`, not whole-app figures. *Corrected.*
- **[P2] Speed needed the prescribed intervals and weaker wording.** *Added:* block-resampled 95% intervals for the six-block ratios. Pixel 8: optimized graph 1.01 (0.98 to 1.06), "no demonstrated gain"; 256 tokens 0.73 (0.71 to 0.75). Pixel 4 XL: 0.92 (0.90 to 0.93) and 0.72 (0.72 to 0.73). The Pixel 8 thread results are single runs; keeping the default is the conservative policy, not proof.
- **[P2] The second cooling rule is a mid-study amendment**, chosen after results and cooling difficulties were known, and "same formula as the first rule" was wrong. *Corrected;* results by rule are kept apart.
- **[P2] No clean optimized-graph `m3` pair exists** on the Pixel 8 (block 1's run ended at status 1). *Corrected.*
- **[P2] The interrupted block was not repeated whole;** the `m3` rerun is unpaired. *Corrected and listed.*
- **[P2] The owner list:** the three uncertain candidates were kept by the frozen rule, so the choice is to complete the frontier or amend its scope; the 256-token decision and the missing notes-style evaluation were not stated. *Corrected.*
- **[P2] "No configuration" and "throttling is the reason" overreached.** *Corrected* to the three tested configurations and to throttling as a contributing factor.
- **[P2] The VNNI statement was a hypothesis, and the parity-policy note was misframed.** *Corrected.*
- **[P2] The native-build digest claim for both `-opt` variants has no archived evidence.** *Marked unverified.*
- **[P2] The list of unfinished work was too short.** *Expanded* ("Not done against the protocol").
- **[P3] Numbers and stale lines:** the Pixel 4 XL sweep gain is 9% to 11%; peak change with two threads is −1.5% to +3.2%; XNNPACK is six crashes in six attempts; 497,164 KiB is 486 MiB; the second rule was written at 23:45 and in force from 23:55; `m3` is long notes, not every note at the limit; `m3` end temperatures are 37.9 to 38.9 °C; latency buckets are exclusive ranges; stale "not yet run" lines removed. Ratios in the tables use the printed per-note figures, so a few differ in the last digit from ratios of `total_ms`.

**Still open after the review:** everything under "Not done against the protocol", and the owner's decisions above.

## Quality of the retained candidates (November 12, cloud session)

This is the frozen rule's full-FiQA step for the two candidates that stopped at screening (Codex's first P2 on Phase A), followed by the decision analysis for all three retained candidates.
- **Comparison:** each candidate against `bge-fp32@512` on SciFact, NFCorpus and full FiQA, with raw queries.
- **The rule is unchanged.** Per-set intervals against the baseline are now reported beside it (Codex's third P2).
- **Phase A's per-query records are now archived** (its second P2), and this analysis reproduces from them.
- Records, report and how to reproduce: [docs/measurements/2026-11-12-retained-quality](../measurements/2026-11-12-retained-quality/README.md).

| Candidate | Hybrid nDCG@10 delta (95% CI) | Verdict | Semantic nDCG@10 delta (95% CI) | Hybrid recall@100 delta (95% CI) | JVM time per document, full FiQA |
|---|---|---|---|---|---|
| `bge-fp32@128` | −0.0141 (−0.0214, −0.0067) | INCONCLUSIVE | −0.035 (−0.045, −0.025) | −0.006 (−0.012, −0.000) | 0.53 |
| `minilm-fp32@128` | −0.0157 (−0.0247, −0.0066) | INCONCLUSIVE | −0.046 (−0.059, −0.032) | +0.002 (−0.008, +0.011) | 0.28 |
| `minilm-fp32@256` | −0.0122 (−0.0204, −0.0040) | INCONCLUSIVE | −0.037 (−0.050, −0.025) | +0.008 (−0.002, +0.017) | 0.41 |

Per-set hybrid nDCG@10 deltas against the baseline (95% CI). These are reported beside the rule; a point loss over 0.02 is flagged (**bold**).

| Candidate | SciFact | NFCorpus | full FiQA |
|---|---|---|---|
| `bge-fp32@128` | −0.008 (−0.026, +0.009) | −0.010 (−0.017, −0.002) | **−0.024 (−0.035, −0.014)** |
| `minilm-fp32@128` | **−0.035 (−0.057, −0.015)** | −0.013 (−0.023, −0.003) | +0.001 (−0.013, +0.015) |
| `minilm-fp32@256` | **−0.029 (−0.048, −0.011)** | −0.010 (−0.021, +0.000) | +0.003 (−0.009, +0.015) |

### Reading

- **No candidate passes and none fails.**
  - Each macro interval straddles −0.02: lower bounds −0.0214, −0.0247 and −0.0204; upper bounds −0.007 to −0.004.
  - INCONCLUSIVE is not a pass, and there is no speed waiver. So none of the three can replace the default on this evidence, whatever their device times.
  - `minilm-fp32@256`'s figures are Phase A's, recomputed: its interval moves in the fourth decimal because the bootstrap stream now serves two earlier candidates (README).
- **The two 128-token candidates lose in different places.**
  - **`bge-fp32@128` loses where documents are long.** The loss is on full FiQA, where 46.8% of documents exceed 128 tokens.
    - Queries whose longest relevant document has 257 to 512 tokens fall from 0.372 to 0.328 (253 queries). Those over 512 fall from 0.382 to 0.324 (108 queries).
    - Queries with relevant documents of up to 256 tokens are unchanged (0.305 and 0.315 against 0.312 and 0.319).
    - _Changed after Codex's review:_ this is consistent with an input-limit effect concentrated in long documents (Codex reproduces −0.044 and −0.058 in those two groups). It does not show that the answers sit after token 128: document length is not answer position, and truncation also changes the competing documents.
  - **MiniLM loses on SciFact at both limits.** The loss is −0.035 at 128 tokens and −0.029 at 256. On full FiQA it is level with the baseline at both limits.
    - _Changed after Codex's review_ (it said "a property of the model rather than of the limit", which is too strong). The model explains a substantial part: at the same 256-token limit, MiniLM minus bge on SciFact is −0.029, with a diagnostic paired interval of −0.047 to −0.011.
    - The evidence does not separate the model from the limit. MiniLM at 128 minus MiniLM at 256 is −0.006 (−0.022, +0.009), which does not exclude a further truncation loss.
    - Both figures were recomputed from the archived per-query records and match Codex's. They are diagnostics, not part of the rule.
  - SciFact is the proxy least like notes. Neither pattern says how either candidate does on notes.
- **The guardrails show a real loss in dense retrieval.**
  - **Semantic nDCG@10.** All three lose more than the margin on semantic nDCG@10, with upper bounds below −0.02 (−0.025, −0.032, −0.025). Hybrid search hides most of it because BM25 carries part of the ranking.
  - **Recall@100** stays within ±0.01.
  - **Hybrid against lexical.** Hybrid beats lexical in every set, except `minilm-fp32@128` on SciFact: +0.019 (−0.007, +0.046), an interval that includes zero and so is no evidence of improvement. Phase A found the same for `minilm-int8`.
- **What would settle them.** These proxies cannot. Their documents are longer than notes, and the 128-token question depends on where in a note the answer sits. The next evidence is the notes-style evaluation set below. Until it exists, the 512-token `bge-fp32` default stays.

## Proposal: a notes-style evaluation set (November 11, cloud session)

The proposal is [notes-evaluation.md](notes-evaluation.md), for the owner and for review by Codex before anything is built. Nothing is built yet. _Revised November 12 after Codex's review (findings 3 to 6); this summary is changed to match._
- **Real note lengths first.** A local survey script prints aggregate token statistics of a person's notes and nothing else. The owner runs it on their notes first. Lengths do not say where the answer sits in a note; only Part R measures that.
- **A public synthetic set (Part S).**
  - At least six collections of 500 to 800 notes, written to a frozen specification.
  - 800 test queries and 200 development queries, written from memory cues rather than from the note text, with an audit of how much each query's words overlap its target note.
  - Each query belongs to one fixed stratum, set by the smallest input limit at which its target's whole evidence survives encoding, special tokens counted (within 128, 129 to 256, 257 to 512, or beyond 512). Each stratum needs minimum support: 10%, 10%, 10% and 5% of the test queries. A stratum the decision weights but that lacks support is a hold. _Changed after Codex's second review._
  - The no-answer queries are deferred.
- **A small private real set (Part R), now required for a default change.**
  - At least 150 naturally elicited queries per contributor, each with its evidence span marked.
  - Everything is evaluated locally, with no remote service or LLM. Only an explicit allowlist of aggregates is exported, including the evidence-position histogram that reweighting needs.
  - Without Part R, the best possible outcome is a provisional pass that does not change the default.
- **Judging.**
  - The pool is the top 20 of lexical search and of both the semantic and the hybrid ranking of each configuration, plus every known target.
  - It is blinded, judged completely by two human judges, and followed by a depth audit of ranks 21 to 100. Judging stops only when the relevant notes the audit says may be missing cannot change the decision. _Changed after Codex's second review._
  - Recall@100 is labelled pooled recall. No LLM pre-sorting.
- **Decision rule.**
  - The frozen rule is unchanged.
  - The added guardrails (exact-match queries, each stratum beyond the input limit, and each Part R contributor) end in veto, hold or clear, with stated criteria.
  - Reweighting uses Part R's histogram, or else three scenarios the candidate must clear.
- **Sizing from Phase A's per-query spread:** ±0.01 needs about 325 queries for `bge@256` (SciFact), and 1,237 to 1,321 for `minilm@128`. 800 is therefore a budget choice that can leave the 128-token and MiniLM candidates INCONCLUSIVE.
- **Owner decisions it needs:**
  - Part R, and whose notes;
  - who judges;
  - whether LLM authors may write Part S's notes;
  - the primary and guardrail sizes;
  - whether to build the deferred no-answer set.

## Measurements added for the targets in answer 5 (November 12, cloud session)

`phase-5-mobile-indexing` at `6e41d47` adds the measurements that "Not done against the protocol" lists under targets. _Changed after the smoke check and Codex's review: `31f1e0c` fixes the reporting, moves the queries before the edits and adds raw samples. The bullets below are updated and marked, and the next section but one lists every changed field._
- No default changed, and nothing was run on a device.
- The local check's python, fixtures and jvm parts passed before the push. CI run 43125937136 is green on every job, including the iOS simulator job, which compiles and runs the shared tests (`PhaseSamplerTest` among them) and runs one tuning configuration.
- The JVM smoke runs only check that the tests work; their times say nothing about phones.

- **Persisted edits.** `IndexingMeasurementTest` takes `edits=<n>`. After the store is indexed, each edit is one of two kinds, alternately:
  - a new note (`put`);
  - new text for an existing note (`update`, which also removes the old version's vector).

  Each is followed by `indexVectors` for that one note. The line reports p50 and p95, overall and by token length, of:
  - the write (`edit_write_`);
  - tokenizing, embedding and saving the vector (`edit_save_`);
  - both together (`edit_total_`), which is the edit-latency target.

  The edit texts are `mixed`-length notes from a separate seed. 5 warm-up edits are not counted.
- **Query and search at 5,000 notes.** `queries=<n>` runs n queries of 2 to 6 words from the notes' vocabulary against the same store.
  - _Changed after Codex's review:_ the queries run before the edits, so they search exactly the 5,000 indexed notes; `query_store_notes` prints the live count.
  - _Changed after Codex's review:_ the four calls of a query rotate through a 4×4 Williams square (`query_order=williams4`), so each call is in each position, and follows each other call within a query, equally often over every four queries. They used to run in one fixed order, with hybrid always last. _Changed after Codex's second review:_ the square balances the calls within a query, not the transitions from one query to the next.
  - The queries use 20 results, the default, and lexical matching on any word, as in the quality study.
  - They are continuous warm measurements on a synthetic vocabulary, not representative search latency.

  It reports p50 and p95 of:
  - the query embedding alone (`query_embed_`);
  - lexical `find` (`query_lexical_`);
  - `findSimilar` (`query_similar_`), which is the embedding plus an exact scan of the stored vectors;
  - `findHybrid` (`query_hybrid_`).

  5 warm-up queries are not counted.
- **Cooling between phases.** `pause=<s>` waits after indexing, so the queries and edits need not run on a phone throttled by the 5,000-note run. Elapsed time does not prove cooling, so the thermal readings decide what a phase measured. Without a pause, the queries and edits describe a hot phone.
- **Timing the phases.** `phase_start_s` gives each phase's start in seconds from the open. _Changed after the local agent's request:_ `open_epoch_ms` is the phone's wall-clock time at the open, so the phases line up with the thermal log exactly.
- **Raw samples** _(added after Codex's review, which said to retain samples)_. After the result line, `[kvid-index-samples]` lines give every counted call's value. With them, the runs of a configuration can be pooled, and per-operation (put or update) percentiles computed.
- **Memory from before the model opens.** Both measurement tests sample from before `open` until after `close`, and the tuning test until 5 s after it:
  - `mem_peak_kb` keeps its earlier meaning, the embedding or indexing phase including the reading at its end, so it stays comparable with Phase B;
  - peaks per phase, and `mem_lifecycle_peak_kb`.
  - _Changed after the smoke check and Codex's review:_
    - the explicit readings are in the same peaks;
    - a sample during which the phase changed counts for the lifecycle only;
    - every phase field is printed on every run;
    - both tests use one label for the method.

  They are still 100 ms samples and explicit readings in the test process: a lower bound, not whole-app memory.

Commands, added to the November 8 block:

```sh
# 5. Edits, queries and lifecycle memory at 5,000 notes: the baseline first, then the configurations the owner
#    keeps (maxTokens=256 or 128 selects the input limit; model=minilm-fp32 for MiniLM).
run $ARGS.class=com.kvid.embed.IndexingMeasurementTest $ARGS.KVID_MODEL_VARIANTS_DIR=$V \
  $ARGS.KVID_INDEX_MEASURE=corpus=mixed+notes=5000+model=bge-fp32+edits=200+queries=200+pause=300
adb logcat -d -s System.out | grep -E "\[kvid-index-(measure|samples)\]"   # changed: keep the samples lines too
```

**What these still do not measure:**
- whole-app memory in a representative app process;
- screen-off and background runs;
- energy.

Those need the sample app, or a dedicated app, rather than an instrumentation test.

## Smoke check and Codex's review of the added measurements (November 12, local session)

Records: [docs/measurements/2026-11-12-answer5](../measurements/2026-11-12-answer5/README.md) (raw smoke lines, Codex's full text and prompt, the draft run plan). **No timed run has been made.**

- **Smoke check** (APK built at `6e41d47`, Pixel 4 XL; the Pixel 8 was unplugged a minute into its run). The edit, query and `phase_start_s` fields and the phase peaks are present. Two faults:
  - `mem_close_peak_kb` is missing from the index line: the close phase lasted 0.1 s, no sample fell in it, and a phase without a sample is left out.
  - On the tuning line `mem_lifecycle_peak_kb` (427,516) is below `mem_peak_kb` (427,532), because the explicit readings do not enter the lifecycle peak.
- **Codex** (`gpt-6.1-sol`, high effort, read-only; `planning` at `185f5fc`, code diff `586cbde..6e41d47`): no P1, seven P2, two P3.
  - **Code, P2:** a memory read spanning a phase change is attributed to the next phase; the explicit readings are not in the lifecycle peak (the fault the smoke check shows). **P3:** queries run against 5,103 live notes while the line prints `notes=5000`.
  - **Code found sound:** what `edit_total_` covers, the query paths (with a synthetic vocabulary), the warm-ups, the percentile, the per-set intervals' separate random stream.
  - **Retained candidates:** the three INCONCLUSIVE verdicts and their intervals reproduce from the per-query records and the digests match. **P2:** "the model rather than the limit" for MiniLM on SciFact is too strong. **P3:** the reproduction command does not locate the analyzer.
  - **Notes-evaluation proposal, four P2:** evidence-position weights are needed for the reweighting; the guardrails are not operational and Part R cannot stay optional for a non-provisional decision; the judgment pool needs its retrieval modes named and the intended target always included; the sizing understates the spread (about 1,200 to 1,300 queries for ±0.01 with MiniLM at 128 tokens).
- **Consequence:** the local session stopped before the timed runs. They wait for a commit that deals with the two code P2s and the missing field, a repeat of the smoke check on that APK, a fresh idle baseline on the Pixel 8 and the owner's go-ahead. The draft run plan is `plan-draft.txt` in the records.
- **Request to the cloud session:** print a wall-clock time at the open in the result line, so the phases can be lined up with the driver's thermal readings exactly.

## Reporting fixes after the smoke check and Codex's review (November 12, cloud session)

`phase-5-mobile-indexing` at `31f1e0c` fixes the faults the smoke check found and Codex's code findings 1, 2 and 8, adds the local agent's wall-clock request, and makes two changes to the measurement now, before any timed run, so that every timed run uses one design.
- **Validation.** The local check's python, fixtures and jvm parts passed (iOS and Android skipped: no macOS or adb in the cloud session). `PhaseSamplerTest` has six cases. CI run 43272663200 is green on every job, including the iOS simulator job, which compiles and runs the shared tests and one tuning configuration.
- **JVM smoke.** On the JVM, with a temporary process-memory reading that was not committed, three configurations produced lines that pass `check_measure_lines.py`. The checker fails both device lines from `6e41d47`, for the two faults the smoke check found. Nothing was run on a device, and no default changed.

### What changed, and why

- **Memory accounting** (Codex 1 and 2, smoke faults 1 and 2).
  - Explicit readings go into the same peaks as the 100 ms samples. They are taken before the open, after it, at the end of every phase, after close, and in the tuning test after the 5 s idle.
  - A sample during which the phase changed counts for the lifecycle peak only: the phase is read on both sides of the memory read.
  - Every phase field of a test is printed on every run.
  - Both lines are built only after the sampler is cancelled and joined.
- **Query store size** (Codex 8). The queries now run before the edits, against exactly the indexed notes.
  - The order is pause, then queries, then edits. The alternatives were to restore the corpus after the edits, which adds work to every run, or to print 5,103.
  - _Changed after Codex's second review_ (this said the queries are short compared with the 300 s pause, which is not evidence of cooling). This does not change what `edit_total_` times. The edits now follow the queries' effects on caches and heat, so the queries and the edits are both continuous warm measurements; the thermal log, not the pause, says how warm.
- **Query call order** (decided now, before step 3). The four calls of each query rotate through a 4×4 Williams square instead of always ending with hybrid. `queries=200` is a multiple of 4, so each order is used 50 times.
- **Raw samples.** The draft run plan asks for edit p95 "pooled over runs", which cannot be computed from per-run percentiles. Codex said to retain samples. `[kvid-index-samples]` lines now give every counted call's value.
- **Wall clock** (local agent). `open_epoch_ms` on both lines.

### Result-line fields added, renamed or changed in meaning

| Line | Field | Change |
|---|---|---|
| both | `open_epoch_ms` | **added:** the phone's wall-clock time (epoch ms) at the origin of `phase_start_s`, just before the open |
| both | `mem_peak` | **value renamed:** `sampled-100ms` (tuning) and `sampled-100ms-from-before-open` (index) are both now `sampled-100ms+readings-from-before-open` |
| both | `mem_<phase>_peak_kb` | **meaning changed:** includes the explicit reading at the phase's end (`open` also includes the reading before the open) and excludes samples that span a phase change. Always printed: a number, `not-run` for a phase whose setting is 0, or `none` if every memory read in the phase failed (possible on iOS when `task_info` fails, not on Android). _Added after the second smoke check:_ `mem_close_peak_kb` is in practice the reading after close alone, because close is shorter than the sampling interval, and that reading comes after the memory is released. It is not the memory held while closing (on both smoke lines it equals `mem_after_close_kb`) |
| both | `mem_lifecycle_peak_kb` | **meaning changed:** the highest of every sample, spanning ones included, and every explicit reading |
| both | `mem_samples`, `mem_boundary_samples` | **added:** sampled reads, and how many of them spanned a phase change. _Added after the second smoke check:_ `mem_boundary_samples` was 0 on both device lines, so a device has not yet exercised that path (the unit tests do) |
| both | `mem_peak_kb` | **same meaning** (the indexing or embedding phase's peak, including the reading at its end); now equal to `mem_index_peak_kb` or `mem_embed_peak_kb` by construction |
| index | `phase_start_s` | **changed:** starts with `open:0.0`; order `open,index,pause,queries,edits,close,end` (queries before edits); lists only phases that ran |
| index | `query_store_notes` | **added:** live notes when the queries start; equals `notes` |
| index | `query_order` | **added:** `williams4` |
| index | `query_*_p50_ms`, `query_*_p95_ms` | **conditions changed:** measured before the edits, on exactly `notes` notes, in rotated call order. Not poolable with `6e41d47` lines (none were timed) |
| index | `edit_*` | **conditions changed:** measured after the queries rather than straight after the pause; fields unchanged |
| index | `[kvid-index-samples]` | **new line**, one per series and part of up to 200 values: `open_epoch_ms`, `series`, `part=k/m`, `n`, `values`. The series are `query_embed_ms`, `query_lexical_ms`, `query_similar_ms`, `query_hybrid_ms`, `edit_total_ms`, `edit_save_ms`, `edit_write_ms`, `edit_tokens` and `edit_kind` (`put` or `update`) |
| tuning | `phase_start_s` | **added:** `open,embed,close,idle,end` |
| tuning | `mem_idle_peak_kb` | **added:** the 5 s after close, now sampled; `mem_after_close_kb` and `mem_idle_5s_kb` are unchanged |

### Invariants the smoke check confirms

_Superseded by the list in "Checker and open-mark fixes after Codex's second review" below, which the checker at `5afdc3a` enforces._

`python3 -I kvid-embed-onnx/scripts/check_measure_lines.py RAW.txt` checks all of these on every result line in the file, together with that run's samples lines. It exits 0 only if all hold.
1. `open_epoch_ms` is present and a plausible epoch time.
2. `phase_start_s`:
   - starts at `open:0.0`;
   - lists the phases that ran, in run order, with non-decreasing times;
   - ends with `end`.
3. `mem_peak=sampled-100ms+readings-from-before-open` on both lines.
4. Every phase field is present: a number for a phase that ran, `not-run` exactly for a phase whose setting is 0, never `none`.
5. `mem_lifecycle_peak_kb` is at least every other `mem_*_kb` value on the line.
6. `mem_peak_kb` equals `mem_index_peak_kb` (index line) or `mem_embed_peak_kb` (tuning line).
7. `mem_boundary_samples` ≤ `mem_samples`.
8. With queries: `query_store_notes` equals `notes`, and `query_order=williams4`.
9. With queries or edits, the samples lines:
   - every series is present with all its parts, and has as many values as the setting asks;
   - the nearest-rank p50 and p95 recomputed from them equal the line's;
   - `edit_kind` is half `put` and half `update`.

Not checked by the script: `mem_samples` per second of the run. _Changed after the second smoke check:_ it is about eight per second on the Pixel 4 XL (491 in 62.5 s, 292 in 36.3 s), not ten. 100 ms is the delay between reads, and each read adds about 25 ms.

### Comments on the draft run plan (`plan-draft.txt`)

- **Commit:** `31f1e0c` (CI run 43272663200 green).
- **Capture the samples lines.** Keep the `[kvid-index-samples]` lines with each result line (the changed `grep` in the command above), and run the checker on each run's raw file before the next run. A failing check is a harness fault: stop and report it to the cloud session, rather than excluding the run under the exclusion rules.
- **Thermal alignment.**
  - Use `open_epoch_ms`. It is the phone's clock. The draft records thermal readings "with host UTC times", while the owner's brief says the phone's clock.
  - Either log the readings with the phone's clock, or record the phone-to-host offset at each gate (`adb shell date +%s%3N` beside the host time).
  - With the new phase order, report the thermal status at the start of the index, queries and edits phases.
- **Pooled percentiles.**
  - Define pooled p95 as the nearest-rank p95 over all counted calls of a configuration's valid runs (600 for three runs), computed from the samples. Report it beside each run's p95.
  - Also report edits by kind (`put`, `update`) from `edit_kind`. Codex noted that a pooled p95 over the mixture does not establish either operation's p95.
- **Also report** `mem_boundary_samples` per run.
- **No objection to the rest:**
  - the cooling formula and its fresh Pixel 8 baseline;
  - AB, BA, AB, BA blocks over at least two sessions;
  - exclusions decided by rule only;
  - the 10% threshold;
  - the optional steps.

## Second smoke check and Codex's review of the fixes (November 12, local session)

Records: [docs/measurements/2026-11-12-answer5](../measurements/2026-11-12-answer5/README.md) (`smoke2-*.txt`, `codex-review-2-2026-11-12.md`, the revised `plan-draft.txt`, `driver/`). **No timed run has been made.**

- **Smoke check** (APK built at `31f1e0c`, sha256 `e6963588…6748d5`; Pixel 4 XL, the Pixel 8 was not attached). Both instrumentation runs ended OK and `check_measure_lines.py` prints `OK` for the index line and the tuning line. The faults of the first smoke check are gone.
  - `mem_samples` is about 8 per second on this phone (491 in 62.5 s, 292 in 36.3 s), not 10: the 100 ms is the delay between reads.
  - `mem_boundary_samples` is 0 on both lines, so the device has not exercised that path.
  - `mem_close_peak_kb` equals `mem_after_close_kb` on both lines: close is too short for a sample, and the only reading credited to it is the one after the memory is released.
- **Codex** (`gpt-6.1-sol`, high effort, read-only; `planning` at `7dca06a`, code diff `6e41d47..31f1e0c`): **no P1**; its earlier code findings 1, 2 and 8 are resolved.
  - **Checker, P2:** the by-token-length percentiles are not recomputed, so a wrong `edit_total_le64_p95_ms` passes. The local session reproduced this. **P3:** part metadata of a samples series is checked on the first part only; a `nan` phase time passes (reproduced); an odd `edits` count fails although legitimate.
  - **Test, P3:** the `open` mark is taken with `now()` after the origin, so a stall of 50 ms or more prints `open:0.1` and fails the checker. On a long run that would stop the queue without a real fault.
  - **Sound:** the phase attribution and the CAS peak; the lifecycle peak by construction; the Williams square; the samples lines and their length; `open_epoch_ms` for 15 s thermal sampling; the run-plan comments. Queries before edits do not change what `edit_total_` times, but the edits now follow the queries' cache and heat effects.
  - **Documents:** earlier findings 4, 6, 7 and 9 are resolved; 3 and 5 are incomplete. **Two new P2 on the notes proposal:** the judging passes can leave the ideal ranking for nDCG@10 incomplete, and the quotas allow empty strata that the reweighting needs. **P3:** evidence boundaries must count the special tokens; the export allowlist and the overlap report disagree.
- **Run plan.** Still a draft: revised for `31f1e0c`, the cloud session's comments, and two points of practice (at most two valid blocks per session; what counts as a baseline reading). The driver for the runs is written and was tried on the Pixel 4 XL with a small setting.
- **Open before step 3:** the cloud session's decision on Codex's checker findings and finding 7 (a test change means a new APK and a third smoke check); the Pixel 8 idle baseline; `plan.txt`; the owner's go-ahead.


## Checker and open-mark fixes after Codex's second review (November 13, cloud session)

`phase-5-mobile-indexing` at `5afdc3a` fixes the checker findings (1, 4, 5 and 6) and the open mark (7) of [Codex's second review](../measurements/2026-11-12-answer5/codex-review-2-2026-11-12.md), which the owner decided to have fixed before any timed run.
- **Validation.** The local check's python (now with the checker's 19 unit tests), fixtures and jvm parts passed; iOS and Android were skipped, as there is no macOS or adb in the cloud session. CI run 43335617072 is green on every job, including the iOS simulator job.
- **Checker runs:**
  - It passes the second smoke check's device lines, and JVM smoke lines from `5afdc3a` (both tests, with an odd edit count of 21, using a temporary process-memory reading that was not committed).
  - It fails each of Codex's counterexamples.
- No default changed, and nothing was run on a device.

**Test code changed (finding 7), so the APK must be rebuilt and the smoke check repeated.**
- Both tests now store the `open` mark as exactly 0.0, the origin of `phase_start_s`, instead of reading the clock a moment after that origin.
- No result-line field is added or renamed. One is **changed in meaning**: `phase_start_s`'s `open:0.0` is now exact by construction. Its origin is unchanged: the monotonic mark taken together with `open_epoch_ms`, just before the open.

**Checker changes** (`kvid-embed-onnx/scripts/check_measure_lines.py`):
- **Finding 1:** it recomputes every by-length count and percentile of `edit_total_` and `edit_save_` from `edit_tokens` and the series, and checks the token values.
- **Finding 4:** every part of a samples series must carry the same part count and `n`, part numbers must be unique, and every part but the last must be full.
- **Finding 5:** phase times must be finite and non-negative.
- **Finding 6:** edit kinds must alternate from `update`, which is what the harness's five warm-up edits produce, so an odd count passes.
- **Also:**
  - it fails samples lines that belong to no result line, two result lines of one run, and unexpected or malformed series;
  - malformed input is reported, not a crash;
  - `test_check_measure_lines.py` keeps every counterexample failing, and the local check's python part runs it. CI does not run it.

### Invariants the smoke check confirms (complete list, replacing the one above)

`python3 -I kvid-embed-onnx/scripts/check_measure_lines.py RAW.txt` (at `5afdc3a`) exits 0 only if all of these hold for every result line in the file, with that run's samples lines.
1. `open_epoch_ms` is present and a plausible epoch time.
2. `phase_start_s`:
   - starts with exactly `open:0.0`;
   - every time is finite and non-negative;
   - lists the phases that ran, in run order, with non-decreasing times;
   - ends with `end`.
3. `mem_peak=sampled-100ms+readings-from-before-open` on both lines.
4. Every phase field is present:
   - a number for a phase that ran;
   - `not-run` exactly for a phase whose setting is 0;
   - `none` is a failure. It appears only if every memory read in a phase failed, which is possible on iOS if `task_info` fails, not on Android.
5. `mem_lifecycle_peak_kb` is at least every other `mem_*_kb` value on the line.
6. `mem_peak_kb` equals `mem_index_peak_kb` (index line) or `mem_embed_peak_kb` (tuning line).
7. `mem_boundary_samples` ≤ `mem_samples`.
8. With queries: `query_store_notes` equals `notes`, and `query_order=williams4`.
9. With queries or edits, the samples lines:
   - **The right series:** exactly the series the settings call for. No samples lines of another run, and no second result line of the same run.
   - **Each series complete:** every part has the same part count and `n`; part numbers are unique and run from 1 to the count; every part but the last holds 200 values; there are `n` values in all, and `n` equals the setting.
   - **Values well formed:** milliseconds with one decimal; `edit_tokens` from 1 to `max_input_tokens`; `edit_kind` alternating from `update`.
   - **Percentiles:** the overall p50 and p95 of every timed series equal those recomputed from the samples. For `edit_total_` and `edit_save_`, so do every by-length bucket's count, p50 and p95. A bucket with no edits prints no fields.

Not checked:
- the tuning line's by-length latency fields (`latency=on`), which have no samples and are not used in the plan;
- `mem_samples` per second, about eight on the Pixel 4 XL.

### Wording changed after the second smoke check and Codex's second review

These are corrected in the earlier sections and marked there:
- `mem_samples` is about eight per second, not ten.
- `mem_close_peak_kb` is in practice the reading after close, taken after the memory is released.
- `mem_boundary_samples` has not been exercised on a device.
- `none` is possible when memory reads fail (iOS).
- The queries and the edits are both continuous warm measurements: the edits follow the queries' cache and heat effects, the pause's length is not evidence of cooling, and the Williams square balances calls within a query, not the transitions between queries.
- In [notes-evaluation.md](notes-evaluation.md):
  - **Judging (finding 2):** the declared pool is judged completely, with a stopping rule against missing relevant notes.
  - **Strata (findings 3 and 8):** they now need minimum support, missing support is a hold, and membership is defined by what survives encoding.
  - **Allowlist (finding 9):** it now includes the overlap histogram.

### Comments on the revised draft run plan (`plan-draft.txt`)

- **Code and checker:** `5afdc3a` (CI run 43335617072 green), with the new APK's hash and the third smoke check.
  - Remove the interim line "Unless a later checker does, the analysis recomputes them…": the checker now recomputes the by-length percentiles, and its tests keep that from regressing.
- **The local agent's additions:** agreed, each as written:
  - at most two valid blocks per attachment;
  - what counts as a baseline reading, and 21 in a row within 2 hours;
  - the screen on at the start of a run;
  - an excluded block repeated whole, at most three attempts;
  - an instrumentation run that does not end "OK (1 test)" stops the queue.
- **Display state, one addition.** The driver does not hold the screen on, so after the phone's screen timeout the queries and edits (from about five minutes into a run) likely run with the display asleep.
  - The 200 ms target describes a foreground edit, with the display on. Whether a phone's CPU behaves differently with the display asleep is not established either way.
  - `therm5.sh` already records `wake=` in every reading. Report it at the start of each phase beside the thermal status, and state beside the edit latency if the edits ran with the display asleep.
  - Keeping Phase B's practice is the owner's call. Holding the display on (for example `svc power stayon usb`) would add heat, and must be decided before `plan.txt` is committed, not after.
- **Thermal alignment:** the phone's clock in the same adb call as each reading, and the offset at every run, is what the comments asked for. No other objection.
