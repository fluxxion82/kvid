package com.kvid.embed

import com.kvid.store.EmbeddingSpec
import com.kvid.store.Embedder

/**
 * An [Embedder] that runs a BERT-style ONNX model with ONNX Runtime 1.30 (ADR 0002): through the Java
 * API on the JVM and Android, and through the C API on iOS (`iosArm64`, `iosSimulatorArm64`).
 *
 * - **Files.** The app supplies `model.onnx` and `vocab.txt` (for [BertEmbeddingModel.BGE_SMALL_EN_V1_5],
 *   the files of `BAAI/bge-small-en-v1.5`); kvid never downloads a model. [open] digests both, so
 *   [spec] names exactly the files in use: `modelDigest` is `sha256:<model.onnx>` and `tokenizer` is
 *   `bert-wordpiece-uncased sha256:<vocab.txt>`. The same files give the same spec on every platform.
 * - **Threading.** [embed] runs on `Dispatchers.Default` and may be called concurrently. [close] waits
 *   for running inferences; [embed] after [close] throws [IllegalStateException]. A failure returns no
 *   vectors at all, so a store writes nothing for that batch. The process-wide ONNX Runtime environment
 *   is never released.
 * - **Execution.** CPU only: no Core ML, NNAPI or other execution provider is registered.
 * - **Android.** The module needs minSdk 24 (onnxruntime-android's minimum). Its manifest removes
 *   onnxruntime-android's `TelemetryInitializer` provider, so nothing runs at app start, and [open]
 *   turns ONNX Runtime telemetry off. The AAR's INTERNET and ACCESS_NETWORK_STATE permissions are left
 *   to the app.
 * - **iOS telemetry (a process-wide side effect).** Before kvid's first call into ONNX Runtime, once per
 *   process, the first [open] sets the environment variable `ORT_DISABLE_TELEMETRY=1` when it is unset;
 *   ONNX Runtime latches that full opt-out when it initializes. A value the app set itself is kept: a
 *   value ONNX Runtime treats as true (`1`, `true`, `yes`, `on`, `y`, ignoring case and surrounding
 *   spaces) disables telemetry as well; any other value is the app's explicit choice, and suppression
 *   is then the app's responsibility, because the C API call kvid still makes, `DisableTelemetryEvents`,
 *   does not stop ONNX Runtime's uploader. kvid never changes the variable afterwards. Another ONNX
 *   Runtime user in the same process that initializes the runtime first is not covered: a host that
 *   needs that guarantee sets `ORT_DISABLE_TELEMETRY=1` at launch (in the scheme's environment, or
 *   before any framework loads).
 */
expect class OnnxEmbedder : Embedder, AutoCloseable {
    override val spec: EmbeddingSpec

    override suspend fun embed(texts: List<String>): List<FloatArray>

    /** Closes the session; idempotent. Waits for inferences already running. */
    override fun close()

    companion object {
        /**
         * Opens [modelPath] (`model.onnx`) with the vocabulary at [vocabPath] (`vocab.txt`) for [model].
         * Blocking work runs on `Dispatchers.IO`. Throws [IllegalArgumentException] naming the file when a
         * file is missing or unreadable, or the graph does not match [model] (inputs, output name, type
         * or dimensions); [IllegalStateException] when the warm-up inference fails. A cancelled open
         * leaves nothing open.
         */
        suspend fun open(
            modelPath: String,
            vocabPath: String,
            model: BertEmbeddingModel = BertEmbeddingModel.BGE_SMALL_EN_V1_5,
            options: OnnxOptions = OnnxOptions()
        ): OnnxEmbedder
    }
}

/** Durations of the phases of one [OnnxEmbedder.open], in milliseconds, for measurements. */
internal class OpenTimings(val digestMs: Double, val sessionMs: Double, val warmupMs: Double)

/**
 * [OnnxEmbedder.open] with a callback that receives the phase durations. It is called at the end of the
 * blocking work, before `open` returns, so tests can also cancel the caller during the return handoff.
 */
internal expect suspend fun openOnnxEmbedder(
    modelPath: String,
    vocabPath: String,
    model: BertEmbeddingModel,
    options: OnnxOptions,
    timings: ((OpenTimings) -> Unit)?
): OnnxEmbedder

/** SHA-256 of the file at [path] as lowercase hex, streamed in 1 MiB blocks. */
internal expect fun sha256Hex(path: String): String
