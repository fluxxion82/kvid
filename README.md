# kvid

kvid is an embedded, searchable document store for Kotlin Multiplatform apps on Android, iOS and the JVM. A store is one portable SQLite file holding your documents, their metadata and tags, every retained version, and an offline full-text index.

It started as a Kotlin port of [memvid](https://github.com/memvid/memvid). memvid has since moved from QR codes in video to a single-file memory format; kvid follows that direction for general-purpose use (notes, journals, offline documentation, transcripts, message history), with AI integrations optional and last.

## What the store provides

- Durable writes: every write commits atomically and survives process death; scoped `transaction { }` blocks group writes.
- Versioned documents: updates and deletes keep history; `get(id, asOfSeq)` reads a document as it was.
- Offline full-text search with BM25 ranking, snippets, Unicode case and accent folding, and plain-text queries by default.
- Filters shared by listing and search: event-time range, tags (all required) and uri prefix; tag counts for filter UIs.
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

## Notes sample

`kvid-sample` is a Compose Multiplatform notes app built on the store: list, search while typing (whole words), tag chips, edit, delete and version history. It closes the store whenever the app leaves the foreground.

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
| iOS | arm64 device and arm64 simulator | Intel simulators are not supported by the bundled SQLite |

The store format (0.1, schema 2) is pre-release: files from unreleased schema 1 builds are refused, and the format may still change before the first release.

## Build and test

Requires JDK 17, the Android SDK, and Xcode on macOS for iOS.

```bash
./gradlew build                                    # JVM and Android host build and tests, sample included
./gradlew :kvid-core:iosSimulatorArm64Test         # iOS simulator tests (macOS)
./gradlew :kvid-core:connectedAndroidDeviceTest    # store tests on a connected device or emulator
```

Toolchain: Kotlin 2.5.0-Beta1, Gradle 9.8, Android Gradle Plugin 9.4, Compose Multiplatform 1.12.1.

## Packaging

kvid is not on Maven Central yet. The build produces everything a release needs, and CI checks it on every run.

**Kotlin.** `kvid-core` publishes one Kotlin Multiplatform module with JVM, Android, iOS arm64 and iOS simulator arm64 variants:

```bash
./gradlew :kvid-core:publishToMavenLocal                         # then depend on com.kvid:kvid-core:0.1.0
./gradlew :kvid-core:publishAllPublicationsToStagingRepository   # release bundle in kvid-core/build/staging-repo
```

```kotlin
// build.gradle.kts of a Kotlin Multiplatform or Android project, with mavenLocal() in its repositories
commonMain.dependencies { implementation("com.kvid:kvid-core:0.1.0") }
```

**Swift.** `./gradlew :kvid-core:assembleKvidCoreReleaseXCFramework` writes `kvid-core/build/XCFrameworks/release/KvidCore.xcframework` with device and simulator slices. Add it to an Xcode target and `import KvidCore`. The Swift API is Kotlin's Objective-C export, so suspend functions become `async` methods.

Before a Maven Central release, the group ID must move to a namespace the publisher can verify, and publications must be signed.

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
