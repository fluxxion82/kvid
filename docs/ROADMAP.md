# kvid roadmap

_Written October 2026 after reviewing kvid (this repo, last commit "Add text compression") and memvid (github.com/memvid/memvid at commit e6bd9f7, 2026-07-14, plus the v0.1.3 Python tag)._

## 1. Where things stand

### 1.1 memvid has moved on from the design kvid copied

kvid is a port of **memvid v1**: text chunks, gzip, QR codes, MP4 frames, a FAISS-style vector index on the side. memvid deprecated that design in January 2026 and rewrote the project in Rust as **v2**. The README now says, verbatim: "Memvid v1 (QR-based memory) is deprecated. If you are referencing QR codes, you are using outdated information."

The v2 design is a **single-file `.mv2` memory** (header, embedded write-ahead log, append-only "Smart Frames", BM25 index, HNSW index, time index, TOC footer). The stated reasons for abandoning v1 are worth taking seriously, because every one of them bites harder on a phone than on a server:

| Reason memvid gave for dropping v1 | How it applies to kvid |
|---|---|
| Decoding QR codes from video frames is slow compared to reading bytes | kvid shells out to ffmpeg, writes PPM files to disk, then runs ZXing per frame |
| The format depended on video codec behaviour, which varies by platform | kvid has three different encoders (ffmpeg, MediaCodec, a custom iOS container) that cannot read each other's output today |
| No crash recovery; a crash mid-write corrupts the file | kvid has no durable store at all; text lives only in the MP4, vectors in a CSV |
| Search was vector-only, no full-text | Same in kvid, and the default embedding is not semantic (see 1.3) |
| Python could not be embedded in other languages | Kotlin Multiplatform actually solves this one, which is kvid's real opportunity |

memvid v2 has **no mobile story at all**: it targets macOS, Linux and Windows only, depends on Tantivy, ONNX Runtime and mmap, and has an open issue asking for a C FFI. A Kotlin Multiplatform implementation of the v2 ideas for Android, iOS and JVM is a genuinely empty niche.

### 1.2 kvid platform parity today

Only the JVM can do the full encode, MP4, decode, text round trip, and even that path is untested end to end. The README claims at `README.md:10` and `README.md:245` ("production-ready on Android and iOS") are not true.

| Capability | JVM | Android | iOS |
|---|---|---|---|
| QR generate | ZXing, works | **Missing** (no `AndroidQRCodeGenerator`, so `MemoryEncoder` cannot be built) | CIFilter, works, ignores the requested version |
| QR decode | ZXing, works | ZXing, but never decompresses `GZ:` payloads | **Broken**: raw pixels fed to `CIImage.imageWithData`, which expects PNG/JPEG (`IosQRCodeDecoder.kt:99`) |
| Video encode | ffmpeg subprocess via PPM temp files | MediaCodec, **hangs** past a few frames (see 1.3) | **Stub**: writes a custom raw-RGB "KVID" container, not MP4 (`IosVideoEncoder.kt:145`) |
| Video decode | ffmpeg extracts every frame to disk even when a subset is requested | MediaCodec, assumes packed I420 with no stride | AVAssetReader, emits BGRA labelled as RGB_888 |
| Compression | gzip | gzip (byte-for-byte copy of JVM) | **raw DEFLATE** (`IosTextCompression.kt:20`); JVM/Android files cannot be read on iOS |
| Vector index persistence | CSV text file | CSV (copy of JVM) | CSV via NSString |

### 1.3 Concrete defects in the current code

Core (commonMain):

- `SimpleEmbedding` is not semantic. It sums character codes into 384 positional buckets (`SemanticEmbedding.kt:95-111`), so "semantic search" in the README is overstated. `MemoryStoreTest` sidesteps it with a keyword embedding.
- `HnswVectorIndex` recomputes the farthest element of the result set with `maxByOrNull` inside the inner loop and re-sorts the candidate list on every insert (`SemanticEmbedding.kt:519-551`). The iOS test needs a five-minute timeout for 500 vectors. It also persists as CSV text.
- `MemoryStore` keeps chunks and metadata only in memory; "persistence" saves the vector index but not the text or metadata, so a reloaded index has nothing to return.
- `exportIndex` builds JSON by hand with escaping that misses backslashes and newlines (`MemoryStore.kt:134-140`) because the serialization compiler plugin was never applied, so `@Serializable` is inert.
- Every suspend function wraps its body in `try { } catch (e: Exception) { Result.failure(e) }`, which swallows `CancellationException` and breaks structured concurrency.
- `MemoryEncoder` hard-codes QR version 30 (137 modules) into 256 px frames (`MemoryEncoder.kt:91-96`, `VideoEncoder.kt:31-36`), under two pixels per module before chroma subsampling and CRF 28 compression. No test proves this decodes. Frame size should be derived from the QR version.

Android (`AndroidVideoEncoder.kt`):

- `addFrame` never drains output buffers; once the codec's input pool fills, frames are silently dropped while returning success (lines 165-184), and `finalize` then spins forever waiting for an end-of-stream flag it never queued (lines 214-237).
- RGB is converted to planar I420 while the codec is configured for semi-planar NV12.
- The decoder ignores stride, slice height and the real output colour format.
- The QR decoder never calls `TextCompression.decompress`.

iOS:

- CoreFoundation objects (`CGImage`, colour spaces, every `copyNextSampleBuffer`) are never released.
- `IosVideoDecoder` skips frame-number increments on `continue`, shifting indices.
- `IosQRCodeDecoder` and `IosVideoEncoder` cannot round-trip with anything.

Build, tests, examples:

- `.gitignore:4` ignores `*.jar`, so `gradle/wrapper/gradle-wrapper.jar` is not committed and a fresh clone cannot run `./gradlew` at all.
- Kotlin is pinned to `2.3.0-RC` (`gradle/libs.versions.toml:2`) backed by the JetBrains dev repo, plus `mavenLocal()` and four unrelated repositories.
- `kotlinx-benchmark` is applied with zero benchmarks, `maven-publish` with no publication, `appcompat` for one extension function.
- No CI. ffmpeg-dependent JVM tests `println` and return, so a machine without ffmpeg is green with zero video coverage. No `testInstrumentationRunner` is set, so instrumented tests do not run.
- `kvid-examples` does not compile: `fun main(args)` is declared in four files in the same package.
- Many tests are tautological (assert `100 < 10000`, assert a masked byte is in 0..255, `assertNotNull` on a non-null `Result`). Nothing anywhere tests `MemoryDecoder` or a video round trip.

## 2. Recommendation

**Reframe kvid as "memvid v2 for Kotlin and mobile", and demote the QR-in-video pipeline to an optional export format.**

Reasons:

1. The original is gone. memvid's own authors found the QR approach too slow, too codec-dependent and unrecoverable. Porting a design its authors abandoned is a dead end, especially when the port is not yet working on two of three platforms.
2. The video pipeline is the most expensive, least portable part of kvid and provides none of the value (search, recall, persistence). The hard parts of memvid v2 (append-only frames, WAL, BM25, HNSW, RRF fusion, time travel) are pure algorithms and byte formats, which is exactly what Kotlin common code is good at.
3. Nobody serves mobile. On-device memory for assistants is a real need (Apple Intelligence, Gemini Nano, llama.cpp on phones), and a single encrypted file per user that syncs through iCloud or Drive is a natural product shape.
4. The video idea is still a fun demo and a legitimate archive format ("print your memory to a video"). It survives as a `kvid-video` module with `export`/`import`, where its codec variance no longer threatens the primary data.

If you prefer to keep kvid as a faithful v1 port for its own sake, Phases 0 and 1 below still apply unchanged, and Phase 2 onward becomes optional.

## 3. Plan by phase

Effort is rough solo effort in weeks and assumes Phases 0 and 1 before anything else.

| Phase | Goal | Effort |
|---|---|---|
| 0 | Build, CI, honest README | 1 to 2 |
| 1 | Existing pipeline round-trips on all three platforms | 3 to 4 |
| 2 | Single-file `.kvid` format with WAL and frames | 4 to 6 |
| 3 | Real search: embeddings, BM25, hybrid, filters, time travel | 4 to 6 |
| 4 | Developer surface: API, CLI, MCP server, sample app | 3 to 4 |
| 5 | Stretch: encryption, memory cards, mv2 import, images | open |

### Phase 0: build, CI, honesty

- Add `!gradle/wrapper/gradle-wrapper.jar` to `.gitignore` and commit the jar.
- Move to Kotlin 2.4.20 (stable, September 2026) and Gradle 9.x. Upgrade AGP to 9.4.x. AGP 9 no longer allows `com.android.library` together with the KMP plugin in one subproject, so switch `kvid-core` to the `com.android.kotlin.multiplatform.library` plugin.
- Remove the Kotlin dev repo, `mavenLocal()`, the jogamp/compose/ktor/wasm repos, the `kotlinx-benchmark` plugin, `maven-publish` until there is something to publish, and `appcompat`. Apply `kotlin("plugin.serialization")`. Set `testInstrumentationRunner`.
- Delete the three duplicate `main` functions in `kvid-examples`.
- Add GitHub Actions: `ubuntu-latest` with ffmpeg installed for JVM tests, `macos-latest` for iOS simulator tests, Android unit tests on the host JVM. Make ffmpeg-dependent tests fail, not skip, when `CI` is set.
- Rewrite the README status section to say what works today (JVM only).

### Phase 1: make the existing pipeline actually work everywhere

Even if the video path becomes optional later, this is what proves the platform layers and gives you golden fixtures.

- **Portable compression.** Make iOS produce real gzip using `platform.zlib` (`deflateInit2` with windowBits 31) or switch every platform to raw DEFLATE with a new prefix. Add a fixture test: a `GZ:` string committed to the repo must decompress on all three targets.
- **Shared JVM/Android code.** Add a `jvmAndAndroid` intermediate source set. Move ZXing QR generation, the QR decoder, gzip and file persistence there, and keep only the AWT `BufferedImage` helper in `jvmMain`. This gives Android a QR generator and removes three duplicated files.
- **Android encoder.** Drain output buffers after each input, fail loudly on dropped frames, exit `finalize` on timeout, feed NV12 or use `COLOR_FormatYUV420Flexible` with `getInputImage()`. Decoder: read planes and strides via `getOutputImage()`.
- **iOS.** Build the `CGImage` for QR decoding from raw pixels with `CGImageCreate` and a data provider. Release every CF object. Convert BGRA to RGB in the decoder. Replace the custom container with `AVAssetWriter` writing H.264 MP4.
- **One real end-to-end test on JVM**: `MemoryEncoder` to MP4 to `MemoryDecoder` to `MemoryStore.search`. Commit the resulting MP4 as a fixture and decode it in `androidInstrumentedTest` and `iosTest`.
- Derive frame size from QR version (at least three or four pixels per module plus quiet zone), and encode with `-tune stillimage -g 1` as memvid v1 did.
- Delete the tautological tests listed in 1.3; they cost CI time and hide the lack of coverage.

### Phase 2: the `.kvid` single-file format

This is the heart of the pivot. Mirror memvid v2's architecture, not its bytes (see 5.1 on why byte compatibility is not worth chasing).

Layout, all little-endian, one file, no sidecars:

```
Header (4 KB): magic "KVID", format version, flags, embedding model id + dimension,
               WAL offset/size/checkpoint sequence, footer offset, header checksum
WAL (fixed region, 1 to 16 MB): [seq u64][type u8][len u32][payload][checksum]
Data segments: frames, each compressed payload + frame record
Index segments: lexical (BM25), vector (HNSW), time index
TOC footer: segment descriptors with offsets, lengths, checksums; footer checksum
```

Frame record (memvid's "Smart Frame"): monotonic `frameId`, `timestamp`, `uri`, `title`, `tags`, `labels`, `metadata` map, `checksum` of the payload, `encoding` (plain or deflate), `role` (document or chunk), `parentId`, `chunkIndex`/`chunkCount`, `status` (active or tombstone), `supersedes`.

Operations: `put` appends a WAL record; `commit` writes pending frames into a data segment, updates indices, rewrites the TOC footer; `open` scans for the last valid footer and replays WAL records after the checkpoint; `delete` writes a tombstone; `correct` writes a new frame with `supersedes`; `vacuum` rewrites without tombstones; `verify` recomputes checksums.

Implementation notes:

- Use `kotlinx-io` for file access in common code so there is a single implementation of the format. Only paths differ per platform.
- Checksums: CRC32 is enough for integrity and exists on all targets; use SHA-256 for payload hashes via `expect`/`actual` (JCA on JVM/Android, CommonCrypto on iOS).
- Compression: the existing `compressBytes`/`decompressBytes` pair, once Phase 1 makes it portable. zstd can come later via `zstd-jni` and a CocoaPod if size matters.
- Replace `Result`-everywhere with a `KvidException` hierarchy carrying error codes (memvid uses `MV001` style codes), and stop catching `Exception` in suspend functions.
- Single writer: take an advisory lock (file lock on JVM/Android, `flock` on iOS) and fail with a clear error, as memvid does.

### Phase 3: search that holds up

- **Real embeddings.** Define the embedding interface with a model id and dimension that are written into the file header so two files or two platforms cannot silently mix models (memvid's `ModelMismatch`). Ship:
  - `kvid-embeddings-onnx` for JVM and Android using ONNX Runtime with `bge-small-en-v1.5` (384 dimensions, memvid's default, ~120 MB). Implement the BERT WordPiece tokenizer in common Kotlin (about 200 lines) rather than depending on a native tokenizer library.
  - iOS via ONNX Runtime's Objective-C pod with the same model, or Core ML conversion of the same model. Apple's `NLContextualEmbedding` is a zero-dependency option but produces a different model, so treat it as its own model id.
  - `kvid-embeddings-remote` using Ktor for OpenAI/Voyage-style APIs for apps that prefer cloud.
- **BM25 lexical index** in common code: inverted index with positions, Unicode tokenizer, optional stemmer, persisted as a segment. memvid uses Tantivy for this; Kotlin needs a small hand-written one, which is fine at mobile scale.
- **Hybrid search with Reciprocal Rank Fusion**, k = 60, merged by frame id, exactly as memvid's `fuse_hits_rrf`. Modes `lex`, `sem`, `hybrid`, and an adaptive cut-off (score cliff or elbow) as in memvid's `search_adaptive`.
- **Rewrite HNSW**: contiguous `FloatArray` storage, binary heaps for candidates and results, heuristic neighbour selection, binary persistence. Add a recall test against the flat index (recall@10 at or above 0.95 on a few thousand vectors) and a timing test that would catch the current quadratic behaviour.
- **Filters**: `since`/`until`, tag and label match, uri prefix scope, and time travel via `asOfFrame` and `asOfTs` (trivial on an append-only log with monotonic ids). Add `timeline(limit, since, until)`.
- **Context assembly**: `ask(question, contextOnly = true)` returns fused hits plus a token-budgeted context string and citations, leaving the LLM call to the app. An optional module can wire this to a local or remote model.
- Token-aware chunking once the tokenizer exists; keep the current character-based chunker as fallback.

### Phase 4: developer surface

- **API shaped like memvid's so its docs transfer**: `Kvid.create(path)`, `open`, `put`, `putMany`, `find`, `ask`, `timeline`, `stats`, `commit`, `seal`, `verify`, `delete`, `correct`.
- **CLI** (`kvid-cli`, JVM, clikt): `create`, `put`, `find`, `ask --context-only`, `timeline`, `stats`, `verify`, `export-video`, `import-video`. This is cheap and makes every feature testable from a shell.
- **MCP server** (`kvid-mcp`, JVM, the official Kotlin MCP SDK) exposing `put`/`find`/`ask`/`timeline` tools so Claude Desktop, Claude Code and Cursor can use a `.kvid` file as memory. memvid has only community MCP servers and an open issue asking for an official one.
- **Adapters**: Koog (JetBrains' Kotlin agent framework) and LangChain4j on JVM.
- **Sample app**: a Compose Multiplatform notes-with-memory demo (Android and iOS) that records text, searches, and shows the timeline. This is the proof that the mobile story is real.
- Publish `kvid-core` to Maven Central with `publishLibraryVariants("release")` for Android and an XCFramework for iOS.

### Phase 5: stretch

- **Encrypted capsules** (`.kvid` with a password): memvid uses Argon2id plus AES-256-GCM. A KMP crypto library such as cryptography-kotlin gives AES-GCM and PBKDF2 on all targets; Argon2 would need native bindings. Encryption at rest is more important on a phone than on a server, so this ranks high once the format is stable.
- **Memory cards** ("living memory"): entity, slot, value, validity time, source frame, confidence. memvid builds these with a rules engine or an LLM enrichment pass and queries `getCurrentMemory(entity, slot)` and `getMemoryAtTime`. Excellent fit for assistant preferences on device.
- **Import from `.mv2`**: the data segments (zstd + BLAKE3) are documented and readable; the Tantivy and HNSW segments are opaque, so an importer would read frames and rebuild indices. Experimental only; the format is revised weekly and the spec already disagrees with the code in several places.
- **Images**: memvid uses MobileCLIP-S2, which was designed for phones. A `kvid-embeddings-clip` module for photo memory is a natural mobile feature.
- **Replay sessions** (record and replay put/find/ask sequences with another model): niche, skip unless needed.

## 4. memvid features ranked for kvid

| Feature | Mimic? | Notes |
|---|---|---|
| Single-file store with WAL and crash recovery | Yes, first | The core of v2; solves kvid's biggest gap |
| Immutable, checksummed, append-only Smart Frames with tombstones and corrections | Yes | Simple to implement, enables everything below |
| Hybrid BM25 + vector search fused with RRF | Yes | memvid's own benchmark gains come from this |
| Time travel (`asOfFrame`, `asOfTs`, `timeline`) | Yes | Nearly free on an append-only log; great demo |
| Model id pinned in the file (`ModelMismatch`) | Yes | Prevents silent cross-platform vector mismatch |
| `ask` with `contextOnly`, citations, token budget | Yes | Keeps the LLM out of the core, app decides |
| Adaptive cut-off (score cliff, elbow) | Yes | Small, improves result quality |
| `verify` and `doctor` (integrity check, rebuild index) | Yes | Pairs with the WAL work |
| CLI with `create/put/find/ask/timeline/stats` | Yes | Cheap, makes the library usable |
| Official MCP server | Yes | memvid lacks one; easy win on JVM |
| Password-encrypted capsules | Later | High value on mobile; wait for format stability |
| Memory cards / enrichment | Later | Structured facts on top of frames |
| Natural-language date parsing ("last Tuesday") | Later | Needs a date parser; nice for assistants |
| Product quantization of vectors | Later | Only matters past ~100k vectors |
| CLIP images, Whisper audio, PDF extraction | Later | Images first (MobileCLIP is mobile-native) |
| Replay sessions | No | Niche |
| Capacity tickets, API-key tiers, telemetry | No | Commercial plumbing, not a feature |
| Byte-compatible `.mv2` | No | Tantivy segments are Rust-only; spec and code diverge; import instead |

## 5. Things to decide

1. **Identity**: "memvid v2 for KMP" (recommended) or faithful v1 port. This decides whether Phase 2 happens.
2. **File extension and name**: `.kvid` is suggested; avoid `.mv2` unless you commit to compatibility.
3. **Embedding default**: bge-small via ONNX on every platform (consistent vectors, 120 MB download) or platform-native models (no download, incompatible vectors across platforms). Recommendation: ONNX by default, platform-native as opt-in with its own model id.
4. **Error handling style**: typed exceptions (recommended) or keep `Result`. Decide before Phase 2 so the new API is consistent.
5. **Compression**: keep DEFLATE everywhere (zero dependencies) or add zstd (better ratio, native dependency on iOS).

## 6. Proposed module layout

```
kvid-core                 common: format, WAL, frames, chunker, BM25, HNSW, fusion, filters
kvid-embeddings-onnx      jvm + android: ONNX Runtime, bge-small, WordPiece tokenizer (common)
kvid-embeddings-apple     ios: ONNX pod or Core ML; NLContextualEmbedding as alternative
kvid-embeddings-remote    common: Ktor client for hosted embedding APIs
kvid-video                existing QR/MP4 code, fixed in Phase 1, exposed as export/import
kvid-cli                  jvm: clikt commands
kvid-mcp                  jvm: MCP server over stdio
kvid-sample               Compose Multiplatform demo app
```

API sketch:

```kotlin
val mem = Kvid.create("notes.kvid", embedder = OnnxEmbedder.bgeSmall())
mem.put("Met Sam about the Q4 plan", PutOptions(title = "standup", tags = listOf("work")))
mem.commit()

val hits = mem.find("Q4 planning", k = 5, mode = SearchMode.HYBRID, since = lastWeek)
val ctx  = mem.ask("What did we decide about Q4?", contextOnly = true)
val past = mem.find("plan", asOfFrame = 120)
mem.timeline(limit = 20).forEach { println("${it.frameId} ${it.timestamp} ${it.preview}") }
```
