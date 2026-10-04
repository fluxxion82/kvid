# kvid roadmap

_Revised October 4, 2026. This merges the owner's Codex-reviewed revision of the roadmap with the state of the `phase-0-baseline` branch. Unchecked items are planned work, not implementation claims. Where something was verified by running it, the text says so; everything else comes from source inspection._

## Direction

kvid becomes a general-purpose, embedded, portable searchable document store for Kotlin Multiplatform applications on JVM, Android, and iOS. Documents, metadata, and eventually optional vector indexes travel together in a portable file. Notes, journals, offline documentation, transcripts, and message history are the initial use cases. AI integrations come last and remain optional.

The first release has one concrete goal: **save documents, reopen safely, and search offline on all three platforms.**

QR codes in video become an optional archive/export feature. Repairing every existing video implementation is not a prerequisite for the new store. memvid informs the design (it solved the same problems and then abandoned the QR approach), but neither its API nor its internal format determines kvid's architecture.

## Current baseline (branch `phase-0-baseline`)

What is on the branch, and how each item was checked:

| Item | State | How verified |
|---|---|---|
| Gradle wrapper jar committed (was excluded by `*.jar`) | done | fresh `./gradlew` run in a clean container |
| Kotlin 2.5.0-Beta1, Gradle 9.8.0, JDK 17 toolchain | done | JVM compile and tests executed |
| AGP 9.4.0 via `com.android.kotlin.multiplatform.library`; Android target configured with `android {}` inside `kotlin {}`; device tests in `src/androidDeviceTest` | done | CI Ubuntu job (`./gradlew build`: Android compile, host tests, lint, JVM tests, examples) green on three consecutive runs |
| iOS targets unchanged (iosX64, iosArm64, iosSimulatorArm64, static framework) | done | CI macOS job: Kotlin/Native 2.5.0-Beta1 compiles the iOS source sets and tests; 88 simulator tests run. Five `IosQRCodeGeneratorTest` cases fail on the runner with `Failed to create CGImage from QR code` (see Appendix A) and are skipped with that reason until reproduced locally |
| kotlinx-coroutines 1.11.0, kotlinx-serialization 1.11.0 with the compiler plugin applied, androidx.test 1.7.0 / 1.3.0 | done | JVM compile |
| Removed: Kotlin dev repo, `mavenLocal()`, four unrelated repos, `kotlinx-benchmark`, `maven-publish`, `appcompat`, forced Kotlin resolution strategy, no-op build-cache block | done | JVM configure |
| `kvid-examples` compiles (three duplicate `main` functions removed) | done | compile executed |
| ffmpeg-dependent JVM tests fail instead of skip when `CI` is set | done | ran with ffmpeg present |
| Tautological tests removed or replaced with behavioral ones (see Milestone 0) | done | JVM suite executed: 106 tests, 0 failures, 0 skipped |
| `CancellationException` is rethrown ahead of every `catch (e: Exception)` in main source sets | done | JVM compile; Android and iOS source sets compile only in CI |
| `MemoryStore.exportIndex` uses kotlinx.serialization instead of hand-built JSON | done | round-trip test with quotes, backslashes, tabs and newlines |
| `HnswVectorIndex.search` sorts candidates by distance before taking top-k (results were returned in visit order) | done | new recall@10 test against the exact index |
| GitHub Actions: Ubuntu job (ffmpeg, `./gradlew build`, examples compile) and macOS job (iOS simulator tests) | done | runs on pushes to `main` and `phase-*` branches, on pull requests, and manually |
| README prerequisites and status describe the real per-platform state | done | review |

Still true, from source inspection, and unchanged by Phase 0:

- `MemoryStore` retains chunks and metadata in memory; saving a vector index alone does not persist a searchable document store.
- `SimpleEmbedding` is a positional character-code demonstration, not a semantic model.
- The iOS encoder writes a custom container rather than MP4; iOS compression is raw DEFLATE while JVM and Android use gzip; the iOS QR decoder cannot build an image from raw pixels (an `@Ignore`d round-trip test now documents this in `iosTest`).
- The Android encoder does not drain output buffers during encoding, and there is no Android QR generator.

The full per-platform defect list is in Appendix A as an investigation checklist. The Android and iOS items are from reading the code, not from device execution.

## Milestone 0: establish a reproducible baseline

- [x] Make a fresh checkout build using a committed Gradle wrapper, including its JAR.
- [x] Choose the Kotlin/Gradle/AGP combination (2.5.0-Beta1 / 9.8.0 / 9.4.0). JVM verified; Android and iOS verification is the first CI run on this branch. The Kotlin Gradle plugin compatibility table is not yet published for 2.5.0-Beta1 (2.4.20 lists AGP 9.3.1 and Gradle 9.7.0 as the newest fully supported). If CI rejects the combination, pin `kotlin = "2.4.20"` in `gradle/libs.versions.toml` until 2.5.0 ships.
- [x] Apply the serialization compiler plugin where generated serializers are needed.
- [x] Remove unused repositories, plugins, and dependencies after confirming usage.
- [x] Add CI for JVM tests, Android host tests, examples compilation, and iOS simulator tests.
- [ ] Add an Android emulator job for device tests once there is platform-specific storage behavior to test (Milestone 2). Host tests alone do not establish mobile support.
- [x] Make missing prerequisites visible in test reports; required CI coverage fails when its prerequisites are absent.
- [x] Correct README capabilities and prerequisites to match verified behavior.
- [x] Replace tautological tests with behavioral tests where coverage is needed; remove assertions that establish no behavior. Removed: data-class construction and enum-count "integration" tests, `100 < 10000`, byte-in-0..255, `assertNotNull` on non-null `Result`, a print-only benchmark that ran as a unit test, the whole iOS video decoder test file (16 tests that never called the decoder). Added: HNSW recall against exact search, HNSW top-k equals exact top-k on a small set, exact sentence-boundary chunking, corrupted compressed payload fails, vectors and search results survive save/load, export JSON round trip, an ignored iOS QR round trip that documents the known decoder failure.
- [x] Preserve coroutine cancellation through error handling. `Result` versus typed exceptions remains a separate API decision (open decision 5).
- [x] Every test task prints failed and skipped events with full exception messages and causes, so CI logs are diagnosable without the report artifacts.
- [ ] Confirm the first fully green CI run (Ubuntu and macOS jobs) and record the commit here. Ubuntu has been green since the first run; macOS becomes green once the five Core Image rendering tests are skipped (next run after `5dcd1a7`).
- [ ] Reproduce the Core Image failure on a local Mac. If the generator works there, the skip reason becomes "GitHub runner limitation" and the tests can be gated on an environment variable; if it fails there too, Phase 1's pure-Kotlin QR encoder replaces it.

**Exit criterion:** reproducible build instructions and CI results for the actual branch, with platform limitations stated accurately.

## Milestone 1: choose storage architecture and define its contract

Compare three approaches in a short architecture decision record before implementing a database engine:

| Approach | Benefit | Cost or constraint |
|---|---|---|
| SQLite-backed document store | Existing transaction and recovery machinery; a portable database file; FTS5 gives BM25 ranking without writing an index | Native integration and deployment; WAL mode creates `-wal`/`-shm` sidecars while open; vectors need an extension or application-side scanning |
| Append-only document log with rebuildable indexes | Smaller custom format; authoritative documents survive index loss | kvid owns recovery, durable writes, compaction, and indexing |
| Custom file with embedded WAL and persisted index segments (memvid v2's shape) | Direct control over a strict single-file runtime format | Largest correctness and maintenance burden |

**Provisional preference:** evaluate SQLite first. The Kotlin Multiplatform `androidx.sqlite` bundled driver ships one SQLite build for Android, iOS and JVM, which removes the per-platform variance that sank memvid v1 and gives transactions, crash recovery and full-text search on day one. kvid's own value then lives above it: the document model, history, portable snapshot export, optional vectors, and the archive formats. Fall back to the append-only log only if owning the format proves essential to the product or SQLite's constraints (sidecars while open, extension loading on iOS, binary size) fail the criteria below.

Evaluation criteria, measured on a phone with a representative notes corpus (thousands of documents, tens of MB):

- [ ] FTS5 is compiled into the chosen driver on every target (verify, do not assume).
- [ ] "Single file" is defined precisely: one portable artifact after a clean close is the target; no sidecars even during writes is a stretch goal. Decide whether temporary files during compaction or snapshot export are permitted.
- [ ] Open time, ingest time, query latency, peak memory, and file size for each candidate.
- [ ] Binary size added to an Android APK and an iOS app.
- [ ] Behavior when copied while open, when the process is killed mid-transaction, and on disk-full.

### Durability and concurrency

- [ ] Specify whether `put` merely stages a change and whether only `commit` promises durability.
- [ ] Specify transaction atomicity, read-your-writes behavior, rollback, and the result of closing with uncommitted work.
- [ ] Define how incomplete records, torn writes, and incomplete transactions are recognized.
- [ ] Specify write and durable-sync ordering and recovery after each step.
- [ ] If using a WAL, specify checkpoint publication and safe WAL reuse, including a full-WAL policy.
- [ ] Start with one writer. Define same-process coroutine synchronization, cross-process locking, and reader visibility during commits.
- [ ] Define behavior on disk-full errors, cancellation, failed sync, unsupported format versions, and corruption.
- [ ] Define a narrow platform I/O interface for positioned reads/writes, durable synchronization, locking, and file replacement. Keep encoding and recovery logic common. Buffered I/O alone does not establish durability.

### Documents, versions, and history

- [ ] Use stable `documentId` values, immutable `versionId` values, and separate chunk identifiers. A URI is metadata or an explicitly defined key, not an implicit identity rule.
- [ ] Separate commit order from application event time. Define which timestamp each date filter uses and how timestamp ties are resolved.
- [ ] Define update/delete behavior and which version is visible at a commit sequence.
- [ ] Define retention and compaction policies. Keeping history and physically removing deleted versions are different modes with different guarantees.
- [ ] Define whether historical search requires historical ranking statistics or only historical document visibility. Defer historical ranked search if necessary.
- [ ] Define pagination ordering and whether cursors remain valid across commits.

### Format evolution and bounds

- [ ] Document versioning, feature flags, unknown-field behavior, and migration policy.
- [ ] Bound record lengths, metadata sizes, decompression output, and allocation sizes before reading user-supplied files.
- [ ] Choose one portable compression envelope and prove it with shared fixtures. Gzip, zlib-wrapped DEFLATE, and raw DEFLATE are distinct formats; today iOS and JVM/Android disagree.
- [ ] Make authoritative documents recoverable independently of derived indexes; define index rebuild behavior.
- [ ] Reserve a format path for encrypted records and authenticated metadata before freezing the format. Define nonce uniqueness, key identification, and what remains visible without a key.

The header/WAL/data/index/footer layout from the earlier draft is a candidate sketch for the custom-format option only, not a committed specification.

**Exit criterion:** an architecture decision record, a written persistence contract, and small platform I/O prototypes that establish the required primitives on JVM, Android, and iOS.

## Milestone 2: durable document store

- [ ] Implement `create`, `open`, `close`, `put`, `get`, `update`, `delete`, and atomic `commit` according to the contract.
- [ ] Persist document text and metadata, not just vectors.
- [ ] Add portable format fixtures written and read across all three platforms.
- [ ] Add integrity verification and explicit corruption errors.
- [ ] Implement recovery and rebuildable derived state.
- [ ] Test interruption at transaction boundaries, truncated records, damaged checksums, disk-full/short-write failures, and cancellation.
- [ ] Distinguish deterministic I/O fault injection from actual platform durability testing; neither proves every power-loss scenario.
- [ ] Add minimal JSON Lines import/export for inspection and recovery.

Keep large attachments, historical ranked search, and a full CLI outside this milestone unless they are necessary for the selected use case.

**Exit criterion:** committed documents and metadata survive close/reopen on every target; interrupted writes preserve the last committed state; corruption cannot silently produce successful reads.

## Milestone 3: useful offline full-text search

- [ ] Provide lexical search with BM25 ranking, deterministic tie-breaking, and documented Unicode normalization/tokenization. With SQLite this is FTS5 configuration plus a tokenizer decision; with a custom store it is a standalone inverted index.
- [ ] Start with basic text queries and tag, date, and URI filters. Specify AND/OR filter behavior.
- [ ] Search the visible document versions; prevent deleted or superseded versions from leaking into current results.
- [ ] Define chunk-to-document result aggregation before exposing chunked search.
- [ ] Verify ranking against a representative notes/document corpus and an independent reference calculation for small cases.
- [ ] Ensure indexes rebuild from authoritative documents and remain consistent after recovery.
- [ ] Measure ingest time, open time, query latency, peak memory, and file size at representative corpus sizes. Record devices and workloads before setting release budgets.

Defer stemming, advanced query syntax, phrase/prefix queries, and adaptive score cutoffs until demonstrated needs justify their complexity.

**Exit criterion:** persisted documents are searchable offline with reliable filtering and measured performance on target platforms.

## Milestone 4: mobile proof and first release

- [ ] Build a small Android/iOS notes sample (Compose Multiplatform): create, edit, delete, close/reopen, search, and filter.
- [ ] Exercise background/foreground transitions, serialized concurrent calls, and recovery after process interruption.
- [ ] Expose a documented resource lifecycle, including `close` and transaction ownership.
- [ ] Provide basic document history if supported by the retention contract.
- [ ] Validate backup/export from a consistent committed snapshot. Copying a live writable file must not be an undocumented backup strategy.
- [ ] Publish only targets that meet the acceptance criteria; document packaging for Kotlin and Swift consumers (Maven Central plus an XCFramework).
- [ ] Provide a clear migration statement for the existing experimental APIs (`MemoryStore`, `MemoryEncoder`, `MemoryDecoder`) and stored artifacts.

**First-release boundary:** durable documents, metadata, BM25, basic filters, portable files, and a working mobile sample. No embedding download is required.

## Milestone 5: optional semantic and hybrid search

- [ ] Support one real embedding implementation first; verify tokenizer, pooling, normalization, and output parity across supported platforms.
- [ ] Record the full embedding configuration in the file: model/revision or hash, tokenizer revision, dimensions, pooling, normalization, and distance metric. Refuse to mix models.
- [ ] Treat embeddings as derived data; define missing-model behavior and explicit re-embedding/index migration.
- [ ] Keep exact vector search as the correctness baseline and initial implementation.
- [ ] Introduce HNSW only after measurements establish a benefit. Evaluate recall@k versus latency and memory across parameters and multiple seeded datasets. The current implementation is a reference, not a release candidate: it recomputes the farthest result inside its inner loop and persists as CSV text.
- [ ] Benchmark graph construction and query behavior; avoid fragile wall-clock assertions in ordinary unit tests.
- [ ] Add hybrid search with document-level deduplication and measured reciprocal-rank-fusion settings.
- [ ] Add token-aware chunking when the real tokenizer is available.

ONNX Runtime with a shared model (for example `bge-small-en-v1.5`, 384 dimensions) is the candidate for portable vectors across platforms, not a required default. Platform-native (Apple `NLContextualEmbedding`) and remote models may be separate providers with distinct identities. Do not impose a model download on lexical-only users.

**Exit criterion:** semantic retrieval improves a representative evaluation corpus and has explicit model compatibility and resource costs.

## Later capabilities

### Encryption

Implement encryption after the format has reserved its required structure and before promoting kvid for sensitive-data use. Select a maintained cross-platform cryptographic implementation; specify key provisioning, password derivation if supported, authenticated coverage of documents and indexes, nonce lifecycle, and key rotation. Checksums detect accidental corruption; they do not provide authentication. If SQLite is chosen, evaluate whole-file encryption of the exported snapshot versus an encrypted-database build.

### Video archive/export

Move the existing QR/MP4 pipeline into optional `kvid-video`. Repair portable compression, Android codec buffering/plane handling, iOS pixel conversion/resource lifetimes, and genuine MP4 encoding as part of this work (Appendix A).

Require exact byte recovery, payload checksums, explicit missing-frame errors, and cross-platform fixtures. Include chunk sequencing and reconstruction; decoding QR strings alone is not a complete document import. Derive frame size from QR version (the current 256 px frames hold under two pixels per module at version 30). Test codec/quality configurations before describing export as a backup. Video export is codec-dependent.

### Backup and synchronization

Document consistent snapshot export and interrupted-compaction recovery early. Treat multi-device sync as a separate design with stable identifiers, conflict resolution, merge behavior, and deletion propagation. An append-only file is not by itself a synchronization protocol.

### Developer tools and additional media

Expand a JVM CLI when the API stabilizes: `create`, `put`, `get`, `find`, `stats`, `verify`, `export`, and `import`; add history/compaction commands with those features. Add Markdown import/export, blobs, image embeddings (MobileCLIP-class models are designed for phones), and natural-language date parsing based on actual demand.

### AI integrations, last

Optional context assembly with citations and a token budget can build on search. MCP, Koog, LangChain4j, and structured memory cards follow demonstrated integration needs. The core store must remain useful without an LLM or network connection.

## Module boundaries

Begin with logical boundaries and split published artifacts when dependencies or consumers require it:

| Module | Responsibility |
|---|---|
| `kvid-core` | Documents, persistence contract, storage, lifecycle, recovery |
| `kvid-search` | Standalone lexical/vector indexes and ranking (only if the custom-store path is chosen; with SQLite most of this is FTS5) |
| `kvid-text` | Shared text normalization, tokenization, chunking; extract when useful |
| `kvid-sample` | Android/iOS proof of the library |
| Optional providers | Embedding runtimes and remote APIs |
| Later tools | Video export, CLI, and AI adapters |

Keep store-specific version visibility and transaction rules out of standalone search components. Avoid creating all proposed modules before they have implementations or a clear dependency-isolation benefit.

## Decisions still open

1. Storage engine (SQLite versus custom) and the exact meaning of single-file operation.
2. Durability boundary, transaction API, and reader model.
3. Document identity, history retention, and historical-search guarantees.
4. Format versioning, compression, encryption structure, and compaction strategy.
5. Error API: typed exceptions or `Result`, with cancellation preserved in either case (cancellation is now preserved in the existing `Result` style).
6. Verified toolchain versions and supported platform minimums (minSdk 21 today; AGP 9.4 imposes no higher floor).
7. Representative workloads and performance budgets.
8. Whether `.kvid` names a custom format or an application container over another engine.

## Appendix A: investigation checklist from the code review

Findings from reading the code. Items marked "confirmed" were reproduced by a test on this branch; the rest are unconfirmed on devices.

Core (commonMain):

- [x] confirmed and fixed: `HnswVectorIndex.search` returned candidates in visit order, not by distance.
- [ ] `HnswVectorIndex` recomputes the farthest element of the result set with `maxByOrNull` inside the inner loop and re-sorts the candidate list on every insert (`SemanticEmbedding.kt`); 500 inserts need a five-minute timeout on the iOS simulator.
- [ ] `MemoryEncoder` hard-codes QR version 30 into 256 px frames; no test proves this decodes after H.264 compression.
- [ ] `JvmVideoDecoder` extracts every frame to disk even when a subset is requested.

Android (`AndroidVideoEncoder.kt`):

- [ ] `addFrame` never drains output buffers; once the codec's input pool fills, frames are dropped while returning success, and `finalize` loops waiting for an end-of-stream it never queued.
- [ ] RGB is converted to planar I420 while the codec is configured for semi-planar NV12.
- [ ] The decoder ignores stride, slice height and the real output colour format; it also re-queues end-of-stream every iteration and accumulates every frame in memory.
- [ ] The QR decoder never calls `TextCompression.decompress`; there is no Android QR generator at all.
- [ ] Re-`initialize` leaks the previous `MediaCodec`; `encodingTimeMs` is measured from construction, not initialization.

iOS:

- [x] confirmed by CI (three runs): `IosQRCodeGenerator.generateQRCode` throws `Failed to create CGImage from QR code` for every input on GitHub's macOS simulator runner, with both the default `CIContext` and `kCIContextUseSoftwareRenderer`. Not yet reproduced on a local Mac. Recommended Phase 1 fix regardless: a pure-Kotlin QR encoder in `commonMain` (a port of Nayuki's qrcodegen, or the `qrose` KMP library) so generation is byte-identical on every platform and depends on neither Core Image nor ZXing; decoding stays platform-specific (ZXing, Vision).
- [x] documented by an `@Ignore`d round-trip test: `IosQRCodeDecoder` passes raw pixels to `CIImage.imageWithData`, which expects an encoded image.
- [ ] compiler warning, not yet confirmed at runtime: six `String as NSString` casts in `IosHnswVectorIndex.kt` are flagged "This cast can never succeed". Kotlin/Native often bridges these at runtime, so iOS index persistence needs an actual save/load test on the simulator before this is called a bug.
- [ ] `IosTextCompression` uses Apple's raw-DEFLATE `zlib` algorithm under a `GZ:` prefix; JVM/Android gzip payloads cannot be read on iOS and vice versa.
- [ ] `IosVideoEncoder` writes a custom raw-RGB container, not MP4; `IosVideoDecoder` cannot read it, emits BGRA labelled RGB_888, skips frame-number increments on `continue`, and hard-codes H.264 in `getVideoInfo`.
- [ ] CoreFoundation objects (`CGImage`, colour spaces, every `copyNextSampleBuffer`) are never released.
- [ ] `IosQRCodeGenerator` ignores the requested version and reports it as honoured.

Build and examples:

- [ ] `PersistenceExample` looks for an index filename the writer never produces; `PerformanceBenchmark.benchmarkEndToEnd` reuses one encoder across sizes without clearing it; the advanced benchmarks run parameter sweeps that are impractical with the current HNSW.

## Appendix B: memvid background

kvid began as a port of memvid v1 (Python; text chunks, gzip, QR codes, MP4 frames, FAISS index). memvid deprecated that design in January 2026 and rewrote the project in Rust as v2: a single-file `.mv2` store with an embedded write-ahead log, append-only checksummed frames, a Tantivy BM25 index, an HNSW index, a time index, and a TOC footer; hybrid search fused with reciprocal rank fusion; as-of queries; optional encryption. The stated reasons for abandoning v1 were slow QR decoding, codec behaviour that varied by platform, no crash recovery, vector-only search, and a Python implementation that could not be embedded. These facts come from the memvid repository at commit e6bd9f7 (2026-07-14), its `MV2_SPEC.md`, and package-registry metadata; they are background for the architecture decision, not requirements. Byte compatibility with `.mv2` is not a goal: its index segments are Rust-library layouts and its spec and code disagree in several places. An importer that reads `.mv2` frames and rebuilds kvid indexes can be considered later if users need it.

## Reference notes

- [SQLite single-file portability](https://www.sqlite.org/onefile.html), [atomic commit](https://www.sqlite.org/atomiccommit.html), and [WAL behavior](https://www.sqlite.org/wal.html) inform the storage comparison; runtime journals differ from a closed portable artifact.
- [kotlinx-io buffered I/O](https://kotlinlang.org/api/kotlinx-io/kotlinx-io-core/kotlinx.io/-sink/) informs common I/O usage if the custom-store path is chosen; platform durability requirements must be established separately.
- [Android Gradle Library Plugin for KMP](https://developer.android.com/kotlin/multiplatform/plugin) documents the `android {}` block used in `kvid-core/build.gradle.kts`.
