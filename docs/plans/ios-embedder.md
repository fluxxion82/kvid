# Brief: ONNX Runtime embedder on iOS

_Written October 25 by the cloud session. Step 3 of [ADR 0002](../adr/0002-embedding-runtime.md). The local agent turns this into an implementation plan (as with [onnx-embedder-implementation.md](onnx-embedder-implementation.md)), has it reviewed by Codex and the cloud session, implements it on a Mac, and runs `scripts/check-local.sh` before each push._

## Goal

`OnnxEmbedder` on `iosArm64` and `iosSimulatorArm64`, with the same behavior as on the JVM and Android:

- the same `EmbeddingSpec` for the same files;
- the same lifecycle (concurrent `embed`, `close` waits, a cancelled `open` leaks nothing);
- parity with Python ONNX Runtime at cosine ≥ 0.9999 on the existing 194-case fixture.

The common pipeline (`BertEmbedder`, tokenizer, pooling, normalization) is reused unchanged. Only the runtime backend, file digests and opening are new.

## Requirements

- **Runtime.** ONNX Runtime **1.30.0** C API through Kotlin/Native cinterop.
  - Use Microsoft's prebuilt `onnxruntime` xcframework (the `onnxruntime-c` pod archive), downloaded at a pinned URL with a pinned SHA-256 and never committed.
  - The plan states how consumers get the runtime: the module's cinterop and linker options, what an app must link or embed, and what the XCFramework or Swift package of kvid contains. A Swift app must not need CocoaPods for kvid.
- **API shape.** The sample's shared code should be able to open an embedder from `commonMain`. The plan decides between:
  - an `expect` declaration in `commonMain`, with actuals in the shared JVM/Android directory and in `iosMain`;
  - keeping platform classes with identical signatures.

  It also covers what that means for the Android and JVM code already merged.
- **Digests.** Streaming SHA-256 with CommonCrypto, matching `sha256Hex` on the JVM.
- **Native memory and threads.**
  - Tensors are built from pinned Kotlin arrays or ORT-allocated buffers, and every `OrtValue`, session and options object is released exactly once, also on failure and cancellation.
  - `close` waits for running inferences. The plan names the K/N primitive used for that.
  - Telemetry is disabled through the C API.
  - The same `OnnxOptions` apply: threads, arena, memory pattern, batch size and sorting.
- **Tests.**
  - The `jvmAndAndroidTest` classes (parity, lifecycle, store, measurement) run on the iOS simulator against the same fixture.
  - The simulator reads host paths, and `SIMCTL_CHILD_*` already forwards the variables.
  - CI's iOS job downloads or restores the model and requires `OnnxEmbedderParityTest`, `OnnxEmbedderTest` and `OnnxEmbedderStoreTest`, gated on test results as on the JVM.
- **Measurements.** Run them with optimized (release) Kotlin/Native binaries, since debug binaries were 10 to 40 times slower in Milestone 3. Simulator timings are a reference only. If a physical iPhone is available, record:
  - time per note on the `mixed` and `m3` corpora;
  - the memory footprint (`phys_footprint`);
  - the size the runtime adds to an app.

  These feed [mobile-indexing.md](mobile-indexing.md).

## Out of scope

Model variants (decided by the mobile-indexing study), Core ML or other execution providers, background indexing, and publishing.

## Review

- **Before implementation:** Codex and the cloud session review the implementation plan.
- **After the push:** the cloud session reviews the diff and the CI logs. It cannot run iOS itself, so the local check's iOS part and CI are the evidence.
