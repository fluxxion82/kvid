# ADR 0002: Runtime for kvid's first real embedding model

_Status: **Proposed** (October 5, 2026) for owner and Codex review. The store-side vector layer it builds on is implemented on `phase-5-semantic-search` and does not depend on this decision._

## Context

Milestone 5 adds optional semantic and hybrid search. The store already accepts any `Embedder` (an interface returning vectors for texts), records the model's `EmbeddingSpec` in the file, refuses vectors from a different spec, and searches vectors exactly. What is missing is one real model that produces the same vectors on JVM, Android and iOS, so a store indexed on one platform searches correctly on another (roadmap, Milestone 5).

Constraints:

- Optional: lexical-only users get no model, runtime or download. Nothing in `kvid-core` may depend on the runtime.
- Portable identity: equal `EmbeddingSpec` must mean interchangeable vectors on every platform, within a stated tolerance.
- Mobile cost: on-device indexing of thousands of notes must take seconds to minutes, not hours, and the binary and model must have a stated size.
- This cloud session cannot reach Hugging Face; GitHub Actions runners can. Model files are not committed to the repository.

## Options

### A. ONNX Runtime with one shared ONNX model (recommended)

Official artifacts: `com.microsoft.onnxruntime:onnxruntime` for the JVM and `onnxruntime-android` for Android, both 1.30.0 on Maven Central (the JVM jar is about 56 MB with natives for every desktop platform; the Android AAR about 53 MB across ABIs, of which an app ships one or two). iOS uses the C API from Kotlin/Native through cinterop, linking Microsoft's prebuilt `onnxruntime` xcframework (CocoaPods `onnxruntime-c`; the official Swift package's newest tag is v1.19.2, so the current iOS distribution channel must be confirmed first).

- Pro: the same model file and graph run everywhere, with optimized kernels; inference for a short note is milliseconds, not seconds.
- Pro: widely used; quantized (int8) models reduce size and time later without changing the integration.
- Con: native size per platform, to be measured in the sample apps. iOS integration (cinterop, static linking, CI) is the largest engineering task.
- Con: float results differ slightly across CPUs and kernels; parity is a tolerance, not bit equality.

### B. Pure-Kotlin transformer inference

One Kotlin implementation of the model forward pass on every platform.

- Pro: identical code paths, no native dependency, small binary.
- Con: speed. A 6-layer MiniLM costs about 45 MFLOP per token, roughly 6 GFLOP for a 128-token note. Scalar Kotlin reaches about 1 to 2 GFLOP/s on JVM and Kotlin/Native, so one note takes seconds and a 5,000-note corpus hours. Not viable for the target workloads.

### C. Platform-native models (Apple `NLContextualEmbedding`, MediaPipe text embedders)

- Pro: no bundled runtime on the platform that provides it.
- Con: a different model per platform, so vectors are not portable; each must be a separate provider with its own `EmbeddingSpec`. Suitable as later, additional providers.

### D. Remote embedding APIs

- Con: network and privacy requirements contradict the offline direction. Later, opt-in providers at most.

## Proposed decision

1. A new optional module, `kvid-embed-onnx` (Kotlin Multiplatform: JVM, Android, iOS arm64 and simulator arm64), depending on `kvid-core` and ONNX Runtime. `kvid-core` stays model-free.
2. First model: `BAAI/bge-small-en-v1.5` (MIT license, 33M parameters, 384 dimensions, CLS pooling, L2-normalized, 512-token input), as the roadmap proposed. `sentence-transformers/all-MiniLM-L6-v2` (Apache-2.0, 22M parameters, mean pooling) is the fallback if mobile indexing time is too high.
3. Tokenizer in pure Kotlin: BERT basic tokenization (lowercasing, accent stripping, punctuation and CJK splitting) plus WordPiece over the model's `vocab.txt`, so token ids are identical on every platform.
4. The app supplies the model and vocabulary files (downloaded on demand or bundled in its assets). The embedder computes SHA-256 digests of both and writes them into `EmbeddingSpec.modelDigest` and `tokenizer`, so a different file is a different model.
5. Inputs longer than the model's limit are truncated, and the limit is recorded in `maxInputTokens`. Token-aware chunking, with a defined chunk-to-document aggregation, is a later step.

## Verification plan

- Tokenizer parity: a fixture of a few hundred varied strings (Unicode, accents, CJK, emoji, punctuation, long inputs) tokenized by Hugging Face `tokenizers` in CI; Kotlin token ids must match exactly on JVM, Android and iOS.
- Embedding parity: reference vectors from Python ONNX Runtime for the same fixture; each platform must reach cosine similarity of at least 0.9999 with the reference, with the maximum absolute difference reported.
- CI downloads the model from Hugging Face with a pinned revision and checksum, caches it, and fails if it is missing; model-dependent tests are required, not skipped, in CI.
- Measure per platform: model load time, embedding time per note and per 5,000-note corpus, peak memory, and added binary size in the sample apps.
- Retrieval quality on a representative evaluation corpus with relevance judgments, comparing lexical, semantic and hybrid ranking and tuning the fusion constant (roadmap exit criterion).

## Consequences

- Apps that want semantic search add `kvid-embed-onnx` and ship or download about 130 MB of fp32 model (about 35 MB int8) plus the runtime. Everyone else is unaffected.
- The iOS cinterop and its CI are the main risk; JVM and Android can land first behind the same API.
- Exact vector search remains the baseline; HNSW is evaluated only after these measurements (roadmap, Milestone 5).

## When to revisit

- iOS integration of ONNX Runtime proves impractical, or its binary size is unacceptable for the sample.
- Measured mobile indexing time for `bge-small-en-v1.5` is too slow, favouring MiniLM or int8 models.
- A maintained Kotlin Multiplatform inference library with comparable speed appears.
