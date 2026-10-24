# Plan: ONNX Runtime embedder for JVM and Android

_Written October 24 for implementation by a local agent, followed by review in the cloud session. Implements step 2 of [ADR 0002](../adr/0002-embedding-runtime.md). iOS (cinterop) is the next step and is out of scope here._

## Why a local agent

The cloud session cannot reach Google Maven (`dl.google.com`, needed by AGP and androidx) or Hugging Face, and it has no macOS. It cannot configure this Gradle build at all, so it can only run pure-Kotlin code in throwaway JVM projects, with CI as its only test of Android and iOS. A local machine can run the whole matrix before each push. That matters most here, because the work depends on a 130 MB model download and on Android device runs.

## Where to work

- Branch `phase-5-onnx-embedder`, created from `phase-5-semantic-search` at `df02d68` or later. That branch holds the store-side vectors and the tokenizer, and is green in CI run 40016745410.
- If `phase-5-semantic-search` is merged to `main` first, merge `main` into the new branch.
- Push only after the local checks below pass. Report the CI run id and the parity numbers on this branch in `docs/ROADMAP.md` (Milestone 5).

## Step 0: local pre-push check

Add `scripts/check-local.sh` to the code branch, mirroring `.github/workflows/ci.yml`, and run it before every push:

1. `./gradlew build`: JVM and Android host tests.
2. Tokenizer fixture and embedding fixture generation in a Python virtual environment (pinned `tokenizers==0.23.3`, `onnxruntime==1.30.0`, `numpy`), with the same pinned model revision and digests as CI. Run the JVM tests with `KVID_REQUIRE_*=true`.
3. On macOS: the same `iosSimulatorArm64Test` tasks CI runs (`:kvid-core`, `:kvid-embed-onnx`, `:kvid-sample:shared`), plus the optimized measurement.
4. If `adb devices` lists a device or emulator: the `connectedAndroidDeviceTest` runs from the Android CI job, including the fixture pushes and instrumentation arguments.
5. Optionally, the sample builds and smoke scripts (`kvid-sample/*/smoke-test.sh`).

The script should say which parts it skipped, so a partial run is never reported as a full one.

## Design

### Module layout (`kvid-embed-onnx`)

- `commonMain`: everything except the runtime call, so it is tested on every platform with a fake backend.
  - `BertEmbeddingModel`: name, dimensions, pooling (`CLS` | `MEAN`), normalize, `maxInputTokens`, output name. Also a `BGE_SMALL_EN_V1_5` constant: 384 dimensions, CLS, normalized, 512 tokens, output `last_hidden_state`.
  - `internal interface EncoderBackend : AutoCloseable` with `run(inputIds: LongArray, attentionMask: LongArray, tokenTypeIds: LongArray, batch: Int, seqLen: Int): FloatArray`. It returns the hidden states (batch × seqLen × hidden, row-major).
  - `internal class BertEmbedder(model, tokenizer, backend, spec) : Embedder`:
    - tokenizes with `BertTokenizer.encode(text, model.maxInputTokens)`;
    - splits the texts into sub-batches (default 16) and pads each to its longest sequence, with `attention_mask` 0 for padding and `token_type_ids` 0;
    - runs the backend, pools, L2-normalizes in float32, and returns the vectors in input order.
  - Mean pooling must respect the attention mask. CLS takes position 0.
- `jvmMain` + `androidMain`: `OnnxEmbedder`, using the `ai.onnxruntime` Java API.
  - The JVM depends on `com.microsoft.onnxruntime:onnxruntime:1.30.0`, Android on `com.microsoft.onnxruntime:onnxruntime-android:1.30.0`.
  - Both expose the same Java API, so share one source directory, e.g. `src/jvmAndAndroidMain/kotlin`, added with `kotlin.srcDir(...)` to both `jvmMain` and `androidMain`. Do the same for tests with `jvmTest` + `androidDeviceTest`.
  - Fall back to two thin copies if the IDE or the compiler objects. Do not add an iOS stub that throws; iOS gets its own actual in the next step.
- Check the minimum Android SDK of `onnxruntime-android` 1.30.0 against kvid's 23. If it is higher, stop and report; do not raise the minimum silently.

### Public API (JVM and Android)

```kotlin
class OnnxEmbedder : Embedder, AutoCloseable {
    override val spec: EmbeddingSpec
    override suspend fun embed(texts: List<String>): List<FloatArray>   // runs on Dispatchers.Default
    override fun close()                                                  // closes the session, not the shared OrtEnvironment

    companion object {
        suspend fun open(
            modelPath: String,                 // model.onnx
            vocabPath: String,                 // vocab.txt
            model: BertEmbeddingModel = BertEmbeddingModel.BGE_SMALL_EN_V1_5,
            options: OnnxOptions = OnnxOptions()   // intraOpThreads: Int? = null, maxBatchSize: Int = 16
        ): OnnxEmbedder
    }
}
```

- `open` computes the SHA-256 of both files by streaming `MessageDigest`. It then:
  - creates the session from the path with full graph optimization;
  - checks that the input names include `input_ids` and `attention_mask` (and supplies `token_type_ids` when the model declares it);
  - runs one warm-up inference on the empty text and checks that the output's last dimension equals `model.dimensions`.
  - Every failure gets a message that names the file and what was expected.
- `spec` must be the same on every platform for the same files:
  - `model` is the model name;
  - `modelDigest` is `sha256:<hex of model.onnx>`;
  - `tokenizer` is `bert-wordpiece-uncased sha256:<hex of vocab.txt>`;
  - `pooling` is `cls`, `normalized` is `true`, `metric` is `COSINE`, and `maxInputTokens` is 512.
  - Changing the tokenizer tables in a way that changes ids is a breaking change caught by the tokenizer fixture. The spec does not include the table version, so library upgrades do not force re-embedding.
- `embed` of an empty list returns an empty list. A failure inside ONNX Runtime surfaces as an exception and nothing is returned partially; the store then writes nothing for that batch.

### Model files

- Expected at the pinned revision `5c38ec7c405ec4b44b94cc5a9bb96e735b38267a` of `BAAI/bge-small-en-v1.5`: `onnx/model.onnx` (fp32), `vocab.txt` and `tokenizer.json`. Verify that the ONNX file exists there.
  - If it does not, export with `optimum`, record the exact command and versions, and stop for review before continuing.
- Add `scripts/download_model.py OUT_DIR --model M --revision R --sha256 ...` (or extend the fixture script). It downloads the three files, checks their digests, and fails on mismatch. Record the three digests next to `TOKENIZER_FIXTURE_ARGS` in CI.
- Model files are never committed.

## Reference fixture and parity

- `scripts/make_embedding_fixture.py OUT_DIR --model-dir DIR`:
  - Texts: about 100 curated texts (short queries, notes, Unicode, CJK, emoji, empty text, two inputs longer than 512 tokens) and about 100 seeded random texts.
  - Encodes them with `tokenizers` (truncation 512, no padding) and runs Python ONNX Runtime one text at a time, so padding cannot influence the reference.
  - Pools CLS and normalizes in numpy float32.
  - Writes `embeddings.jsonl`, with `{text, ids, vector}` per case and floats written with `repr` so they round-trip exactly, and `source.json` (revision, digests, onnxruntime, numpy and tokenizers versions, case count).
- Do a one-off check locally, not in CI: compare the Python ONNX Runtime vectors with `sentence-transformers` for the same revision, to confirm that the ONNX graph is the published model. Record the minimum cosine in the ROADMAP.
- `OnnxEmbedderParityTest` (shared JVM and Android test directory) reads `KVID_EMBEDDING_FIXTURE_DIR`, which holds the fixture plus `model.onnx` and `vocab.txt`, and `KVID_REQUIRE_EMBEDDING_FIXTURE`, using the same skip-or-fail rule as the tokenizer test.
  - It checks that token ids equal the fixture's.
  - It embeds every case once in batches of 8 (exercising padding) and once one at a time.
  - It requires a cosine of at least 0.9999 with the reference for every case (ADR 0002), and at least 0.99999 between batched and unbatched results.
  - It prints `[kvid-embedding] cases=… min_cosine=… max_abs_diff=… batched_min_cosine=… platform=…`.
- Unit tests in `commonTest` with a fake backend: CLS and masked mean pooling, normalization (a zero vector stays zero), padding layout, ordering across sub-batches, truncation, the empty list, and a dimension mismatch.

## CI

- Cache the model directory with `actions/cache`, keyed by the revision. Always verify the digests after restoring.
- **JVM job:** download or restore the model, build the embedding fixture, and run the parity test with `KVID_REQUIRE_EMBEDDING_FIXTURE=true`.
- **Android emulator job:**
  - push the model and the fixture to `/data/local/tmp/kvid-embedding-fixture`;
  - run `:kvid-embed-onnx:connectedAndroidDeviceTest` with both instrumentation arguments (tokenizer and embedding);
  - require both `[kvid-tokenizer]` and `[kvid-embedding]` lines in logcat.
  - The per-module test count guard already covers `kvid-embed-onnx`.
- **iOS job:** unchanged in this step. The common tests run there with the fake backend.

## Measurements to record (ROADMAP, Milestone 5)

On the JVM (CI Ubuntu and the local machine) and the Android API 35 emulator (and a physical device if one is available), record:

- open time, with and without digest computation;
- median embedding time per note, with a batch of 1 and a batch of 16;
- the time to embed 500 notes from the Milestone 3 corpus, extrapolated to 5,100;
- the native library size per Android ABI from the `onnxruntime-android` AAR, and the model size;
- memory, if measurable without guesswork: Android `Debug.getPss()` before and after opening and embedding; otherwise state that it was not measured.

Print a `[kvid-embedding-measure]` line, as the store measurements do. Keep wall-clock limits out of assertions.

## Done when

- The local check and CI are green, with the parity lines present on the JVM and the Android emulator and numbers recorded in the ROADMAP.
- `kvid-core` is unchanged except where a review agrees otherwise. It must not depend on ONNX Runtime.
- No iOS stubs, and no skipped or relaxed tests.

## Not in this step (tracked in the ROADMAP)

- iOS through cinterop.
- Query instructions for BGE ("Represent this sentence for searching relevant passages: ") behind an `embedQuery` hook on `Embedder`; decide this with the evaluation corpus.
- int8 models.
- Token-aware chunking.
- Semantic search in the sample.
- Publishing `kvid-embed-onnx`.

## Review

When the branch is pushed, the cloud session reviews the diff and the CI logs against this plan. It pushes small fixes it can verify, and writes larger findings back here or to the ROADMAP for the implementing agent.
