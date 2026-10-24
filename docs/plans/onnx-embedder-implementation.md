# Implementation plan: ONNX Runtime embedder (JVM and Android)

_Written October 24, 2026 by the local implementer. Turns [onnx-embedder.md](onnx-embedder.md) into ordered tasks. Code goes on `phase-5-onnx-embedder` (from `phase-5-semantic-search` at `df02d68`); planning documents stay on this branch. Status: revised after review by Codex (`gpt-6.1-sol`, high) and Claude Opus (high); see "Review record" at the end._

## Facts checked before planning

- `BAAI/bge-small-en-v1.5` at revision `5c38ec7c405ec4b44b94cc5a9bb96e735b38267a` has `onnx/model.onnx`: 133,093,490 bytes, LFS SHA-256 `828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35` (from the Hub's `X-Linked-ETag`; the download script re-verifies it). No `optimum` export is needed.
- Maven Central has ONNX Runtime 1.30.0 (the plan's pin) and 1.31.0 (newest). Keep 1.30.0 as ADR 0002 and the plan state; the Python reference uses `onnxruntime==1.30.0` (wheels for CPython 3.11 to 3.14 on macOS arm64 and manylinux x86_64/aarch64).
- `onnxruntime-android:1.30.0` declares `minSdkVersion 24`; kvid is 23. **Owner decision (October 24): `kvid-embed-onnx` alone uses minSdk 24; `kvid-core` and the sample stay at 23.**
- The `onnxruntime-android` AAR registers `ai.onnxruntime.TelemetryInitializer`, a `ContentProvider` that runs at app start, loads the native library and creates a telemetry `HttpClient`; it also adds `INTERNET` and `ACCESS_NETWORK_STATE` permissions. `OrtEnvironment.setTelemetry(boolean)` exists. **Owner decision (October 24): the module's manifest removes the provider with `tools:node="remove"`, `open` calls `setTelemetry(false)`, and a device test checks that the provider is absent. The permissions stay (an app decides), and are documented.**
- AAR native libraries (uncompressed): arm64-v8a 32 MB, armeabi-v7a 22 MB, x86 38 MB, x86_64 38 MB. Exact byte counts are recorded in Task 8.
- `gradle.properties` enables the Gradle build cache (`org.gradle.caching=true`), so an unchanged test task can be `UP-TO-DATE` or `FROM-CACHE` and print nothing.
- Local machine: macOS with Xcode, `adb` (a Pixel 8 on API 37 is attached at times), AVD `Pixel_9` (API 35, arm64), Python 3.11. CI uses Python 3.12 and an API 35 x86_64 emulator.
- Java API used: `OrtEnvironment.getEnvironment()`, `createSession(String, SessionOptions)`, `SessionOptions.setOptimizationLevel(ALL_OPT)`, `setIntraOpNumThreads`, `OrtSession.getInputNames/getOutputInfo`, `OnnxTensor.createTensor(env, LongBuffer, long[])`, `run(Map, Set<String>)`, `OnnxTensor.getInfo().getShape()`, `getFloatBuffer()`; identical in the JVM jar and the AAR. ORT's Java docs require `SessionOptions` to stay open until sessions created from them are closed.

## Deviations from the plan document

- Python scripts go in `kvid-embed-onnx/scripts/` beside the existing tokenizer scripts; `check-local.sh` and `verify_device_tests.py` go in a new root `scripts/` directory.
- One pin file, `kvid-embed-onnx/scripts/model-pins.json` (model, revision, SHA-256 of `onnx/model.onnx`, `vocab.txt`, `tokenizer.json`), replaces the digests in `ci.yml`'s `TOKENIZER_FIXTURE_ARGS`. CI and the local check both read it; nothing parses YAML.
- The tokenizer fixture is built from files fetched by `download_model.py` (`make_tokenizer_fixture.py --tokenizer-json --vocab`, which already exists), so every download goes through one verified path.
- A shared `kvid-embed-onnx/scripts/requirements.txt` pins `tokenizers`, `onnxruntime` and `numpy` for CI and the local check.
- The embedding device tests run in a new parallel CI job, `android-embed`, with its own emulator, instead of extending `android-store`, which is already close to its 25-minute limit.
- `BertEmbeddingModel`'s constructor is `internal` in this step; only `BGE_SMALL_EN_V1_5` is public. Output name and pooling are part of the model contract, not free parameters, so two embedders with equal specs cannot select different graph outputs. Opening other models is a later API decision.
- Normalization accumulates the squared norm in `Double`, not float32 as the plan document says. Cosine does not depend on scale, the ADR tolerance is far wider than the difference, and the store normalizes the same way (`VectorCodec.prepare`). The parity test additionally checks unit norm.

## Task 0: baseline (before any change)

1. Worktree for `phase-5-onnx-embedder` at `df02d68`. `./gradlew build` with the tokenizer fixture required; record the result and, with `./gradlew build --dry-run`, which iOS test tasks `build` runs on macOS (`check` depends on `allTests`).
2. Same baseline for the iOS tasks CI runs and the Android device tests, on the emulator and on the phone if attached. The phone run doubles as the spike for reading `/data/local/tmp` from an instrumented process (the tokenizer test already reads `vocab.txt` there).
3. If the base is red locally, find out whether it is environmental or a defect before writing new code, and report it. The recent CI failure on this branch (run 40012171530, iOS sample background close) is fixed at `df02d68` (run 40016745410 green).

**Done:** baseline results written down with the device, OS and Gradle task list.

## Task 1: local pre-push check

### `scripts/check-local.sh`

- Bash, run from the repository root. **Each part runs in its own `bash -euo pipefail` process** (a function file sourced by a child, or one script per part), and the parent records that process's exit code. Never `part || mark_failed` in one shell: that disables `-e` inside the part and hides failures.
- Exports `KVID_SKIP_IOS_QR_RENDERING_TESTS=true` for the whole run, as CI does.
- Statuses per part: `RAN`, `SKIPPED (<reason>)`, `FAILED`. Exit codes: `0` when every part ran and passed, `2` when nothing failed but something was skipped (`PARTIAL`), `1` when anything failed. The summary prints each part, its log path (`build/check-local/<part>.log`), the device used, and the overall result.
- Gradle invocations run one at a time.

Parts, mirroring `.github/workflows/ci.yml`:

1. `python`: create or reuse `build/check-local/venv` (git-ignored via `build/`), install `kvid-embed-onnx/scripts/requirements.txt`.
2. `fixtures`: `download_model.py` into `${KVID_MODEL_CACHE:-$HOME/.cache/kvid/models}` (verifies digests even when files exist); tokenizer fixture from the downloaded `tokenizer.json`/`vocab.txt`; embedding fixture. Outputs in `build/check-local/`.
3. `jvm`: `./gradlew build -x <each iOS test task found in Task 0>` and `:kvid-examples:compileKotlin`, with `KVID_REQUIRE_TOKENIZER_FIXTURE=true` and `KVID_REQUIRE_EMBEDDING_FIXTURE=true`; then check the JUnit XML of `:kvid-embed-onnx:jvmTest` for the parity test classes (passed, not skipped). The `[kvid-tokenizer]`/`[kvid-embedding]` lines are copied into the log, not used as the gate.
4. `ios` (macOS with `xcrun simctl` only): `:kvid-core:iosSimulatorArm64Test`, `:kvid-core:iosSimulatorArm64ReleaseTest --tests com.kvid.store.SearchMeasurementTest`, `:kvid-embed-onnx:iosSimulatorArm64Test` with the tokenizer fixture required, `:kvid-sample:shared:iosSimulatorArm64Test`.
5. `android`: device chosen as `ANDROID_SERIAL` if set, else the only `adb devices` entry in state `device` (`unauthorized`/`offline` ignored); more than one without `ANDROID_SERIAL`, or none, is `SKIPPED` with the reason. Steps:
   - delete `*/build/outputs/androidTest-results/connected` for both modules;
   - `adb logcat -G 16M -c`; core device tests with the package argument; copy `[kvid-measure]`;
   - push fixtures with `adb shell rm -rf <target>` first (an existing target directory makes `adb push` nest the copy), except that the model is not re-pushed when `adb shell sha256sum` on the device already matches;
   - `adb logcat -c`; embed device tests with the four instrumentation arguments; then a separate measurement run (Task 8);
   - `scripts/verify_device_tests.py` with the required test classes;
   - record the device model, API level and the serial from `connected/<device>/` in the summary.
6. `sample` (only with `--with-sample`): iOS sample `xcodegen`/`xcodebuild` and `smoke-test.sh`; Android `installDebug` and smoke test when a device is selected.

Options: `--skip-ios`, `--skip-android`, `--with-sample`, `--only <part>`.

### `scripts/verify_device_tests.py`

Replaces the inline CI step. Scans only `<module>/build/outputs/androidTest-results/connected/**/*.xml` with modification time after `--since` (the run's start), prints counts and failures as today, and takes `--require <module>:<TestClass>` (repeatable): each required class must have at least one test case, none failed, none skipped. CI uses it with the same arguments.

### Bootstrap

Task 1 lands first, with `requirements.txt` and `model-pins.json`, and only the parts that exist (the embedding fixture and required classes are added as Tasks 6 and 7 land). **Bootstrap done:** two consecutive runs on the base commit both exit 0 with every part `RAN`; a deliberately altered tokenizer fixture case makes it exit 1. **Final done:** the same with all embedding parts present.

## Task 2: build wiring

- `gradle/libs.versions.toml`: `onnxruntime = "1.30.0"`; libraries `onnxruntime-jvm` and `onnxruntime-android`; `androidEmbedMinSdk = "24"` with a comment naming the AAR's requirement.
- `kvid-embed-onnx/build.gradle.kts`:
  - `commonMain`: `api(project(":kvid-core"))`, `implementation(libs.kotlinx.coroutines.core)`.
  - `commonTest` and `androidDeviceTest`: `implementation(libs.kotlinx.coroutines.test)`.
  - `jvmMain`: `implementation(libs.onnxruntime.jvm)`; `androidMain`: `implementation(libs.onnxruntime.android)`.
  - Shared sources: `jvmMain` and `androidMain` each add `kotlin.srcDir("src/jvmAndAndroidMain/kotlin")`; `jvmTest` and `androidDeviceTest` each add `src/jvmAndAndroidTest/kotlin`. IntelliJ reports "duplicate content roots" and assigns the directory to one module; compilation is unaffected. Note this in the commit.
  - `minSdk = libs.versions.androidEmbedMinSdk`.
  - Fixture plumbing generalized to `KVID_EMBEDDING_FIXTURE_DIR`, `KVID_REQUIRE_EMBEDDING_FIXTURE` and `KVID_EMBEDDING_MEASURE` (task inputs, `SIMCTL_CHILD_` forwarding).
  - **When any `KVID_REQUIRE_*` or `KVID_EMBEDDING_MEASURE` is `true`, test tasks never come from cache or count as up to date:** `outputs.upToDateWhen { false }` and `outputs.doNotCacheIf("fixture parity and measurements must execute") { true }` on `Test` and `KotlinNativeTest`.
  - If the KMP Android DSL allows it, restrict the device-test APK's packaged ABIs to the device's ABI to keep the APK small; otherwise leave it and rely on the CI disk size.
- `kvid-embed-onnx/src/androidMain/AndroidManifest.xml` (the KMP library plugin's default manifest location), with `xmlns:tools` and `<provider android:name="ai.onnxruntime.TelemetryInitializer" tools:node="remove" />` inside `<application>`, and a comment. Check the built AAR's manifest contains the remove marker.
- No change to `kvid-core` or the sample.

**Done:** `./gradlew :kvid-embed-onnx:build` and `:kvid-embed-onnx:compileKotlinIosSimulatorArm64` succeed; a second run with the fixture required re-executes the tests.

## Task 3: common embedding pipeline (`commonMain`)

Files under `kvid-embed-onnx/src/commonMain/kotlin/com/kvid/embed/`:

- `BertEmbeddingModel.kt`: `class BertEmbeddingModel internal constructor(name, dimensions, pooling: Pooling, normalize: Boolean, maxInputTokens: Int, outputName: String)` with validation; public `companion object { val BGE_SMALL_EN_V1_5 }` (384, `CLS`, `true`, 512, `last_hidden_state`). `enum class Pooling(val id: String) { CLS("cls"), MEAN("mean") }`.
- `EncoderBackend.kt`: `internal interface EncoderBackend : AutoCloseable { fun run(inputIds: LongArray, attentionMask: LongArray, tokenTypeIds: LongArray, batch: Int, seqLen: Int): FloatArray }` returning `batch × seqLen × hidden` row-major. Implementations validate their own output shape (Task 5).
- `HiddenStates.kt`: `internal fun checkHiddenStatesShape(shape: LongArray, batch: Int, seqLen: Int, dimensions: Int)` requiring exactly `[batch, seqLen, dimensions]`, with a message naming expected and actual shapes. Pure, so it is tested in common code.
- `BertEmbedder.kt`: `internal class BertEmbedder(model, tokenizer, backend, override val spec, maxBatchSize: Int) : Embedder`.
  - Tokenize each text with `tokenizer.encode(text, model.maxInputTokens)`.
  - Split into sub-batches of `maxBatchSize` in input order; pad each to its longest sequence (pad id = the vocabulary's `[PAD]` id, `attention_mask` 0, `token_type_ids` 0).
  - Check the output length is `batch * seqLen * dimensions` (a second guard behind the backend's shape check).
  - Pool: `CLS` takes position 0; `MEAN` averages positions where the mask is 1 (`Double` accumulation).
  - Normalize when `model.normalize`: L2 with a `Double` sum; a zero vector stays zero.
  - Reject non-finite outputs with `IllegalStateException`.
  - `currentCoroutineContext().ensureActive()` between sub-batches; return only when every sub-batch succeeded.
  - Empty input list returns an empty list without calling the backend.
- Pure helpers (`pool`, `l2Normalize`, `padBatch`) are `internal` and unit-tested directly.

## Task 4: common unit tests (`commonTest`, fake backend, `runTest`)

`BertEmbedderTest.kt` with a `FakeBackend` that records each call and returns hidden states computed from position and token id:

- CLS picks position 0; masked mean ignores padding (a text alone and padded beside a longer text pools the same).
- Normalization: unit length; zero stays zero; `normalize = false` passes values through.
- Padding layout for a batch of uneven lengths.
- Ordering across sub-batches (`maxBatchSize = 2`, 5 texts) and the number of backend calls.
- Truncation: a long text sends exactly `maxInputTokens` ids ending with `[SEP]`.
- Empty list: no backend call.
- Wrong output length, non-finite output, and `checkHiddenStatesShape` with a same-element-count wrong shape (`[seqLen, batch, dims]`) and a wrong rank: clear exceptions, nothing returned.
- Cancellation between sub-batches stops further backend calls.

**Done:** green on `jvmTest`, `iosSimulatorArm64Test` and the Android device run.

## Task 5: ONNX Runtime embedder (`jvmAndAndroidMain`)

Files under `kvid-embed-onnx/src/jvmAndAndroidMain/kotlin/com/kvid/embed/`:

- `OnnxOptions.kt`: `data class OnnxOptions(val intraOpThreads: Int? = null, val maxBatchSize: Int = 16)` with validation.
- `FileDigest.kt`: streaming SHA-256 (`MessageDigest`, 1 MiB buffer), lowercase hex.
- `OrtEncoderBackend.kt`: owns the `OrtSession` **and its `SessionOptions`** (closed in that order, once). `run`:
  - holds a `ReentrantReadWriteLock` read lock for the synchronous call only (never across a suspension point; read holds are per thread); throws `IllegalStateException("OnnxEmbedder is closed")` once closed;
  - builds `LongBuffer` tensors `[batch, seqLen]`, adds `token_type_ids` only when the session declares it, requests only `model.outputName`;
  - checks the output is a float tensor and calls `checkHiddenStatesShape` with its actual shape on every run;
  - closes every tensor and the `Result` in `finally`; copies the `FloatBuffer` into a `FloatArray`.
  - `close` takes the write lock, so it waits for a running inference, then closes the session and options; idempotent; never closes the shared `OrtEnvironment`.
- `OnnxEmbedder.kt`: public API as in the plan document (`spec`, `embed`, `close`, `companion suspend fun open(modelPath, vocabPath, model, options)`).
  - `open`: the blocking work runs in `withContext(Dispatchers.IO)`, which assigns the created embedder to a variable outside the block; if `withContext` throws after the block completed (cancellation while returning), the outer `catch` closes that embedder and rethrows. Inside, each native resource is closed if a later step fails. `CancellationException` is always rethrown unchanged; other failures become `IllegalArgumentException`/`IllegalStateException` naming the file and expectation.
  - Steps: files exist and are readable; digest both; `OrtEnvironment.getEnvironment()`, `setTelemetry(false)` (an `OrtException` where telemetry is unsupported is ignored, with a comment); `SessionOptions` with `ALL_OPT` and `intraOpThreads` when set; session; inputs include `input_ids` and `attention_mask` and nothing else except `token_type_ids`; `model.outputName` exists, is a float tensor and, where static, has last dimension `model.dimensions`; warm-up embed of `""` with length check.
  - An internal `open` overload accepts a timing callback (digest, session creation, warm-up) for Task 8.
  - `spec`: `EmbeddingSpec(model = model.name, modelDigest = "sha256:<hex>", tokenizer = "bert-wordpiece-uncased sha256:<hex of vocab.txt>", dimensions, pooling = model.pooling.id, normalized = model.normalize, metric = COSINE, maxInputTokens = model.maxInputTokens)`.
  - `embed` runs on `Dispatchers.Default` and delegates to `BertEmbedder`. If `close` happens between sub-batches, the next backend call throws and nothing is returned.
- KDoc covers file provenance (the app supplies files), the spec, threading, Android minSdk 24 and the telemetry provider removal.

## Task 6: pins, download and reference fixture scripts

- `kvid-embed-onnx/scripts/model-pins.json`: model, revision, and the three file digests.
- `kvid-embed-onnx/scripts/requirements.txt`: `tokenizers==0.23.3`, `onnxruntime==1.30.0`, `numpy==<pinned, wheels for 3.11 and 3.12>`.
- `download_model.py OUT_DIR [--pins PATH] [--files vocab.txt tokenizer.json …]`: downloads from `https://huggingface.co/{model}/resolve/{revision}/…` to temporary names with retries and backoff, verifies SHA-256, renames into place; verifies existing files instead of trusting them; fails naming the file and both digests; writes `provenance.json` (model, revision, digests). Stdlib only. `--files` lets the iOS job fetch only the tokenizer files.
- `make_embedding_fixture.py OUT_DIR --model-dir DIR`:
  - reads `provenance.json` from `--model-dir` and re-verifies the digests;
  - cases: about 100 curated texts (queries, multi-paragraph notes, Unicode, CJK, emoji, accents, the empty text, two texts over 512 tokens) plus 100 seeded random texts from the tokenizer fixture's generator, loaded with the full `importlib.util.spec_from_file_location` / `module_from_spec` / `exec_module` recipe (scripts run with `-I`);
  - tokenizes with `tokenizers` (truncation 512, no padding); ORT `InferenceSession` with `providers=["CPUExecutionProvider"]` and `graph_optimization_level=ORT_ENABLE_ALL`, one text at a time, int64 `input_ids`, `attention_mask`, `token_type_ids`; CLS pool; L2-normalize in numpy float32; asserts every value is finite;
  - writes `embeddings.jsonl` (`{"text", "ids", "vector"}`, floats as `repr(float(x))`), `source.json` (provenance, versions of onnxruntime/numpy/tokenizers/Python, provider, optimization level, input/output names, case count), and hard-links `model.onnx` and `vocab.txt` into `OUT_DIR` (copy as fallback; never symlinks, which `adb push` may push as links).
- `check_reference_model.py` (manual, separate virtual environment with `sentence-transformers` and `torch`): compares fixture vectors with `SentenceTransformer` at the pinned revision; prints per-case minimum cosine and environment; exits non-zero below 0.9999 or on any non-finite value. Passing it is a prerequisite of Task 11.

**Done:** scripts run from a clean virtual environment on macOS; a tampered `vocab.txt` fails the download; a second download run verifies without re-downloading.

## Task 7: JVM and Android tests (`jvmAndAndroidTest`)

These tests use `runBlocking` (or `runTest` with an explicit long timeout), since real inference must not hit `runTest`'s 60-second default.

- `EmbeddingFixture.kt`: locates the fixture via `testEnvironment("KVID_EMBEDDING_FIXTURE_DIR")` with the tokenizer test's skip-or-fail rule. On Android, if opening a file under `/data/local/tmp` is denied, it copies it into the instrumentation's `cacheDir` through `uiAutomation.executeShellCommand("cat …")` (runs as shell) and opens the copy. (Pushing into the test app's own storage does not work: the connected task installs and uninstalls the APK around the run.)
- `OnnxEmbedderParityTest.kt`:
  - case count is positive and equals `source.json`'s;
  - token ids equal the fixture's for every case;
  - every vector has 384 finite values and unit norm within 1e-5;
  - embeds every case with `maxBatchSize = 8` and one text at a time;
  - cosine ≥ 0.9999 with the reference for every case; batched versus unbatched ≥ 0.99999;
  - prints `[kvid-embedding] cases=… min_cosine=… max_abs_diff=… batched_min_cosine=… platform=…` (platform from a small helper in each platform's test directory).
- `OnnxEmbedderTest.kt` (same fixture rule): spec fields and digests match `source.json`; `embed(emptyList())` is empty; `close` idempotent and `embed` afterwards throws; a missing model path and a vocabulary file given as the model fail naming the file; opening with `dimensions = 768` and with a wrong `outputName` (internal constructor) fails naming the expectation; concurrent `embed` from several coroutines matches serial results (cosine ≥ 0.99999); `close` while `embed` calls are running either completes them or makes them throw, never crashes or returns partial results; a cancelled `open` leaves no open session (checked through an internal counter of live backends).
- Android only (`androidDeviceTest`): `TelemetryProviderTest` asserts the test package has no `ai.onnxruntime.TelemetryInitializer` provider (`getPackageInfo(…, GET_PROVIDERS)`; `providers == null` counts as none).

**Done:** green on the JVM (macOS), the local API 35 emulator and the attached phone; with `KVID_REQUIRE_EMBEDDING_FIXTURE=true`, a missing fixture fails and an altered reference vector fails.

## Task 8: measurements

`OnnxEmbedderMeasurementTest.kt` in `jvmAndAndroidTest`, run only when `KVID_EMBEDDING_MEASURE=true` (otherwise it prints a skip line), asserting no time budget. On Android it runs in its own instrumentation run (`-Pandroid.testInstrumentationRunnerArguments.class=com.kvid.embed.OnnxEmbedderMeasurementTest`), so PSS is not inflated by earlier tests. It prints:

`[kvid-embedding-measure] platform=… open_ms=… digest_ms=… session_ms=… warmup_ms=… batch1_median_ms=… batch16_per_note_median_ms=… notes500_ms=… notes5100_extrapolated_ms=… pss_before_kb=… pss_after_open_kb=… pss_after_embed_kb=…`

- Phase timings come from `open`'s internal timing callback, so "open without digests" is `session_ms + warmup_ms` from the same operation.
- Corpus: the Milestone 3 synthetic note generator (same seed, syllables, lengths as `SearchMeasurementTest`), copied with a comment pointing to the original (kvid-core's test sources are not visible here).
- PSS: Android `Debug.getPss()`; the JVM prints `pss=not-measured`.
- Sizes: `scripts/` gets no new script for this; Task 11 records, by command, per-ABI byte totals of `jni/*/*.so` in the resolved AAR, the JVM jar size and the model size.

**Done:** lines captured from the JVM (local and CI), the local API 35 emulator, CI's emulator and the phone.

## Task 9: CI

- Model cache: `actions/cache/restore` on `~/.cache/kvid/models` keyed `bge-small-en-v1.5-<revision>-<model digest>` (from `model-pins.json` via `hashFiles`), then `download_model.py`, then `actions/cache/save` when the key missed, so a later failing step does not lose the download.
- `jvm-and-android`: install requirements; download; tokenizer fixture from the downloaded files; embedding fixture; `KVID_*_FIXTURE_DIR` and `KVID_REQUIRE_*=true`; `KVID_EMBEDDING_MEASURE=true`; after the build, check the JUnit XML for the parity classes and copy the `[kvid-embedding*]` lines.
- `android-store`: unchanged except the tokenizer fixture comes from the downloader and the embed module's tokenizer device run moves to `android-embed`; it keeps the core device tests and the sample smoke run.
- New `android-embed` job: same emulator settings plus `disk-size: 6G`, `timeout-minutes: 40` (lowered after measuring); push both fixtures; `logcat -G 16M -c`; `:kvid-embed-onnx:connectedAndroidDeviceTest` with all four instrumentation arguments; the separate measurement run; `scripts/verify_device_tests.py --require` for `BertTokenizerParityTest`, `OnnxEmbedderParityTest`, `OnnxEmbedderTest`, `TelemetryProviderTest`; copy logcat lines; upload reports.
- `ios`: tokenizer files via `download_model.py --files vocab.txt tokenizer.json`; otherwise unchanged (the new common tests run there).
- `TOKENIZER_FIXTURE_ARGS` is removed in favour of `model-pins.json`.

**Done:** CI green; parity lines and required classes present in the JVM and `android-embed` jobs.

## Task 10: README on the code branch

A short `kvid-embed-onnx` section: purpose, files the app supplies, Android minSdk 24, telemetry provider removed (with the app-level `tools:node="remove"` snippet for apps that also declare `onnxruntime-android` directly), permissions left to the app, not yet published, iOS later. No `docs/` on the code branch.

## Task 11: verify, push, record

1. `check_reference_model.py` passes; record its minimum cosine.
2. `scripts/check-local.sh --with-sample` exits 0, on the emulator and again with `ANDROID_SERIAL` set to the phone.
3. Independent code review of the branch diff against this plan (cross-family: Codex reviews Claude's code); address findings; rerun the check.
4. Push `phase-5-onnx-embedder`; watch CI; fix and repeat, with the local check before each push.
5. On `planning`: ROADMAP Milestone 5 gets the CI run id, parity numbers per platform, measurements and sizes, the reference-model check, both owner decisions, and what each `check-local.sh` run ran and skipped; ADR 0002 implementation notes get the embedder paragraph; this plan's status line is updated.

## Commit order on the code branch

1. Pins, requirements, download script, `check-local.sh`, `verify_device_tests.py`; CI switched to the pin file and verifier.
2. Build wiring, common pipeline and fake-backend tests.
3. ONNX Runtime embedder and manifest.
4. Embedding fixture script, parity, lifecycle and telemetry tests.
5. Measurements.
6. CI embedding jobs and README.

## Delegation

- Python and shell (Tasks 1 and 6) run in parallel with Kotlin (Tasks 2 to 4); files are disjoint, and only the Kotlin work runs Gradle. Gradle runs are serialized in the worktree.
- Tasks 5, 7 and 8 follow Task 3's API.
- Device and CI runs, decisions, and the final review stay with the coordinating session.

## Risks

- `/data/local/tmp` readability on the API 37 phone (Task 0 spike; fallback in Task 7).
- ORT float differences on x86_64 emulator kernels; the 0.9999 threshold is from ADR 0002 and is not relaxed. A failure is investigated (pooling, padding, optimization level), not tolerated.
- CI time for `android-embed`; the limit starts generous and is tightened after measurement.

## Review record

Both reviews read the original plan, the ADR and the code at `df02d68`. Folded in:

- Gradle build cache would skip parity runs and hide console lines (both; Opus rated blocker): tests forced to execute when fixtures are required; JUnit XML is the gate, logcat and stdout lines are copied, not trusted.
- `set -e` disabled under `||` (Opus): one process per part; exit codes 0/2/1; bootstrap criterion separated from the final one (both).
- Device-result verification counted JVM results (both; Codex measured 163 core and 34 embed results under `build/**` with no device run): connected results only, fresh, required classes.
- `adb push` nesting and logcat buffer loss (Opus).
- Read lock held across suspension (Opus); `SessionOptions` ownership (Codex; follows ORT's documented requirement rather than closing options after creation); cancellation-safe `open` (Codex).
- Actual output shape validated on every run (Codex); output selection fixed by an internal constructor so it cannot vary under one spec (Codex).
- `./gradlew build` on macOS runs iOS tests (Opus): QR opt-out exported, iOS tasks excluded from the jvm part.
- Measurement of open without digests (both); PSS isolation (Opus); `runTest` timeout and coroutine-test dependency (both).
- Separate `android-embed` job, disk size, split cache restore/save, download retries (Opus); single pin file and downloader provenance (both).
- Python reference provider and optimization level explicit, finite check (Opus); reference-model check with a failure threshold (Codex); unit-norm and case-count checks (Codex); dimension and output-name failure tests (Opus); `close` during `embed` test (Codex); `/data/local/tmp` fallback through a shell `cat` (Opus).
- Not changed: `srcDir` sharing, manifest location, Java tensor APIs, `repr` round-trip and the `-I` import recipe were confirmed by both.
