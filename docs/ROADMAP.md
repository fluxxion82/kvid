# kvid roadmap

_Written October 2026 after reviewing kvid (this repo) and memvid (github.com/memvid/memvid at commit e6bd9f7, 2026-07-14, plus the v0.1.3 Python tag). Direction confirmed by the owner: build general-purpose pieces first, AI-agent integrations last; adopt current tooling including Kotlin 2.5.0-Beta1._

## 0. Direction in one paragraph

kvid becomes a **general-purpose, embedded, single-file searchable store for Kotlin Multiplatform apps**: one file holds documents, metadata, full-text and (optionally) vector indexes, with crash-safe appends, document history and encryption at rest. It is useful for a notes or journal app, offline documentation, message and chat history, logs, receipts, clippings, voice-memo transcripts, photo captions, and, as one use case among those, memory for AI assistants. memvid v2 is the architectural model (it solved the same problems well), but kvid keeps memvid's ideas, not its bytes, and keeps every building block usable on its own. The QR-in-video pipeline kvid started from survives as an optional archive/export format, not as the storage engine.

## 1. Where things stand

### 1.1 memvid has moved on from the design kvid copied

kvid is a port of **memvid v1**: text chunks, gzip, QR codes, MP4 frames, a FAISS-style vector index on the side. memvid deprecated that design in January 2026 and rewrote the project in Rust as **v2**. The README now says, verbatim: "Memvid v1 (QR-based memory) is deprecated. If you are referencing QR codes, you are using outdated information."

The v2 design is a **single-file `.mv2` store** (header, embedded write-ahead log, append-only "Smart Frames", BM25 index, HNSW index, time index, TOC footer). The stated reasons for abandoning v1 bite harder on a phone than on a server:

| Reason memvid gave for dropping v1 | How it applies to kvid |
|---|---|
| Decoding QR codes from video frames is slow compared to reading bytes | kvid shells out to ffmpeg, writes PPM files to disk, then runs ZXing per frame |
| The format depended on video codec behaviour, which varies by platform | kvid has three different encoders (ffmpeg, MediaCodec, a custom iOS container) that cannot read each other's output today |
| No crash recovery; a crash mid-write corrupts the file | kvid has no durable store at all; text lives only in the MP4, vectors in a CSV |
| Search was vector-only, no full-text | Same in kvid, and the default embedding is not semantic (see 1.3) |
| Python could not be embedded in other languages | Kotlin Multiplatform solves this one, which is kvid's real opportunity |

memvid v2 has **no mobile story**: it targets macOS, Linux and Windows only, depends on Tantivy, ONNX Runtime and mmap, and has an open issue asking for a C FFI. An embedded single-file store with search for Android, iOS and JVM is an empty niche, and it is useful far beyond agents.

### 1.2 kvid platform parity today

Only the JVM can do the full encode, MP4, decode, text round trip, and even that path is untested end to end. The README has been corrected to say so.

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

- `SimpleEmbedding` is not semantic. It sums character codes into 384 positional buckets (`SemanticEmbedding.kt:95-111`). `MemoryStoreTest` sidesteps it with a keyword embedding.
- `HnswVectorIndex` recomputes the farthest element of the result set with `maxByOrNull` inside the inner loop and re-sorts the candidate list on every insert (`SemanticEmbedding.kt:519-551`). The iOS test needs a five-minute timeout for 500 vectors. It persists as CSV text.
- `MemoryStore` keeps chunks and metadata only in memory; "persistence" saves the vector index but not the text or metadata, so a reloaded index has nothing to return.
- `exportIndex` builds JSON by hand with escaping that misses backslashes and newlines (`MemoryStore.kt:134-140`). The serialization compiler plugin is now applied, so this can be replaced with `Json.encodeToString`.
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

Tests and examples:

- ffmpeg-dependent JVM tests `println` and return, so a machine without ffmpeg is green with zero video coverage. CI now installs ffmpeg, and the guards fail rather than skip when `CI` is set.
- Many tests are tautological (assert `100 < 10000`, assert a masked byte is in 0..255, `assertNotNull` on a non-null `Result`). Nothing anywhere tests `MemoryDecoder` or a video round trip.
- `PersistenceExample` looks for an index filename the writer never produces; `PerformanceBenchmark.benchmarkEndToEnd` reuses one encoder across sizes without clearing it.

## 2. Plan by phase

### Phase 0: build, CI, honesty (done in this session except where marked)

- [x] Commit `gradle/wrapper/gradle-wrapper.jar` (it was gitignored by `*.jar`; a fresh clone could not run Gradle).
- [x] Kotlin **2.5.0-Beta1** (owner's choice), Gradle **9.8.0**, Android Gradle Plugin **9.4.0** using the **`com.android.kotlin.multiplatform.library`** plugin (AGP 9 does not allow `com.android.library` next to the KMP plugin in one subproject; the Android target is configured with `android {}` inside `kotlin {}`, and instrumented tests live in `src/androidDeviceTest`).
- [x] kotlinx-coroutines 1.11.0, kotlinx-serialization 1.11.0 with the compiler plugin applied, androidx.test 1.7.0 / 1.3.0, foojay resolver 1.0.0, JDK 17 toolchain.
- [x] Removed: Kotlin dev repo, `mavenLocal()`, jogamp/compose/ktor/wasm repos, `kotlinx-benchmark` (zero benchmarks), `maven-publish` (nothing publishable yet), `appcompat` (one extension import, replaced with `Bitmap.createBitmap`), the forced Kotlin version resolution strategy, the no-op `buildCache` block (replaced by `org.gradle.caching=true`).
- [x] Deleted the three duplicate `main` functions that made `kvid-examples` fail to compile.
- [x] GitHub Actions: Ubuntu job with ffmpeg running `build` (JVM tests + Android host tests + examples compile), macOS job running iOS simulator tests.
- [x] README prerequisites and status rewritten to match reality.
- [ ] **Unverified**: the Android plugin configuration and the iOS compile. This sandbox's proxy blocks `dl.google.com`, so neither AGP nor the Android SDK could be downloaded, and the Kotlin/Native toolchain is Apple-host only. JVM tests and the examples compile were verified on Kotlin 2.5.0-Beta1 with the Android plugin temporarily stripped. The first CI run on GitHub, or one local `./gradlew build`, settles it.
- [ ] Note on the Beta: the Kotlin Gradle plugin compatibility table is not yet published for 2.5.0-Beta1 (2.4.20 lists Gradle up to 9.7.0 and AGP up to 9.3.1 as fully supported). Expect warnings; if the Android plugin rejects the Beta, pin `kotlin = "2.4.20"` in `gradle/libs.versions.toml` until 2.5.0 ships.
- [x] ffmpeg-dependent JVM tests now fail instead of skip when `CI` is set.
- [ ] Delete the tautological tests listed in 1.3.

### Phase 1: make the existing pipeline actually work everywhere

This proves the platform layers and produces golden fixtures. It is also what makes the "archive to video" export in Phase 5 real.

- **Portable compression.** Make iOS produce real gzip using `platform.zlib` (`deflateInit2` with windowBits 31). Add a fixture test: a `GZ:` string committed to the repo must decompress on all three targets.
- **Shared JVM/Android code.** Add a `jvmAndAndroid` intermediate source set. Move ZXing QR generation, the QR decoder, gzip and file persistence there, and keep only the AWT `BufferedImage` helper in `jvmMain`. This gives Android a QR generator and removes three duplicated files.
- **Android encoder.** Drain output buffers after each input, fail loudly on dropped frames, exit `finalize` on timeout, feed NV12 or use `COLOR_FormatYUV420Flexible` with `getInputImage()`. Decoder: read planes and strides via `getOutputImage()`.
- **iOS.** Build the `CGImage` for QR decoding from raw pixels with `CGImageCreate` and a data provider. Release every CF object. Convert BGRA to RGB in the decoder. Replace the custom container with `AVAssetWriter` writing H.264 MP4.
- **One real end-to-end test on JVM**: `MemoryEncoder` to MP4 to `MemoryDecoder` to `MemoryStore.search`. Commit the resulting MP4 as a fixture and decode it in `androidDeviceTest` and `iosTest`.
- Derive frame size from QR version (at least three or four pixels per module plus quiet zone), and encode with `-tune stillimage -g 1` as memvid v1 did.

### Phase 2: the `.kvid` single-file store

The heart of the project, and the piece every other use case rests on. Mirror memvid v2's architecture, not its bytes (see 4 on why byte compatibility is not worth chasing).

Layout, all little-endian, one file, no sidecars:

```
Header (4 KB): magic "KVID", format version, flags, embedding model id + dimension,
               WAL offset/size/checkpoint sequence, footer offset, header checksum
WAL (fixed region, 1 to 16 MB): [seq u64][type u8][len u32][payload][checksum]
Data segments: frames, each compressed payload + frame record
Index segments: lexical (BM25), vector (HNSW), time index
TOC footer: segment descriptors with offsets, lengths, checksums; footer checksum
```

Frame record (memvid's "Smart Frame", but think of it as "a document version"): monotonic `frameId`, `timestamp`, `uri`, `title`, `tags`, `labels`, `metadata` map, `checksum` of the payload, `encoding` (plain or deflate), `role` (document, chunk, or blob), `parentId`, `chunkIndex`/`chunkCount`, `status` (active or tombstone), `supersedes`.

Operations: `put` appends a WAL record; `commit` writes pending frames into a data segment, updates indexes, rewrites the TOC footer; `open` scans for the last valid footer and replays WAL records after the checkpoint; `delete` writes a tombstone; `update` writes a new frame with `supersedes` (this is document history, usable by any app); `vacuum` rewrites without tombstones; `verify` recomputes checksums; `timeline` and as-of reads give "what did this file contain on Tuesday" to any app, not just agents.

Implementation notes:

- Use `kotlinx-io` for file access in common code so there is a single implementation of the format. Only paths differ per platform.
- Checksums: CRC32 for integrity; SHA-256 for payload hashes via `expect`/`actual` (JCA on JVM/Android, CommonCrypto on iOS).
- Compression: the existing `compressBytes`/`decompressBytes` pair once Phase 1 makes it portable. zstd later via `zstd-jni` and a CocoaPod if size matters.
- Replace `Result`-everywhere with a `KvidException` hierarchy carrying error codes, and stop catching `Exception` in suspend functions.
- Single writer: advisory lock (file lock on JVM/Android, `flock` on iOS) with a clear error, as memvid does.
- Blobs: allow frames whose payload is binary (attachments, thumbnails) so apps can keep small files next to their text.

### Phase 3: search that holds up, full-text first

Full-text search needs no model download and serves every use case, so it comes first; vectors are optional.

- **BM25 lexical index** in common code: inverted index with positions, Unicode tokenizer, optional stemmer, phrase and prefix queries, persisted as a segment. memvid uses Tantivy for this; Kotlin needs a small hand-written one, which is fine at the scale of one device.
- **Filters** on every query: `since`/`until`, tag and label match, uri prefix scope, `asOfFrame` and `asOfTs` (time travel), pagination cursor.
- **Rewrite HNSW**: contiguous `FloatArray` storage, binary heaps for candidates and results, heuristic neighbour selection, binary persistence. Add a recall test against the flat index (recall@10 at or above 0.95 on a few thousand vectors) and a timing test that would catch the current quadratic behaviour.
- **Real embeddings, as optional modules.** The embedding interface carries a model id and dimension that are written into the file header so two files or two platforms cannot silently mix models (memvid's `ModelMismatch`).
  - `kvid-embeddings-onnx` for JVM and Android using ONNX Runtime with `bge-small-en-v1.5` (384 dimensions, memvid's default). Implement the BERT WordPiece tokenizer in common Kotlin rather than depending on a native tokenizer library.
  - iOS via ONNX Runtime's Objective-C pod with the same model, or Core ML conversion of the same model. Apple's `NLContextualEmbedding` is a zero-dependency option but is a different model, so it gets its own model id.
  - `kvid-embeddings-remote` using Ktor for hosted embedding APIs.
- **Hybrid search with Reciprocal Rank Fusion**, k = 60, merged by frame id, exactly as memvid's `fuse_hits_rrf`. Modes `lex`, `sem`, `hybrid`, plus an adaptive cut-off (score cliff or elbow) as in memvid's `search_adaptive`.
- Token-aware chunking once the tokenizer exists; keep the character-based chunker as fallback.

### Phase 4: developer surface

- **API shaped like memvid's so its docs transfer**, but named for documents, not agents: `Kvid.create(path)`, `open`, `put`, `putMany`, `update`, `delete`, `get`, `find`, `timeline`, `stats`, `commit`, `seal`, `verify`, `vacuum`.
- **Standalone modules.** Keep the indexes independent of the store so an app can use just the full-text index over its own data: `kvid-text` (chunking, tokenizing), `kvid-search` (BM25, HNSW, fusion), `kvid-core` (store and format).
- **CLI** (`kvid-cli`, JVM, clikt 5): `create`, `put`, `find`, `get`, `timeline`, `stats`, `verify`, `vacuum`, `export`, `import`. Cheap, and makes every feature testable from a shell.
- **Import/export** in plain formats: JSON Lines and a folder of Markdown files with front matter, so data is never locked in.
- **Sample app**: a Compose Multiplatform notes app (Android and iOS) that writes, searches, filters by tag and date, and shows document history. This is the proof that the mobile story is real.
- Publish `kvid-core` to Maven Central with an XCFramework for iOS.

### Phase 5: broader features

- **Encrypted files** (password or key): memvid uses Argon2id plus AES-256-GCM. A KMP crypto library such as cryptography-kotlin gives AES-GCM and PBKDF2 on all targets; Argon2 would need native bindings. Encryption at rest matters more on a phone than on a server.
- **Video archive export** (`kvid export --video`, `kvid import --video`): the Phase 1 QR/MP4 pipeline, repackaged as an optional `kvid-video` module. A fun, codec-independent "print your data to a video" backup, and the project's origin story.
- **Sync-friendliness**: document how a single append-mostly file behaves under iCloud Drive and Android backup; consider a compaction mode that keeps the file small for sync.
- **Images**: memvid uses MobileCLIP-S2, which was designed for phones. A `kvid-embeddings-clip` module for photo search is a natural mobile feature.
- **Natural-language dates** ("last Tuesday") as a query filter, useful for any timeline UI.

### Phase 6: AI integrations (last, and optional)

- `ask(question, contextOnly = true)` returning fused hits plus a token-budgeted context string and citations, leaving the LLM call to the app.
- MCP server (`kvid-mcp`, JVM, the official Kotlin MCP SDK) so Claude Desktop, Claude Code and Cursor can use a `.kvid` file. memvid has only community MCP servers.
- Koog and LangChain4j adapters on JVM.
- Memory cards (entity, slot, value, validity time) as a structured layer on top of frames, if there is demand.

## 3. memvid features ranked for kvid

| Feature | Mimic? | Notes |
|---|---|---|
| Single-file store with WAL and crash recovery | Yes, first | The core of v2; solves kvid's biggest gap; general-purpose |
| Immutable, checksummed, append-only frames with tombstones and supersedes | Yes | Document history for any app |
| BM25 full-text search | Yes | No model needed; serves every use case |
| Time travel (`asOfFrame`, `asOfTs`, `timeline`) | Yes | Nearly free on an append-only log |
| Metadata, tag, label and date filters with cursors | Yes | Every list screen needs these |
| `verify` and `doctor` (integrity check, rebuild index) | Yes | Pairs with the WAL work |
| CLI with `create/put/find/timeline/stats` | Yes | Cheap, makes the library usable |
| Hybrid search with RRF | Yes, optional module | Only when an embedding model is configured |
| Model id pinned in the file (`ModelMismatch`) | Yes | Prevents silent cross-platform vector mismatch |
| Adaptive cut-off (score cliff, elbow) | Yes | Small, improves result quality |
| Password-encrypted files | Yes, Phase 5 | High value on mobile |
| `ask` with context assembly and citations | Later, Phase 6 | AI-specific |
| Official MCP server | Later, Phase 6 | AI-specific |
| Memory cards / enrichment | Later, Phase 6 | AI-specific |
| Natural-language date parsing | Later | Useful for timelines generally |
| Product quantization of vectors | Later | Only matters past ~100k vectors |
| CLIP images, Whisper audio, PDF extraction | Later | Images first (MobileCLIP is mobile-native) |
| Replay sessions | No | Niche |
| Capacity tickets, API-key tiers, telemetry | No | Commercial plumbing |
| Byte-compatible `.mv2` | No | Tantivy segments are Rust-only; spec and code diverge; an importer is possible later |

## 4. Why not just read `.mv2` files

memvid's data segments (zstd payloads with BLAKE3 checksums and a documented TOC) are readable, but its lexical index is a Tantivy directory and its vector index is a Rust crate's layout, both opaque. The spec also disagrees with the code in several places (CRC32/SHA-256 in the spec, BLAKE3 in code; LZ4 listed, only zstd implemented; different HNSW parameters), and releases ship weekly. An importer that reads frames and rebuilds kvid's own indexes is feasible later; byte compatibility is not a goal.

## 5. Decisions already made, and ones still open

Made:

- General-purpose first; AI integrations last.
- Kotlin 2.5.0-Beta1, Gradle 9.8, AGP 9.4, JDK 17 toolchain.
- The QR/video path is an export format, not the storage engine.

Open:

1. **File extension and name**: `.kvid` is suggested.
2. **Embedding default**: bge-small via ONNX on every platform (consistent vectors, 120 MB download) or platform-native models (no download, incompatible vectors across platforms). Suggested: ONNX by default, platform-native as opt-in with its own model id.
3. **Error handling style**: typed exceptions (suggested) or keep `Result`. Decide before Phase 2.
4. **Compression**: DEFLATE everywhere (zero dependencies) or add zstd.
5. **Second opinion**: a Codex review of this plan was requested but could not run from the cloud sandbox (no CLI, no credentials, `api.openai.com` blocked by the environment's network policy). See the session notes for how to enable it.

## 6. Proposed module layout

```
kvid-core                 common: file format, WAL, frames, store API
kvid-search               common: BM25, HNSW, fusion, filters (no dependency on the store)
kvid-text                 common: chunking, tokenizers
kvid-embeddings-onnx      jvm + android: ONNX Runtime, bge-small, WordPiece tokenizer
kvid-embeddings-apple     ios: ONNX pod or Core ML; NLContextualEmbedding as alternative
kvid-embeddings-remote    common: Ktor client for hosted embedding APIs
kvid-video                existing QR/MP4 code, fixed in Phase 1, exposed as export/import
kvid-cli                  jvm: clikt commands
kvid-sample               Compose Multiplatform notes app
kvid-mcp                  jvm: MCP server (Phase 6)
```

API sketch:

```kotlin
val db = Kvid.create("notes.kvid")
val id = db.put("Met Sam about the Q4 plan", PutOptions(title = "standup", tags = listOf("work")))
db.update(id, "Met Sam and Priya about the Q4 plan")      // new frame, supersedes the old one
db.commit()

val hits    = db.find("Q4 plan", k = 5, since = lastWeek, tags = listOf("work"))
val history = db.timeline(uri = hits.first().uri)
val before  = db.find("Q4 plan", asOfTs = yesterday)
db.verify()
```
