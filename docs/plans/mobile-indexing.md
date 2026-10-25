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
