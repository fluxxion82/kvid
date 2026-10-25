# Implementation plan: ONNX Runtime embedder on iOS

_Written October 25, 2026 by the local implementer. Turns the brief [ios-embedder.md](ios-embedder.md) into ordered tasks (step 3 of [ADR 0002](../adr/0002-embedding-runtime.md)). Code goes on `phase-5-ios-embedder`, created from `main` after `phase-5-onnx-embedder` is merged. Status: revised after review by Codex (`gpt-6.1-sol`, high; its review is the last section). The "Revisions after review" section **supersedes** the parts of D1 to D5 and the tasks it names. Awaiting the cloud session's review._

## Facts checked before planning

- **Runtime archive.** The CocoaPods spec `onnxruntime-c` 1.30.0 points at `https://download.onnxruntime.ai/pod-archive-onnxruntime-c-1.30.0.zip`: 63,965,603 bytes, SHA-256 `e6f1670c14406fd9f082bb400ab197a9b0a9646058ca6366e440642e2b54a2ea` (downloaded October 25). It holds `onnxruntime.xcframework` (slices `ios-arm64`, `ios-arm64_x86_64-simulator`, `macos-arm64_x86_64`), the C and C++ headers in `Headers/`, and `LICENSE` (MIT). The pod declares `static_framework: true`, minimum iOS 15.1, `libraries: c++`, frameworks `CoreFoundation Foundation Network Security SystemConfiguration UIKit`, and `CoreML` weak.
- **Static archives.** Each slice's `onnxruntime.framework/onnxruntime` is an `ar` archive, not a dylib: 48 MB for `ios-arm64`, 98 MB for the simulator slice (x86_64 and arm64; kvid needs only arm64).
- **C API.** `ORT_API_VERSION` is 30. The API is a struct of function pointers obtained through `OrtGetApiBase()->GetApi(ORT_API_VERSION)`. It has `DisableTelemetryEvents(const OrtEnv*)`, `CreateTensorWithDataAsOrtValue`, `GetTensorMutableData`, `GetTensorTypeAndShape`, `DisableCpuMemArena`, `DisableMemPattern`, `SetIntraOpNumThreads` and `SetSessionGraphOptimizationLevel`.
- **Telemetry on iOS.** Unlike the AAR, the iOS archive has no manifest provider, but it contains Microsoft's 1DS client. The strings include `https://mobile.events.data.microsoft.com/OneCollector/1.0`, and the archive imports `NSURLSession` and the `nw_path_monitor_*` functions. The header comment says telemetry events are on by default. The binary also contains the string `ORT_DISABLE_TELEMETRY`, presumably an environment variable read when the environment is created. **This makes telemetry a first-class task here (Task 4), with a verification, not a one-line call.**
- **Devices.** Xcode 27.1 is installed, with simulators for iOS 17.5 to 27.0. A physical iPhone 13 (`iPhone14,5`, "snoot") is paired with this Mac. Running Kotlin/Native code on it needs a signed host app (Task 9).
- **Existing code** on `phase-5-onnx-embedder`:
  - `commonMain` has the pipeline: `BertEmbedder`, `BertEmbeddingModel`, `EncoderBackend`, `checkHiddenStatesShape`.
  - `src/jvmAndAndroidMain` (a directory shared by `jvmMain` and `androidMain` through `srcDir`) has `OnnxEmbedder`, `OnnxOptions`, `OrtEncoderBackend` and `FileDigest`.
  - `src/jvmAndAndroidTest` has six test classes and `EmbeddingFixture`. They use `java.io.File`, `java.util.concurrent` latches and atomics, `kotlin.concurrent.thread` and `runBlocking`, 15 call sites in all.

## Decisions (for review)

### D1. API shape: `expect class OnnxEmbedder` in `commonMain`

- `commonMain` declares the API:

  ```kotlin
  expect class OnnxEmbedder : Embedder, AutoCloseable {
      override val spec: EmbeddingSpec
      override suspend fun embed(texts: List<String>): List<FloatArray>
      override fun close()
      companion object {
          suspend fun open(modelPath: String, vocabPath: String, model: BertEmbeddingModel = …, options: OnnxOptions = …): OnnxEmbedder
      }
  }
  ```

- The current class becomes the `actual` in `src/jvmAndAndroidMain`, which compiles into both the JVM and Android, so each of them sees exactly one actual. The new actual is in `iosMain`.
- `OnnxOptions` moves unchanged to `commonMain`; it is plain data. So do `OpenTimings` and the internal `open` overload with the timing callback, as an `internal expect` function.
- **Why:** the brief asks that the sample's shared code can open an embedder from `commonMain`. With platform classes that only have identical signatures, every common caller would need its own `expect` wrapper. The `expect` declaration costs one keyword on the merged JVM/Android class, with no behaviour change. Default arguments are declared on the `expect` side only (Kotlin's rule).
- **Merged code affected:**
  - `OnnxEmbedder` gains `actual`, and its defaults move to the `expect`.
  - `OnnxOptions` changes source set; its package and API stay the same.
  - KDoc moves to the `expect`.
- No binary-compatibility concern: `kvid-embed-onnx` is unpublished.

### D2. Linking the runtime into kvid, and what an app links

- **cinterop.** A cinterop `onnxruntime` for `iosArm64` and `iosSimulatorArm64`:
  - The `.def` file covers `onnxruntime_c_api.h` only, and filters headers to it.
  - `staticLibraries = libonnxruntime.a` and `libraryPaths` point at a thinned, per-target copy of the slice. The `ios-arm64` archive is used as is; the simulator archive is thinned with `lipo -thin arm64`.
  - `linkerOpts = -lc++ -framework Foundation -framework CoreFoundation -framework Network -framework Security -framework SystemConfiguration -framework UIKit -weak_framework CoreML`.
- With `staticLibraries`, Kotlin/Native embeds the archive in the module's klib and links it into every final binary built from it: test executables, frameworks, and the sample's shared framework. **No consumer needs CocoaPods, a podspec, or a separate copy of `onnxruntime.xcframework` for Kotlin/Native builds.**
- **To verify in Task 2 (spike), before anything else is built on it:**
  - (a) A simulator test executable links and calls `OrtGetApiBase`.
  - (b) A **static** framework of a consumer module (the shape `kvid-core` publishes, `isStatic = true`) contains the ORT objects, or does not. If it does not, a Swift app linking that static framework has undefined symbols.
- **Fallbacks for (b):**
  1. Make the consumer framework that includes `kvid-embed-onnx` dynamic, so ORT is linked into it.
  2. Ship the pinned `onnxruntime.xcframework` next to the kvid XCFramework, and document that the app adds both in Xcode (no CocoaPods).

  The spike result picks one; the plan's review record states which. Either way a Swift app does not use CocoaPods.
- **klib size.** The iOS klibs grow by about 48 MB (device) and about 49 MB (thinned simulator archive). The klib is not published in this step.
- **App size.** What ORT adds to an app is measured in Task 9 from a linked, stripped app binary. The archive size is not the app size, since dead-stripping removes unused kernels.

### D3. Getting the runtime: a pinned download, never committed

- `kvid-embed-onnx/scripts/runtime-pins.json` records:
  - the archive URL;
  - its SHA-256;
  - the SHA-256 of each slice binary after unzip, so the cache is verified per file.
- A Gradle task `prepareOnnxRuntimeIos` in `kvid-embed-onnx/build.gradle.kts`:
  - downloads the archive into `${KVID_RUNTIME_CACHE:-~/.cache/kvid/runtime}/onnxruntime-c-1.30.0/` when it is missing or its digest is wrong, with up to 4 attempts with backoff;
  - verifies the SHA-256 and unzips;
  - writes thinned per-target archives into `build/onnxruntime/<target>/libonnxruntime.a`.
- The cinterop tasks depend on it. It is a configuration-cache-friendly task with declared inputs (the pins file) and outputs.
- The task runs only when an iOS cinterop is in the task graph, so JVM-only builds on Linux never download it.
- CI caches `~/.cache/kvid/runtime` with the same split restore/save pattern as the model.
- `scripts/check-local.sh` needs no new part: its ios part triggers the task.

### D4. Native resources and threads

- **`OrtIosBackend : EncoderBackend`** owns, released exactly once in reverse order of creation:
  - the `OrtSession*`;
  - the `OrtSessionOptions*`, which must outlive the session (as on the JVM);
  - the input and output name strings, as allocator-owned or `nativeHeap` C strings.
- **Environment.** A process-wide `OrtEnv*`, created lazily under a lock and never released. ORT supports one environment per process, and the JVM shares one as well. Releasing it while another embedder is open would be undefined.
- **Tensors.** The three input tensors come from `CreateTensorWithDataAsOrtValue` over **pinned** Kotlin `LongArray`s (`usePinned` for the whole `Run`), so no copy is made. Each `OrtValue*` is released in `finally`, inside the pinned scope, so ORT never sees unpinned memory.
- **Output.**
  - The output `OrtValue*` comes from `Run`.
  - Read the element type and shape (`GetTensorTypeAndShape`, `GetDimensionsCount`, `GetDimensions`), then call `checkHiddenStatesShape`.
  - Copy into a `FloatArray` through `GetTensorMutableData` and `memcpy` into a pinned array.
  - Release the type-and-shape info and the value in `finally`.
- **Errors.** Every `OrtStatus*` is checked by one helper that reads `GetErrorMessage`, releases the status and throws `IllegalStateException` (the JVM throws `OrtException`; `open` wraps either into the same `IllegalArgumentException`/`IllegalStateException` messages, so error text is the same on every platform).
- **`close` waits for running inferences.**
  - A `pthread_rwlock_t` allocated with `nativeHeap.alloc` and initialized once. `run` holds the read lock for the synchronous native call only; `close` takes the write lock.
  - The lock and its memory are freed only in `close`, after the session is released, while the write lock is held. Then it is unlocked and destroyed.
  - The closed flag is an `AtomicInt` from `kotlin.concurrent.atomics` (opt-in `ExperimentalAtomicApi`), checked under the read lock.
  - This is the K/N primitive the brief asks to name. `kotlinx.coroutines.sync.Mutex` is not used, because the critical section is a blocking native call that must not suspend.
- **Cancellation.**
  - `open` keeps the JVM structure: blocking work in `withContext(Dispatchers.IO)` (available on Native), the result assigned outside the block, closed if `withContext` throws after it completed.
  - `embed` checks `ensureActive()` between sub-batches in common code.
  - A running `Run` is not interrupted. `RunOptionsSetTerminate` could do that, but is not needed for parity with the JVM.
- **Digests.**
  - `sha256Hex(path)` in `iosMain`: `CC_SHA256_Init/Update/Final` from `platform.CoreCrypto`.
  - Reads 1 MiB blocks with `fopen`/`fread` into a pinned `ByteArray`, closed in `finally`.
  - Output is lowercase hex.
  - A common test compares it with known digests of the fixture files (the values in `source.json`), so JVM and iOS agree by test, not by assumption.
- **Options.**
  - `intraOpThreads` maps to `SetIntraOpNumThreads`.
  - `cpuMemoryArena = false` maps to `DisableCpuMemArena`.
  - `memoryPattern = false` maps to `DisableMemPattern`.
  - `ORT_ENABLE_ALL` maps to `SetSessionGraphOptimizationLevel`.
  - `maxBatchSize` and `sortByLength` are applied by the common `BertEmbedder`.
  - The defaults are the same as on Android: sorted batches of 4 with the arena on. Whether that is also the right default on iOS is measured in Task 9 and reported, not changed in this step.

### D5. Tests run from `commonTest`

- The six classes and `EmbeddingFixture` move from `src/jvmAndAndroidTest` to `commonTest`. They then run on the JVM, the Android device and the iOS simulator from one copy.
- JVM-only calls are replaced as follows:

  | Before | After |
  |---|---|
  | `java.io.File` (reading, temporary directories) | `kotlinx-io` `SystemFileSystem` (already a `commonTest` dependency), plus a small `expect fun tempDir()` |
  | `runBlocking` | `runTest(timeout = 30.minutes)`, with the body in `withContext(Dispatchers.Default)` so delays and the measurement use real time |
  | `java.util.concurrent` atomics | `kotlin.concurrent.atomics` |
  | `CountDownLatch` | a `CompletableDeferred` plus a blocking `expect` latch for the backend hook, which runs on a native thread |
  | `kotlin.concurrent.thread` | a coroutine on `Dispatchers.Default` (the close-while-running test) |

- `fixtureFile`, `testPlatform` and `pssKb` become `expect` functions in `commonTest`:
  - JVM: unchanged.
  - Android: unchanged, including the shell `cat` fallback.
  - iOS:
    - `fixtureFile` uses host paths, which the simulator can read.
    - `testPlatform` returns `ios-<version>/<model>/<arch>` from `UIDevice` and `uname`.
    - `pssKb` returns the `phys_footprint` from `task_info(TASK_VM_INFO)` and reports itself as `footprint`. The line's field names are `mem_before_kb` and so on, with `mem_kind=pss|footprint`.
- The test hooks `live` and `insideRunForTests` move to a common `internal object BackendHooks`. They stay internal and small, as the cloud review accepted. Both actual backends increment and decrement `live`, and invoke the hook inside the read lock.
- `TelemetryProviderTest` stays Android-only. A new iOS telemetry test is described in Task 4.

## Tasks

### Task 0: baseline

- Create the `phase-5-ios-embedder` branch from `main` after the merge.
- Run `scripts/check-local.sh` with the android part on the emulator, and record the result.
- Run `./gradlew :kvid-embed-onnx:iosSimulatorArm64Test --dry-run` and list which iOS tasks `build` already runs.

### Task 1: runtime pins and download

- Create `runtime-pins.json` and the `prepareOnnxRuntimeIos` task (D3).
- **Done:**
  - A cold run downloads, verifies and unzips the archive.
  - A second run verifies without downloading.
  - A tampered slice fails, naming the file.
  - Nothing appears in `git status`.

### Task 2: cinterop spike (gate)

- Add the cinterop and the `.def` file (D2).
- Add a throwaway iOS test that gets the API base and the version string, and creates and releases an environment.
- Then build a scratch consumer module with a static framework (`isStatic = true`) depending on `kvid-embed-onnx`, and check with `nm` whether ORT symbols resolve. Link it into a two-line Swift command-line target for the simulator to prove an app-shaped link.
- **Done:** the test passes on the simulator, and the static-framework result is written into this plan's review record with the chosen fallback. **Stop for review if neither fallback works.**

### Task 3: `expect`/`actual` split (no behaviour change)

- Make the move in D1, then run `check-local.sh` with the android part.
- **Done:** JVM and Android results equal the baseline. iOS compiles with an `actual` that throws `NotImplementedError` only on a scratch commit that is never pushed. The brief and the earlier plan rule out shipping an iOS stub, so the pushed history goes straight from Task 3 to a working Task 5.

### Task 4: telemetry off, verified

- **In `actual` `open`:**
  - Call `setenv("ORT_DISABLE_TELEMETRY", "1", 0)` before the process-wide environment is created. Overwrite is 0, so an app's own setting wins.
  - Call `DisableTelemetryEvents(env)` right after creation.
  - Comment both with the strings found in the binary.
- **Verification** (iOS test `TelemetryTest`, plus a manual check recorded in the ROADMAP):
  - **(a) The flag.** The test checks that `getenv("ORT_DISABLE_TELEMETRY")` is set after `open`, and that `DisableTelemetryEvents` returned no error.
  - **(b) No traffic.** During one `check-local` ios run, record the simulator's network activity with `log stream --predicate 'process == "<test binary>" AND subsystem == "com.apple.network"'` and `nettop -m tcp -p <pid>`. Confirm there is no connection to `mobile.events.data.microsoft.com`.
  - **(c) Control.** Repeat (b) once with both calls removed. The test is valid only if that run shows the connection. If no connection appears even then, the ROADMAP says so: the verification is inconclusive, not passed.
- **Decision for the owner** if (c) shows traffic: whether the README should tell apps about it. This task already disables it in every embedder.

### Task 5: iOS backend and `actual OnnxEmbedder`

- Write `OrtIosBackend`, `sha256Hex`, the `actual` `open` (input-name, output-name and output-type checks as on the JVM, warm-up, timings) and the options mapping (D4).
- **Done:**
  - Every moved test class passes on the simulator with the fixture required: parity, lifecycle including close-while-running and cancelled open, store, measurement and tuning.
  - The `[kvid-embedding] cases=194` line reports minimum cosine ≥ 0.9999 and batched ≥ 0.99999.
  - `BackendHooks.live` returns to its starting value in every lifecycle test.

### Task 6: leak check

- Run the lifecycle and parity tests under the simulator with `MallocStackLogging=1` (forwarded as `SIMCTL_CHILD_MallocStackLogging`).
- Then run `leaks` against a test process paused at its end, for example by a `KVID_PAUSE_AT_END` hook in a debug-only test.
- If `leaks` cannot attach to simulator processes on this Xcode, use a long loop instead: open, embed and close 200 times, and compare `phys_footprint` before and after, within a stated tolerance.
- **Done:** zero leaks attributed to ORT objects that kvid owns, or the loop shows flat memory. Record the method used.

### Task 7: CI

- **iOS job.** Before the embed-onnx tests:
  - restore or download the model with the existing cache key;
  - build the embedding fixture (the Python requirements install on macOS runners);
  - restore and save `~/.cache/kvid/runtime`.
- Export `KVID_EMBEDDING_FIXTURE_DIR`, `KVID_REQUIRE_EMBEDDING_FIXTURE=true` and `KVID_EMBEDDING_MEASURE=true`.
- After `:kvid-embed-onnx:iosSimulatorArm64Test`, run `scripts/verify_device_tests.py --glob '{module}/build/test-results/iosSimulatorArm64Test/*.xml'`. It requires:
  - `BertTokenizerParityTest`
  - `OnnxEmbedderParityTest`
  - `OnnxEmbedderTest`
  - `OnnxEmbedderStoreTest`
  - `OnnxEmbedderMeasurementTest`
  - `TelemetryTest`

  It also greps the parity and measurement lines, as the JVM job does.
- The optimized measurement runs `:kvid-embed-onnx:iosSimulatorArm64ReleaseTest --tests com.kvid.embed.OnnxEmbedderMeasurementTest`, with a release test binary added as in `kvid-core`.
- **`check-local.sh`:** the ios part sets the embedding variables and runs the release measurement. It requires the same classes from the simulator results, with the same detection rule as the jvm part.
- **Done:** CI green, with the parity and measurement lines in the iOS job log.

### Task 8: XCFramework and consumer check

- Apply the fallback chosen in Task 2.
- Build the kvid XCFramework that a Swift app would use: the scratch consumer from Task 2, kept as a CI-only build check, not a published artifact.
- Link it into the Swift command-line target for the simulator in CI. This proves that "no CocoaPods" holds for an app-shaped link.
- **Done:** the CI step links, and the README states exactly what a Swift app adds.

### Task 9: measurements, simulator and iPhone

- **Simulator, optimized binary.** Run the measurement test and the tuning matrix on `mixed` and `m3` with batch 4 sorted, arena on and off, plus batch 16 unsorted for comparison.
  - This gives the same `[kvid-embedding-*]` lines as on Android, with `mem_kind=footprint`.
  - Simulator timings are a reference only.
- **iPhone 13 ("snoot"), if signing works.**
  - A minimal SwiftUI host app (`kvid-embed-onnx/measure-ios/`, generated with xcodegen and not part of the sample) calls a Kotlin entry point. The entry point runs the measurement and the `mixed`/`m3` tuning configurations from files copied into the app's container with `xcrun devicectl device copy to`, and prints the lines to the console, which `devicectl` collects.
  - Automatic signing needs the owner's development team in Xcode. If `xcodebuild -allowProvisioningUpdates` cannot sign without the owner's interaction, stop and ask; credentials are never entered by the agent.
  - Record:
    - time per note on `mixed` and `m3`;
    - `phys_footprint` before, after open, at peak and after;
    - the size the runtime adds: the stripped app binary with and without `kvid-embed-onnx` linked, from the archived app's `__TEXT` and `__DATA` segments.
  - Measure on a cool phone, with arena on/off pairs interleaved, as on the Pixel 8.
- **Done:** the numbers are in the ROADMAP (Milestone 5) and feed [mobile-indexing.md](mobile-indexing.md).

### Task 10: README and docs

- **README:** iOS support in the `kvid-embed-onnx` section, covering:
  - what an app links (per Task 8);
  - the minimum iOS version, the higher of 15.1 (ORT) and the sample's 18.5 for the sample only;
  - telemetry (per Task 4);
  - where the runtime comes from.
- **ADR 0002:** an implementation note.
- **ROADMAP:** results, with what each `check-local` run ran and skipped.

## Commit order

1. Runtime pins, download task, cinterop (Tasks 1 and 2, without the scratch consumer).
2. The `expect`/`actual` split and the move of the tests to `commonTest`. JVM and Android stay green; iOS compiles because the commit already contains Task 5's backend, since no stub is ever pushed.
3. The iOS backend, digests and the telemetry handling, with their tests.
4. CI, the consumer link check and `check-local`.
5. The measurement host app, if signing works.
6. README.

`check-local.sh` (with the android part, on the emulator, plus iOS) runs before every push. Commits 2 and 3 may be squashed if a green intermediate state is not practical.

## Delegation

- Task 1 (Gradle download task) and the CI YAML of Task 7 can run in parallel with Task 2's spike: they touch different files.
- Everything after the spike is sequential on one Gradle worktree.
- The Swift and xcodegen host app (Task 9) is independent of the Kotlin backend once Task 5's entry point exists.
- Device runs, signing, and decisions stay with the coordinating session.

## Risks

- Static framework consumers (Task 2 gate). Mitigated by two fallbacks, both CocoaPods-free.
- Telemetry verification may be inconclusive on the simulator. The control run (4c) prevents a false "verified".
- K/N interop overhead in the copy of hidden states (512 × 4 × 384 floats per batch, about 3 MB). A `memcpy` into a pinned array keeps it negligible. Measure it if parity passes but time is far off the Android numbers.
- `pthread_rwlock` misuse (unlocking from another thread) would be undefined behaviour. The lock is only taken and released inside one synchronous call, never across a suspension point, as on the JVM.
- The iOS test binary grows by ORT's size, and simulator test linking slows down. Acceptable; measure it in Task 0 against Task 7.

## Open questions for the reviewers

1. D1 changes the merged JVM/Android class to an `actual`. Is the cost acceptable, or should `commonMain` only get an `expect fun openOnnxEmbedder(...)`: Embedder & AutoCloseable` factory instead?
2. D5 moves the tests to `commonTest`, which touches six merged files. Alternative: a third srcDir `src/allPlatformsTest`. Same effect, but less idiomatic.
3. Is `setenv` before environment creation acceptable in a library (it changes the process environment), given that overwrite is 0 and the default is otherwise "on"?

## Revisions after review (October 25)

Codex found three P1 problems, nine P2 and two P3. All are accepted. Where a revision conflicts with the text above, the revision wins.

### R1. Lifecycle without destroying the lock (P1; replaces the `pthread_rwlock_t` part of D4)

- **Primitive.** Each `OrtIosBackend` owns an `NSCondition`. It is an Objective-C object, so Kotlin/Native frees it with the backend and `close` never destroys it. The backend keeps a state `OPEN | CLOSING | CLOSED` and an `active` count, both guarded by the condition.
- **`run`.**
  - Locks the condition. If the state is not `OPEN`, unlocks and throws `IllegalStateException("OnnxEmbedder is closed")`. Otherwise increments `active` and unlocks.
  - Does the whole synchronous operation without holding the lock: tensor creation, `Run`, output inspection and copy, and cleanup.
  - In `finally`, locks, decrements `active`, calls `broadcast` and unlocks.
  - The hook `BackendHooks.insideRun` is invoked after registering.
- **`close`.**
  - Locks the condition. If the state is `CLOSED`, returns. If it is `CLOSING`, waits until `CLOSED` and returns.
  - Otherwise sets `CLOSING`, waits while `active > 0`, and unlocks.
  - Releases the native resources exactly once (R5's ledger), then locks, sets `CLOSED`, broadcasts and unlocks.
  - A second or concurrent `close` therefore waits for the first, and nothing ever touches a destroyed primitive.
- **Threads.** Lock and unlock always happen on one thread with no suspension between them; `waitUntilDate` is used with a bounded timeout only in tests' debug assertions.
- **JVM and Android.** `OrtEncoderBackend` keeps its `ReentrantReadWriteLock`, which is garbage-collected and never destroyed. The two backends satisfy the same common lifecycle tests.

### R2. Telemetry: a once-only, documented process policy (P1; replaces Task 4's implementation)

- **Initialization.** One process-wide `OrtIosRuntime.initialize()`, guarded by a lock and an atomic "done" flag, runs before kvid's first call into ORT (before `OrtGetApiBase` is dereferenced). It also checks `GetApi(30)` for null and records the linked runtime version from `GetVersionString`.
- **The variable.** Inside the guard:
  - If `ORT_DISABLE_TELEMETRY` is unset, it is set to `1` with `setenv(…, 1)`, and the return code is checked.
  - If the app already set a value that ORT 1.30 treats as true (the parsing in [`telemetry_environment.h`](https://github.com/microsoft/onnxruntime/blob/v1.30.0/onnxruntime/core/platform/telemetry_environment.h) is copied into a comment and a test), it is kept.
  - If the app set any other value, **kvid respects it as the app's explicit choice**, and the embedder's documentation says telemetry suppression is then the app's responsibility: kvid makes the C API call only, and that call does not stop ORT's uploader.
  - kvid never restores or changes the variable afterwards.
- **The C API call.** `DisableTelemetryEvents(env)` is still called and checked once, right after the process-wide `OrtEnv` is created (the brief requires it).
- **Documentation.**
  - The KDoc and the README state the process-wide side effect.
  - They state that another ORT user in the same process which initializes ORT first is not covered, so a host that needs that guarantee sets `ORT_DISABLE_TELEMETRY=1` at launch, for example in the scheme's environment or before any framework loads.
- **Owner decision, flagged.** The alternative policy is to refuse to open when the app has a false value, or to overwrite it. This plan respects the app's value. The owner can change that in review; it is one branch in `initialize()`.
- **Verification** (replaces Task 4 (a) to (c)):
  - **CI test.** `TelemetryPolicyTest` runs in a **fresh process** per case: the simulator test runner with `--tests` per case, or a probe executable. It checks four cases: the variable unset, a truthy value, a falsy value, and an empty value. For each it checks the resulting environment and that `DisableTelemetryEvents` succeeded. It claims nothing about the network.
  - **Network probe, manual, recorded in the ROADMAP.** A disposable probe executable, built by the same Gradle setup, opens an embedder over synthetic text and runs three conditions, each in its own fresh process:
    1. production policy;
    2. API call only, with the variable forced to `0`;
    3. control, with neither the variable nor the call.

    The probe clears inherited CI and test variables that ORT 1.30 also treats as opt-outs (`CI`, `ORT_RUNNING_UNIT_TESTS` and the others listed in `telemetry_environment.h`) and prints its own environment. Capture is continuous, started before launch, and lasts 2 minutes per condition: `log stream` filtered to the probe's PID, DNS lookups through `log stream --predicate 'subsystem == "com.apple.network"'`, and `nettop -L` sampling attributed to the PID. A pass needs no connection to `mobile.events.data.microsoft.com` in condition 1 **and** a visible attempt in condition 3. If condition 3 shows nothing, the result is recorded as *inconclusive*, and the README says "disabled through ORT's documented switches; network absence not independently verified".

### R3. Explicit test gates that survive the move (P1; amends D5 and Task 7)

- **`check-local.sh`.** `have_embedding_tests()` now checks for `kvid-embed-onnx/src/commonTest/kotlin/com/kvid/embed/OnnxEmbedderParityTest.kt`, the stable new location, instead of the `src/jvmAndAndroidTest` directory. Each part names its required classes explicitly:
  - JVM and Android keep their current lists. `TelemetryProviderTest` stays Android-only.
  - iOS requires `BertTokenizerParityTest`, `OnnxEmbedderParityTest`, `OnnxEmbedderTest`, `OnnxEmbedderStoreTest`, `OnnxEmbedderMeasurementTest` and `TelemetryPolicyTest`.
- **The move is a commit of its own,** with these negative checks on each platform before it is pushed:
  - a missing required fixture fails;
  - a stale result directory (for example, with `--since` in the future) fails;
  - a no-op measurement (with `KVID_EMBEDDING_MEASURE` unset) fails.

### R4. Blocking tests use dedicated threads (P2; amends D5)

- The test orchestration stays on the `runTest` scheduler.
- Blocking work, such as an inference held by the hook or a `close` that waits, runs on **dedicated single-thread dispatchers**, `newSingleThreadContext`, created and closed per test.
- Measurements and real delays use `Dispatchers.Default`, never the test dispatcher.
- **Latches.** The hook uses a blocking `expect class TestLatch` with timed waits and release in `finally`: `CountDownLatch` on the JVM and Android, `NSCondition` on iOS. Suspending notification uses `CompletableDeferred`. Hook publication and counters use `kotlin.concurrent.atomics`.
- **Bounds.** Every wait and join is bounded. The Gradle test task has an outer timeout, because `runTest` cancellation cannot stop a stuck native wait.
- **Serialization.** Tests that mutate the global hooks are in one class, so they never run concurrently.
- **Cancelled-open test.** It keeps the "cancel during the return handoff" condition with a lazily started job, so the callback cannot race the job reference.
- **Portability.** Also replaced: `System.nanoTime` becomes `TimeSource.Monotonic`. `String.format(Locale.ROOT, …)` and the existing `fmt` become one locale-independent common formatter. `fixtureFile` returns a kotlinx-io `Path` instead of a `java.io.File`.

### R5. Native resource ledger and error unwind (P2; amends D4 and Task 5)

- **Ownership.** One internal `NativeLedger` per operation, which releases in reverse order and only what was created:

  | Resource | Released with |
  |---|---|
  | `OrtMemoryInfo` | `ReleaseMemoryInfo` |
  | session `OrtTypeInfo` from input/output inspection | `ReleaseTypeInfo` |
  | owned `OrtTensorTypeAndShapeInfo` | `ReleaseTensorTypeAndShapeInfo` |
  | allocator-returned input and output names | the default allocator's `Free`, never `nativeHeap.free` |
  | every input and output `OrtValue` | `ReleaseValue` |
  | `OrtStatus` | `ReleaseStatus`, after `GetErrorMessage` has been copied to a Kotlin string |

  The borrowed default allocator and the tensor info view from `CastTypeInfoToTensorInfo` are never released.
- **Out-pointers and allocation.** Every out-pointer is initialized to null, and each successful allocation is registered immediately.
- **Lifetimes.**
  - Names and shape arrays live in the backend's own `nativeHeap` allocations for its lifetime, never in a `memScoped` block kept as a field.
  - Input values are released inside their pins.
  - Output values are released after the copy.
- **Size checks.** Byte counts use `Long.SIZE_BYTES` and `Float.SIZE_BYTES` with checked multiplication. The element type `FLOAT` and the exact shape `[batch, seqLen, 384]` are checked before any copy.
- **Failure-path tests.** An internal test seam makes each step fail in turn: memory info, session, input inspection, output inspection, warm-up, and cancellation during `open`. The tests check a per-kind counter of live native objects, not only the backend count.
- **Errors.** Exception categories and stable message prefixes, including file names, match the JVM. The underlying ORT error text may differ between the Java and C builds and is not compared.
- **Digest.**
  - The digest loop distinguishes `ferror` from EOF and checks every CommonCrypto return value. `fclose` runs on every path, so an I/O error never yields the hash of a prefix.
  - Tests cover empty, small and multi-block known byte strings, as well as the fixture files.

### R6. Runtime integrity runs every time (P2; amends D3 and Task 1)

- **Two tasks.**
  - `verifyOnnxRuntimeIos` is **never up-to-date and never cached**. It checks the SHA-256 of the ZIP, of every extracted header the C header includes, of the two source slice binaries and of the two thinned archives against `runtime-pins.json`.
  - `prepareOnnxRuntimeIos` is cacheable and does download, extract and thin.
- **Wiring.** Every cinterop and link task depends on `verifyOnnxRuntimeIos`, which depends on `prepareOnnxRuntimeIos`.
- **Mismatches.**
  - A mismatch in a derived file deletes it and re-extracts it from the verified ZIP, once.
  - A mismatch in the ZIP deletes it and downloads it again, once.
  - A second mismatch fails, naming the file.
- **Concurrency.** Extraction writes to a temporary directory and renames it atomically, under a cross-process file lock in the cache directory (`FileChannel.lock`), so concurrent worktrees never see a partial download.
- **Inputs.** The cache location and the target are task inputs. So are the versions of `lipo` and `unzip`, recorded but not part of the cache key.
- **Done adds:** after a successful build, tampering with an extracted header, or with a thinned archive, fails the next build (or re-extracts once and then succeeds, as specified).

### R7. A link spike that proves real use, on both slices (P2; amends D2, Tasks 2 and 8)

- **Live code.** The spike's Kotlin entry point creates a session over the real model and runs the warm-up. The Swift caller invokes it, so dead stripping cannot remove the path under test.
- **What is linked:**
  - an iOS-simulator app target, built from a clean consumer directory with no access to the preparation cache or the header search paths;
  - an `iosArm64` device framework or XCFramework slice, which needs no signing to build.
- **Evidence.** The final binary's link map, not `nm` of an archive.
- **One distribution mode is chosen and documented:**
  - **Dynamic umbrella.** The app embeds and signs it.
  - **Static umbrella plus a separate static `onnxruntime.xcframework`.** Linked with "Do Not Embed", with `-lc++` and the system frameworks listed.

  Never two ORT copies, and never several independently exported Kotlin runtimes.
- **Scope.** `kvid-core`'s lexical-only XCFramework stays free of ORT; the consumer check uses a separate, unpublished umbrella module.
- **Licence.** The ORT `LICENSE` and notices travel with whatever the chosen mode ships.

### R8. Deployment target enforced (P2; amends Tasks 2, 8 and 10)

- Both native targets, the umbrella framework and the measurement host set the minimum iOS version to 15.1, ORT's floor.
- The spike inspects `LC_BUILD_VERSION` in the linked binaries, and a link that warns about a newer object is a failure.
- `kvid-core` keeps its own minimum and the sample keeps 18.5.
- CI compiles and links both iOS slices even when no device run happens.

### R9. Precise gates for debug and release results (P2; amends Task 7)

- **Verifier.** `verify_device_tests.py --module kvid-embed-onnx --glob '{module}/build/test-results/iosSimulatorArm64Test/*.xml' --since <run start>` with an explicit `--require kvid-embed-onnx:<class>` per class in R3.
- **XML format.** Before the gate is written, the Kotlin/Native XML format is checked: class names, skips, and where standard output lands. The parity and marker greps then follow that format.
- **Optimized run.** It is gated on **its own** fresh result directory (`iosSimulatorArm64ReleaseTest`), requiring `OnnxEmbedderMeasurementTest` and its marker. A debug measurement never satisfies it.
- **Tuning.** At least one tuning configuration is run explicitly, `KVID_EMBEDDING_TUNE=corpus=mixed+batch=4+sort=on`, and gated on its `[kvid-embedding-tune]` line. Task 5's "tuning passes" means that.
- **Forwarding.** `KVID_EMBEDDING_TUNE`, `KVID_EMBEDDING_MEASURE` and any pause or leak settings are forwarded with `SIMCTL_CHILD_`.
- **Execution policy.** The no-up-to-date, no-cache rule covers the iOS test tasks, release included.
- **Exclusions.** `check-local.sh`'s jvm part also excludes `:kvid-embed-onnx:iosSimulatorArm64ReleaseTest`.
- **Negative checks.** A missing fixture, stale XML and a no-op measurement each fail, both locally and in CI's step logic, before the gate is accepted.

### R10. iPhone measurement entry point and protocol (P2; replaces Task 9's device part)

- **Shared code.** The benchmark logic and the corpora (the Milestone 3 notes and `mixed`) move from the tests into `kvid-embed-onnx/src/measureMain`. It is a non-published source set compiled into a separate `kvid-embed-onnx-measure` module that the tests and the host app consume. It contains no test hooks and no fixture machinery.
- **Entry point.** A small Kotlin function, `runMeasurement(configJson: String): String`, returns a JSON result or a JSON error, never throws across the Swift boundary, and verifies the model hashes before timing.
- **Host app.**
  - The host app and its framework are built in **Release**.
  - Work runs off the main thread, and paths resolve inside the app container.
  - Each configuration runs in a fresh process: the host takes a configuration argument and exits.
  - The order follows the counterbalanced, repeated protocol of the [mobile-indexing review, answer 4](mobile-indexing.md#4-device-protocol-and-statistics).
- **Recorded:**
  - `phys_footprint` at open, during the call (sampled), after the call and after close;
  - the sampling overhead;
  - real and padded tokens;
  - raw lines with build and device metadata.
- **If signing blocks the run:** the physical items are marked **skipped**, and their acceptance stays open. Simulator numbers do not fill them.
- **App size.** Two otherwise identical Release apps, one calling the live ORT path and one not. Compare the stripped executables and the installed-app sizes, including `__DATA_CONST`, `__LINKEDIT`, the signature and any embedded framework, with the model bytes reported separately.

### R11. Leak checks state what they prove (P3; amends Task 6)

- `leaks` attached to a paused test process is preferred.
- The fallback loop runs 200 open/embed/close cycles after an explicit warm-up, on success and failure paths. It samples `phys_footprint` and the per-kind native counters (R5) and states its tolerance.
- The result is worded "no growth detected", never "zero leaks verified".
- The once-only process state (R2) is excluded explicitly from per-session expectations.

### R12. Smaller amendments

- **Open questions.**
  - *Q1:* the `expect class` is kept. The JVM, Android and iOS `actual` declarations are written out fully, with defaults only on the `expect`, and the backend types are private.
  - *Q2:* `commonTest` is kept, and the old `srcDir` registrations are removed so nothing compiles twice.
  - *Q3:* R2's policy.
- **Environment.** One process-lived `OrtEnv`, created once under the R2 guard and published only after its setup succeeded. A failed, unpublished creation is released.
- **Execution providers.** CPU only: no Core ML or other EP is registered, even though the archive contains them.
- **Portability test.** A store indexed on the JVM is copied to the iOS simulator test. There it must reopen with an equal spec, embed nothing again, and return the same top hits.

### R13. Link spike result (October 25; settles D2 and R7)

Run on `phase-5-ios-embedder` before any backend code. Everything below was executed, not inferred.

- **Runtime and cinterop.** Task 1 and R6 hold:
  - a cold download, then a verify without downloading;
  - a tampered header, thinned archive and ZIP each recovered once;
  - a second mismatch fails, naming the file;
  - JVM-only task graphs never schedule the runtime;
  - the configuration cache is reused.

  The cinterop needs `kotlin.mpp.enableCInteropCommonization=true` in `gradle.properties`, so that `iosMain` and `iosTest` see it.
- **Test binaries.** `OrtLinkSpikeTest` on the simulator printed `api=30 version=1.30.0`, created an environment, disabled telemetry events, and opened a session over the real model (3 inputs, 1 output). It then released everything. Both test executables have `minos 15.1`, which needed `-Xoverride-konan-properties=osVersionMin.ios_arm64=15.1;osVersionMin.ios_simulator_arm64=15.1`, because Kotlin/Native defaults to 15.0 and its link prints no warning. R8's check is therefore an explicit `vtool -show-build` assertion, not a warning.
- **Static umbrella (`isStatic = true`): self-contained.** The framework archive contains the Kotlin object, `sqlite3.o` from kvid-core and one `prelinked_objects.o` holding all of ORT.
  - `swiftc -F … -framework KvidSpikeStatic` links on the simulator and the device slice with no other flags. The link map attributes `_OrtGetApiBase` and 120,762 symbols to the framework.
  - The Kotlin object carries autolink entries for `-lc++` and the system frameworks.
  - With autolinking off, exactly the podspec's flags are needed.
  - CoreML is then linked strongly instead of weakly, which is harmless at 15.1.
- **Dynamic umbrella: also works.** It must be embedded and signed by the app; this was tested with an ad-hoc signed `.app` on the simulator.
- **Correctness through Swift.** Both simulator apps (static and dynamic) ran one inference on `[101, 7592, 2088, 102]`. They printed `shape=[1, 4, 384] cls[0..2]=[0.14310633, -0.21255468, 0.08049059]`, identical to Python ONNX Runtime 1.30.0 on the same input.
- **Decision: ship a static umbrella that contains ORT.**
  - This is one framework added in Xcode with "Do Not Embed". It is the same shape as KvidCore.
  - No CocoaPods, and **no separate `onnxruntime.xcframework`**: that part of R7's static option would put two copies of ORT in the app, which R7 forbids.
  - The umbrella exports the kvid-core API, because it already contains a Kotlin runtime and kvid-core's SQLite, so an app links it **instead of** KvidCore, never beside it.
  - The README lists the podspec flags for builds that disable autolinking. ORT's `LICENSE` ships with the umbrella.
  - The dynamic umbrella stays a documented fallback.
- **Gates adjusted.**
  - Consumer link tasks take ORT from the cinterop klib (about 46 MB embedded), not from the runtime directory, so R6's verify dependency applies inside `kvid-embed-onnx` only, which builds that klib.
  - Kotlin/Native result class names are `iosSimulatorArm64Test.com.kvid.embed.<Class>`; R9's `--require` patterns use them.
  - On a Mac, `./gradlew build` (and so check-local's jvm part) now compiles the iOS cinterop and triggers the runtime download once.

## Review

_October 25, 2026, Codex. Reviewed the draft above against `ios-embedder.md`, ADR 0002 and the JVM/Android implementation at `0d857813267a6a3346e9522e08b6473aa52abdb9`. Read the supplied iOS archive and headers; independently recomputed the ZIP SHA-256 (`e6f1670c14406fd9f082bb400ab197a9b0a9646058ca6366e440642e2b54a2ea`), confirmed API version 30, the slice inventory and static-archive format, and found the POSIX telemetry functions in the device slice with `nm`. Also checked the tagged upstream ORT 1.30.0 source and coroutine 1.11.0 Native source. This is a plan/source/artifact review: no Gradle, compiler/link spike, simulator or physical-device runs were performed. The static-framework packaging outcome and runtime behavior remain unverified._

### Findings, ordered by priority

**[P1] Keep synchronization alive after `close` — D4, lines 90–94.** Freeing the rwlock while holding it, then unlocking/destroying it, accesses freed memory. Merely reordering those operations is insufficient: a concurrent waiter, a second `close`, or the required nonempty `embed` after close would still use a destroyed lock. Preserve synchronization for the backend object's entire callable lifetime. A concrete alternative is an object-owned `NSCondition` with OPEN/CLOSING/CLOSED state and an active-inference count: register runs under the condition, reject new runs once closing starts, decrement/signal in `finally`, and have one closer wait for zero and release native resources exactly once while other closers await CLOSED. If retaining pthreads, specify a separate lifetime-safe lock owner and eventual cleanup after no method can access it; do not destroy the lock in public `close`. Check pthread return codes and keep lock/unlock on one thread with no suspension. Protect the whole synchronous backend operation, including tensor creation, output copy and cleanup, against session teardown. [Apple's rwlock destruction contract](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man3/pthread_rwlock_destroy.3.html).

**[P1] Replace the per-open telemetry workaround with an explicit initialization policy — Task 4, lines 166–174.** With a preexisting `ORT_DISABLE_TELEMETRY=0` or empty value, `setenv(..., overwrite=0)` leaves full suppression off, yet test (a) passes because the variable exists. A successful C API call does not repair that guarantee: tagged 1.30 source leaves the uploader running and permits `ProcessInfo` under runtime API suppression. The full opt-out is latched during POSIX-provider initialization, potentially before this library's `OrtEnv` acquisition. Set/check a truthy value once, under the same once-only initialization guard, **before any ORT access by kvid**, and check `setenv`'s return code. Define conflicting false values explicitly: reject them for this no-telemetry contract, or adopt a documented process-wide forced-disable policy; do not silently preserve them while claiming suppression. Continue calling/checking `DisableTelemetryEvents` through the C API as required by the brief. Document that unrelated ORT users can initialize first and that a launch-time host setting is required to cover them. Do not promise a private per-embedder switch or restore the variable after initialization. [ORT 1.30 environment parsing](https://github.com/microsoft/onnxruntime/blob/v1.30.0/onnxruntime/core/platform/telemetry_environment.h), [POSIX implementation](https://github.com/microsoft/onnxruntime/blob/v1.30.0/onnxruntime/core/platform/posix/telemetry.cc).

**[P1] Update every embedding-test presence gate when moving the tests — D5 and Task 7, lines 114 and 208.** `scripts/check-local.sh:57` currently implements `have_embedding_tests()` by checking for `src/jvmAndAndroidTest`. Removing that directory makes fixture requirements, JVM/Android required embedding classes, parity-line checks and the separate Android measurement silently disappear. Reusing the same detection rule for iOS repeats the defect. Replace the historical directory heuristic with an explicit required suite or a stable file in its new location, update all callers/messages, and retain the existing Android-only telemetry requirement. Demonstrate that removing a required fixture or required result fails each platform's check after the move. This is a regression caused by the proposed relocation, not an objection to `commonTest`.

**[P2] Make telemetry verification isolate process state and observe startup — Task 4, lines 170–173.** The negative-control idea and explicit “inconclusive” outcome are good, but the test currently checks only a variable's existence and an API status. Run a dedicated host/probe from a fresh process for each condition: production suppression, API-only suppression, and a deliberate control with neither suppression mechanism. Test unset/truthy/false/conflicting environment cases separately. Inspect the **child's** environment: ORT 1.30 also suppresses initialization for CI flags and `ORT_RUNNING_UNIT_TESTS`, so an inherited flag can invalidate a control. Never reuse a process whose suppression is already latched. Start capture before launch, use identical workload/network conditions and an observation window long enough to see the control, and retain DNS/connection evidence with process attribution. `log stream` and `nettop` are useful diagnostics, but a quiet snapshot is not proof of no attempted traffic; add continuous packet/connection instrumentation and account for redaction, brief connections and delayed upload. Run the deliberate telemetry control only in a disposable test host with synthetic data. A failed control leaves network verification inconclusive and must not turn into “verified off” in README/ROADMAP. The ordinary CI test can verify initialization policy and C-call success without claiming network proof. [ORT's CI/test/opt-out predicates](https://github.com/microsoft/onnxruntime/blob/v1.30.0/onnxruntime/core/platform/telemetry_environment.h).

**[P2] Keep native blocking tests off a shared dispatcher bottleneck — D5, lines 120–123.** `runTest { withContext(Dispatchers.Default) { ... } }` does give real delays there, but replacing the dedicated closer thread with another Default coroutine can deadlock on a small pool: one worker waits inside the inference hook, another blocks in `close`, and the orchestrator cannot resume to release the hook. Keep orchestration on the test scheduler and use dedicated, explicitly closed test threads/dispatchers for blocking inference/close work; keep measurement delays/clocks on a real-time dispatcher. A `CompletableDeferred` is suitable for suspending notification, but the synchronous backend hook needs an actual blocking latch/condition, with timed waits and `finally` release. Use atomics for cross-thread hook publication and counters, and serialize tests that mutate global hooks. Bound every wait/join, clean up both workers on failure, and add an outer process timeout: `runTest` cancellation cannot stop a stuck pthread wait or native `Run`. Preserve the cancelled-open test's “cancel during return handoff” condition using a start barrier or lazy job so its callback cannot race assignment of the job reference. Do not infer that `delay(300)` proves the closer started; use an explicit notification. [Coroutine test timing/timeout contract](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/run-test.html).

**[P2] Complete the native ownership and error-unwind design — D4 and Task 5, lines 78–89 and 178.** The pinning approach itself is correct, but the ownership list omits required auxiliary allocations. Add a resource ledger covering owned `OrtMemoryInfo`, session/validation `OrtTypeInfo`, owned `OrtTensorTypeAndShapeInfo`, allocator-returned names, every input/output `OrtValue`, session/options and statuses. Free allocator-returned strings with that allocator's free function, never `nativeHeap.free`; do not free the borrowed default allocator or the tensor view returned by `CastTypeInfoToTensorInfo`. Copy `GetErrorMessage` to a Kotlin string **before** releasing its status. Initialize out-pointers to null, register successful allocations immediately and release only initialized results on partial failure. Keep name strings and pointer/shape arrays valid through `Run`; never retain `memScoped` pointers as backend fields. Use byte counts (`Long.SIZE_BYTES`/`Float.SIZE_BYTES`) with checked size arithmetic, confirm FLOAT plus exact `[batch, seqLen, 384]` before copying, and release input values before their pins end and output values after copying. Add focused failure-path tests around allocation/session creation, validation, warm-up, cancellation and output inspection; a backend `live` count alone cannot detect leaked type info, names or statuses. The supplied C header explicitly says caller-owned tensor data is not freed by `ReleaseValue`, and output data pointers expire when their value is destroyed.

**[P2] Verify restored runtime inputs even when Gradle would skip preparation — D3 and Task 1, lines 63–73 and 147–149.** A declared-output preparation task can be UP-TO-DATE or restored from cache without executing the checksum checks; pinning only the original slice binaries also leaves consumed headers and thinned outputs unchecked. Separate always-executed integrity verification from cacheable extraction/thinning, or otherwise wire verification so it runs before cinterop/link consumers even when producers are skipped. Cover the ZIP, extracted headers (including the error/EP headers included by the C header), source slices and derived target archives; derive trusted extracted inputs from the verified ZIP rather than trusting an old directory. Specify fail/re-extract behavior and test tampering with an extracted header and a thinned archive after a successful build. Include cache location, target and relevant tool inputs in task modeling, use atomic replacement and a cross-process cache lock so concurrent worktrees cannot observe partial downloads/extraction. This makes the proposed second-run/tamper acceptance checks achievable.

**[P2] Make the consumer spike reference real ORT work and cover both slices — D2, Tasks 2 and 8, lines 49–57, 155–157 and 213–215.** `staticLibraries` bundles a native archive in the **cinterop klib** and supplies it to Kotlin/Native linking; that alone is not evidence that a redistributed static framework is self-contained or that Swift receives all required system-library flags. Keep the spike, but export a Kotlin entry that reaches `OrtGetApiBase` and creates a session/warm-up, and have Swift call it so dead stripping cannot remove the path under test. Link a genuine iOS-simulator target from a clean consumer directory without the preparation cache/header search paths; inspect the final binary/link map, not just `nm` output from an archive. Build/link the arm64 device framework/XCFramework slice too (no device or signing is needed for that artifact check). Choose and document one successful distribution mode before claiming no extra runtime is needed. A dynamic kvid umbrella must be embedded/signed by the app; a separate static ORT framework is linked with “Do Not Embed,” with its C++/system frameworks listed. Avoid linking both an embedded ORT copy and a separate copy, or independently exporting multiple Kotlin runtimes. Keep `kvid-core`'s published lexical-only framework free of ORT; use a separate optional umbrella for the consumer check. [Kotlin cinterop archive embedding](https://kotlinlang.org/docs/native-definition-file.html#include-a-static-library), [framework dependency export](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html#export-dependencies-to-binaries).

**[P2] Enforce the deployment floor in builds, not only README — Tasks 2, 8 and 10, lines 154–157, 214 and 237.** The downloaded runtime's stated iOS minimum is 15.1, while the plan only documents that number. Set/verify compatible deployment targets for both Native binary targets, consumer frameworks and measurement hosts; inspect Mach-O build-version metadata and reject a link that merely warns about a newer archive. Preserve the sample's separate 18.5 requirement and do not raise the minimum of lexical-only `kvid-core`. CI should compile both iOS slices even if the optional signed iPhone run is unavailable.

**[P2] Gate fresh debug and release results independently — Task 7, lines 197–208.** The shown verifier invocation defaults to scanning both `kvid-core` and `kvid-embed-onnx`; it does not encode any of the listed required classes. Specify `--module kvid-embed-onnx` and explicit `--require module:class` arguments, clear the relevant result directory or use `--since`, and verify the actual Native XML class/skip/output representation before copying the JVM grep. Require tokenizer fixture variables/line as well as embedding parity/spec/store/lifecycle output. Gate the separate release run on a fresh required measurement class and marker from **its own** result directory; a debug measurement must not satisfy the optimized gate. Explicitly invoke/gate at least one configured tuning run where Task 5 says tuning passed: with `KVID_EMBEDDING_TUNE` unset, that test currently returns successfully without doing work. Forward the pause/leak/tuning settings through `SIMCTL_CHILD_*` when used, keep the existing no-up-to-date/no-cache execution policy for required runs, and update the JVM `build` exclusions for the new iOS release test task to avoid duplicate/mislabeled platform runs in `check-local`. Negative checks for missing fixture, stale XML and a no-op measurement should fail before the gate is accepted.

**[P2] Specify a release measurement entry point the iPhone app can actually link — Task 9, lines 223–230.** The proposed runners live in `commonTest`, which a production framework dependency does not export. Extract shared benchmark logic/corpora into a dedicated measurement-support source set/module consumed by the tests and host, with a small Kotlin entry point that reports failures safely across the Swift boundary; keep test hooks and fixture machinery out of the public production API. Build **both** the host app and its Kotlin framework in Release/optimized mode, run work off the main thread, resolve paths in the app container and verify model hashes before timing. Use a fresh process/session per configuration and the counterbalanced/repeated thermal protocol in [mobile-indexing.md, Review answer 4](mobile-indexing.md#4-device-protocol-and-statistics), with open/call/after-close footprint, sampling overhead, real/padded token counts, and raw lines plus build/device metadata. If signing prevents the run, mark physical measurements skipped and leave their acceptance item open; simulator numbers cannot fill it. For app size, compare otherwise equivalent linked Release apps with an ORT path demonstrably live. Measure complete stripped executable/installed-app deltas with model bytes reported separately: `__TEXT + __DATA` omits `__DATA_CONST`, `__LINKEDIT`, signatures and any embedded dynamic framework, so it cannot establish the runtime's app-size cost.

**[P3] Distinguish a bounded memory trend from proof of no leaks — Task 6, lines 186–189.** Two hundred open/embed/close iterations with flat footprint can detect substantial growth but cannot prove zero leaks of small ORT objects; allocator retention and Kotlin/Native GC also change footprint independently. Keep it as a fallback stress check, sample the series after an explicit warm-up/idle policy and report its tolerance, allocation counters and limitations. Run failure/cancellation paths as well as success. The retained process environment/once state is intentional and should be excluded explicitly from per-session leak expectations. Require session/resource counters to return to baseline even when `leaks` cannot attach; describe that outcome as “no growth detected,” not “zero leaks verified.”

**[P3] Finish the common-test portability seams and stable error contract — D1/D4/D5, lines 35, 89 and 115–132.** The migration table omits `System.nanoTime` in both measurement classes and `String.format(Locale.ROOT, ...)` in `EmbeddingFixture.kt`. Replace them with monotonic common timing and a locale-independent common formatter or narrow platform helpers, and replace `fixtureFile`'s current `java.io.File` return type with a common path/reader contract. Specify the internal timed-open declaration and its actuals so moved tests retain that seam. Preserve exception categories and stable diagnostic prefixes/file names, but do not promise byte-identical underlying ORT error text across Java/C builds. In the streaming digest implementation, distinguish `ferror` from EOF, check CommonCrypto return values and close on every path so an I/O failure cannot publish a hash of a prefix. Add empty/small/multi-block known-byte digest cases as well as the model fixture. `platform.CoreCrypto` is the correct CommonCrypto package in the installed Kotlin 2.5.0-Beta1 platform definition; that import is not a finding.

### Answers to the three open questions

1. **Use the `expect class`.** Its cost is acceptable for this unpublished module and it preserves the existing public `OnnxEmbedder.open` shape for common callers. Keep defaults on the expect declarations and make the required class/companion/member actual declarations explicit; this is more than adding one keyword. Keep native pointer/backend types private to the actual. A plain common `OpenTimings` data holder plus an explicitly declared internal expected timed-open seam is sufficient. A factory is viable if the owner prefers it, but `Embedder & AutoCloseable` is not a valid general intersection return type in Kotlin; it needs a named combined interface. There is no reason to introduce that extra public abstraction just for this port.
2. **Use `commonTest`, with narrow platform helpers.** It is preferable to another manually attached shared directory because these tests now cover all configured targets. Preserve the Android device test source-set-tree wiring and remove the old `srcDir` registrations to avoid duplicate compilation. Do the filesystem/clock/formatter/atomic/latch conversion without weakening the parity, store or lifecycle assertions, fix the gate regression above, and keep platform-only telemetry/network probes in their platform tests or a separate host. Do not apply the blanket Default-dispatcher rewrite to blocking synchronization tests.
3. **A process-environment mutation is acceptable only as a documented, once-only startup policy; the proposed `overwrite=0` per-open implementation is not acceptable as a no-telemetry guarantee.** The flag is real in 1.30, rather than merely a suggestive binary string. Its effect is global and latched, can affect other ORT consumers, and cannot retroactively prevent earlier initialization. Choose the explicit conflict/early-initialization policy in the P1 finding, retain the required C API call, and document the side effect regardless of whether the manual control produces traffic. If that global policy cannot be supported, the implementation must report the narrower API-only guarantee or revisit the runtime artifact; an environment assertion cannot substitute for the missing guarantee.

### Brief/ADR coverage and accepted parts

| Requirement | Assessment after review |
|---|---|
| ORT 1.30 C API, pinned official XCFramework, no committed runtime | Archive/hash/API facts independently confirmed; D3 needs the integrity/task amendment. Check `GetApi(30)` for null and report the linked runtime version before dereferencing the function table. |
| `iosArm64` and `iosSimulatorArm64`, CocoaPods-free consumers | Both slices exist. The linking spike is the right gate, with both targets, live references and deployment flags as above. A Swift package/publication is not required in this scope, but the chosen artifact's contents and host link/embed steps must be explicit. |
| Common open API, same spec and unchanged common embedding pipeline | D1 is appropriate. Keep tokenizer/pooling/normalization in existing common code and produce exactly the same model/vocab digests, pooling ID, normalization, metric and token limit. Existing fixture/spec/store tests exercise this; add a cross-platform-generated store reopen/query check as useful portability evidence. |
| Streaming CommonCrypto digests | Correct approach and package; add I/O failure checks and small deterministic fixtures. |
| Pinned tensors and output ownership | Nested `usePinned` from tensor creation through synchronous `Run` and input `ReleaseValue` is correct. No suspension or escaped pointers in that region. Copy output before releasing its value. Add the complete resource/error ledger above. |
| Concurrent embedding, safe/idempotent close, cancelled open | The cancellation structure and between-batch checks match the JVM intent. The proposed lock teardown fails the lifecycle requirement and must change before implementation. |
| Shared environment and dispatchers | Retaining one deliberately process-lived `OrtEnv` reference is reasonable. Initialize it once under synchronization, publish only after required setup succeeds, clean up a failed unpublished creation and do not acquire/leak a new reference per open. `Dispatchers.IO` **is available on Native in coroutines 1.11.0**; the open design does not need an invented replacement. See the [pinned Native dispatcher source](https://github.com/Kotlin/kotlinx.coroutines/blob/1.11.0/kotlinx-coroutines-core/native/src/Dispatchers.kt). |
| Telemetry disabled through the C API | Explicitly present, but global startup suppression and independent verification need the P1/P2 corrections. Keep this CPU-only port from registering Core ML/other EPs merely because the archive contains them. |
| Existing test classes on iOS with required fixture and CI gates | Intended coverage matches the brief; test migration, thread handling and fail-closed gates need the changes above. Keep absolute-difference/unit-norm reporting, ≥0.9999 Python parity and ≥0.99999 batch-vs-single assertions. |
| Optimized simulator and available iPhone measurements, footprint and app delta | Simulator reference status is correct. Device signing is a legitimate external prerequisite, but physical Release configuration, shared runner, statistics and complete size accounting must be specified; skipped evidence remains open. |
| Optionality and scope | Keep ORT out of `kvid-core` and lexical-only apps. Model variants, background scheduling and publishing remain outside this port, consistent with the brief/ADR. Preserve the runtime's license/notices in eventual consumer packaging, and document coexistence/version conflicts when a host also links ORT. |

**Overall assessment:** the architecture is suitable, but the draft is not implementation-ready until the three P1 findings and the native cleanup/test/linking gates above are incorporated. The key unresolved experiments are static-framework consumption and actual telemetry/network behavior in a clean host. Treat their outcomes as recorded evidence, not assumptions inferred from headers, strings or passing unit-test status. The mobile-indexing review's distinction between microbenchmarks and sustained full-store measurements also applies when the iPhone numbers are later used for a product/default decision.
