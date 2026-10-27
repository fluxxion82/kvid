# kvid

kvid is an embedded, searchable document store for Kotlin Multiplatform apps on Android, iOS and the JVM. A store is one portable SQLite file holding your documents, their metadata and tags, every retained version, and an offline full-text index.

It started as a Kotlin port of [memvid](https://github.com/memvid/memvid). memvid has since moved from QR codes in video to a single-file memory format; kvid follows that direction for general-purpose use (notes, journals, offline documentation, transcripts, message history), with AI integrations optional and last.

## What the store provides

- Durable writes: every write commits atomically and survives process death; scoped `transaction { }` blocks group writes.
- Versioned documents: updates and deletes keep history; `get(id, asOfSeq)` reads a document as it was.
- Offline full-text search with BM25 ranking, snippets, Unicode case and accent folding, and plain-text queries by default. `FindOptions(prefixLastTerm = true)` lets the last word match as a prefix for search while typing.
- Filters shared by listing and search: event-time range, tags (all required) and uri prefix; tag counts for filter UIs.
- Optional semantic and hybrid search: plug in an `Embedder`, and the store records the model's identity, keeps vectors as derived data, refuses vectors from a different model, and fuses lexical and vector rankings. No model ships with the core library.
- Portable files: `snapshot()` publishes a consistent, verified copy; `verify()` checks the whole store; JSON Lines export and import.
- Typed failures (`KvidException` with stable codes) and enforced size bounds.

## Quick start

```kotlin
import com.kvid.store.*

suspend fun notes(path: String) = Kvid.create(path).use { store ->
    val id = store.put(
        "Budget review on Thursday; draft the plan for next quarter.",
        PutOptions(title = "Q4 planning", tags = listOf("work"))
    )
    store.update(id, "Budget review moved to Friday.", PutOptions(title = "Q4 planning", tags = listOf("work")))

    val hits = store.find("budget friday", FindOptions(tags = listOf("work")))
    hits.items.forEach { println("${it.document.title}: ${it.snippet}") }

    println(store.history(id).size)          // 2 versions
    store.snapshot("$path.backup")           // consistent copy for backup or sharing
}
```

Open an existing file with `Kvid.open(path)`, or `Kvid.openReadOnly(path)` alongside a writer. A process holds one writable handle per file; own it at application scope and close it when that owner goes away. The `Kvid` class documentation describes the lifecycle on Android and iOS.

## Semantic search with kvid-embed-onnx

`kvid-embed-onnx` is an optional module that runs [`BAAI/bge-small-en-v1.5`](https://huggingface.co/BAAI/bge-small-en-v1.5) with ONNX Runtime 1.30.0 on the JVM, Android and iOS (arm64 device and arm64 simulator). `OnnxEmbedder` is declared in common code, so shared code opens it on every platform. Lexical-only apps do not depend on it. It is not published yet.

```kotlin
OnnxEmbedder.open(modelPath = "…/model.onnx", vocabPath = "…/vocab.txt").use { embedder ->
    store.indexVectors(embedder)
    val similar = store.findSimilar("quarterly budget", embedder)
}
```

- The app supplies `onnx/model.onnx` (about 133 MB, fp32) and `vocab.txt` from the model repository, bundled or downloaded. The embedder records SHA-256 digests of both in the store's `EmbeddingSpec`, so different files count as a different model. `kvid-embed-onnx/scripts/model-pins.json` lists the revision and digests the tests use.
- Inputs longer than 512 tokens are truncated.
- Memory: ONNX Runtime keeps its peak working memory (about 460 MB in total on Android) while the embedder is open; close it after bulk indexing. A process that must keep it open with less memory can pass `OnnxOptions(cpuMemoryArena = false)`, which returns to about 220 MB after each call but made inference 1.6 to 1.9 times slower on a Pixel 8.
- Android: the module requires API 24, the minimum of `onnxruntime-android`. The ONNX Runtime AAR registers a telemetry `ContentProvider` (`ai.onnxruntime.TelemetryInitializer`) that starts at app launch; this module's manifest removes it, and `open` disables ONNX Runtime telemetry. An app that also declares `onnxruntime-android` directly should remove it in its own manifest:

  ```xml
  <provider android:name="ai.onnxruntime.TelemetryInitializer" tools:node="remove" />
  ```

  The AAR also adds the `INTERNET` and `ACCESS_NETWORK_STATE` permissions; the embedder itself never uses the network. Remove them with `tools:node="remove"` if your app does not need them.
- iOS: the module requires iOS 15.1, the minimum of ONNX Runtime's iOS build. The build downloads Microsoft's prebuilt `onnxruntime-c` 1.30.0 archive at a pinned URL and SHA-256 (`kvid-embed-onnx/scripts/runtime-pins.json`) into `~/.cache/kvid/runtime` and links it statically into every Kotlin/Native binary of the module; nothing comes from CocoaPods. ONNX Runtime's iOS build contains Microsoft's telemetry client. Before kvid first calls ONNX Runtime in a process, it sets the environment variable `ORT_DISABLE_TELEMETRY=1`, and it disables telemetry events through the C API. The variable affects the whole process. A value the app already set that ONNX Runtime treats as true (`1`, `true`, `yes`, `on` or `y`) is kept; any other value, including `0`, `false` or an empty one, is replaced with `1`, because the C API call alone does not stop ONNX Runtime from uploading its process-information event (checked with a network probe). Another component that initializes ONNX Runtime earlier in the same process is not covered; set `ORT_DISABLE_TELEMETRY=1` at launch to cover it. A Swift app uses the embedder through the `KvidEmbed` XCFramework (see Packaging, Swift).

## Notes sample

`kvid-sample` is a Compose Multiplatform notes app built on the store: list, search while typing, tag chips, edit, delete, version history and backup. It closes the store whenever the app leaves the foreground.

```bash
./gradlew :kvid-sample:shared:run                  # desktop
./gradlew :kvid-sample:androidApp:installDebug     # Android device or emulator
cd kvid-sample/iosApp && xcodegen generate         # then open KvidNotes.xcodeproj in Xcode
```

## Platform support and evidence

CI runs the store tests on the JVM, on an Android API 35 emulator and on the iOS simulator, plus a JVM test that kills a child process mid-transaction. It also builds and launches the sample on the emulator and the simulator, and checks that the store file is created, closed cleanly in the background and, on Android, recovered after the process is killed.

| Target | Minimum | Notes |
|---|---|---|
| JVM | Java 17 | |
| Android | API 23 | Runtime checked on API 35 only |
| iOS | arm64 device and arm64 simulator | Intel simulators are not supported by the bundled SQLite. The notes sample requires iOS 18.5 |

The store format (0.1, schema 2) is pre-release: files from unreleased schema 1 builds are refused, and the format may still change before the first release.

## Build and test

Requires JDK 17, the Android SDK, and Xcode on macOS for iOS.

```bash
./gradlew build                                    # JVM and Android host build and tests, sample included
./gradlew :kvid-core:iosSimulatorArm64Test         # iOS simulator tests (macOS)
./gradlew :kvid-core:connectedAndroidDeviceTest    # store tests on a connected device or emulator
```

`scripts/check-local.sh` runs what CI runs, as far as the machine allows: the JVM build, the tokenizer and embedding parity fixtures (it downloads the pinned model into `~/.cache/kvid/models`), the iOS simulator tests on macOS, and the Android device tests when one device is connected. It reports each part as ran, skipped or failed; run it before pushing.

Toolchain: Kotlin 2.5.0-Beta1, Gradle 9.8, Android Gradle Plugin 9.4, Compose Multiplatform 1.12.1.

## Packaging

kvid is not on Maven Central yet. The build stages unsigned Maven artifacts and an XCFramework. CI checks packaging and compiles a separate Kotlin consumer. A Central release still needs namespace verification and signing configuration.

**Kotlin.** `kvid-core` publishes one Kotlin Multiplatform module with JVM, Android, iOS arm64 and iOS simulator arm64 variants:

```bash
./gradlew :kvid-core:publishToMavenLocal                         # then depend on com.sterlingalbury:kvid-core:0.1.0
./gradlew :kvid-core:publishAllPublicationsToStagingRepository   # release bundle in kvid-core/build/staging-repo
```

```kotlin
// build.gradle.kts of a Kotlin Multiplatform or Android project, with mavenLocal() in its repositories
commonMain.dependencies { implementation("com.sterlingalbury:kvid-core:0.1.0") }
```

**Swift.** `./gradlew :kvid-core:assembleKvidCoreReleaseXCFramework` writes `kvid-core/build/XCFrameworks/release/KvidCore.xcframework` with device and simulator slices. Add it to an Xcode target and `import KvidCore`. The Swift API is Kotlin's Objective-C export, so suspend functions become `async` methods.

**Swift with embeddings.** `./gradlew :kvid-embed-onnx-ios-framework:assembleKvidEmbedReleaseXCFramework` writes `kvid-embed-onnx-ios-framework/build/XCFrameworks/release/KvidEmbed.xcframework` (unpublished) and ONNX Runtime's MIT licence next to it as `onnxruntime-LICENSE`; include it in the app's notices. `KvidEmbed` is one static framework that contains ONNX Runtime 1.30.0, kvid-core with its SQLite and one Kotlin runtime, and exports both the `kvid-core` and the `kvid-embed-onnx` API. What a Swift app adds:

- `KvidEmbed.xcframework` in the target's "Frameworks, Libraries, and Embedded Content" with **Do Not Embed** (it is static), and `import KvidEmbed`. It replaces `KvidCore`: never link both, which would put two Kotlin runtimes and two SQLite copies in the app.
- A deployment target of iOS 15.1 or later.
- Nothing else: no CocoaPods and no separate `onnxruntime.xcframework`. The framework's objects carry autolink entries for `libc++` and the system frameworks. A build that turns autolinking off (`-Xlinker -no_autolink`) adds `-lc++ -framework Foundation -framework CoreFoundation -framework Network -framework Security -framework SystemConfiguration -framework UIKit -weak_framework CoreML` to Other Linker Flags.

Kotlin's export has no default arguments, so Swift passes the model and options: `try await OnnxEmbedder.companion.open(modelPath:vocabPath:model: BertEmbeddingModel.companion.BGE_SMALL_EN_V1_5, options: OnnxOptions(intraOpThreads: nil, maxBatchSize: 4, sortByLength: true, cpuMemoryArena: true, memoryPattern: true))`. CI links `kvid-embed-onnx-ios-framework/swift-check/main.swift` against a copy of the framework for the simulator and the device, checks the deployment target and the link map, and runs it on a simulator (`scripts/check-local.sh --only ios-framework`).

The Maven group is `com.sterlingalbury`; the Kotlin packages stay `com.kvid.*`. Before a Maven Central release, the namespace must be verified through Central's DNS TXT record for `sterlingalbury.com`, and publications must be signed.

## Experimental APIs and migration

The original QR-code video pipeline in package `com.kvid.core` (`MemoryStore`, `MemoryEncoder`, `MemoryDecoder`, the QR generators and decoders, the video encoders and decoders, `TextChunker`, `SimpleEmbedding` and the in-memory vector indexes) is experimental. It is planned to move into an optional `kvid-video` module and may change or be removed.

- Its artifacts are not compatible with `.kvid` stores. There is no automatic migration of `.bin` vector indexes or of MP4 or QR videos.
- To move data, collect the original text and `put` it into a store, or write JSON Lines records and use `importJsonLines`.

Current state of the experimental pipeline:

- **JVM**: QR generation/decoding, FFmpeg video encoding/decoding, chunking, embeddings and in-memory search work. The end-to-end encode → MP4 → decode path has no automated test yet.
- **Android**: video encoding and QR decoding exist but have known defects (no QR generator, encoder does not drain output buffers, decoder ignores stride, decoded chunks are not decompressed). Not usable end to end yet.
- **iOS**: QR generation fails in the tested CI and local Xcode 26.2 simulators; the QR decoder, the video encoder (writes a custom container, not MP4) and compression (raw DEFLATE instead of gzip) are not compatible with the other platforms yet.
- `SimpleEmbedding` is a hashing placeholder, not a semantic model.

The five iOS QR rendering tests run by default. CI explicitly excludes them with `KVID_SKIP_IOS_QR_RENDERING_TESTS=true` while the simulator rendering defect is unresolved; the task logs this exclusion. The known decoder round-trip failure remains ignored.

The examples in `kvid-examples` exercise the experimental pipeline: `./gradlew :kvid-examples:run --args="<example>"` with `persistence`, `persistence-load`, `advanced`, `chunking`, `vector-index`, `embedding`, `benchmark` or `qrtest`.

MIT License
