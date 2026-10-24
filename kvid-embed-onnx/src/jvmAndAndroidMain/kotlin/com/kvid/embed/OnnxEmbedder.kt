package com.kvid.embed

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.kvid.store.EmbeddingSpec
import com.kvid.store.Embedder
import com.kvid.store.VectorMetric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * An [Embedder] that runs a BERT-style ONNX model with ONNX Runtime on the JVM and Android (ADR 0002).
 *
 * - **Files.** The app supplies `model.onnx` and `vocab.txt` (for [BertEmbeddingModel.BGE_SMALL_EN_V1_5],
 *   the files of `BAAI/bge-small-en-v1.5`); kvid never downloads a model. [open] digests both, so
 *   [spec] names exactly the files in use: `modelDigest` is `sha256:<model.onnx>` and `tokenizer` is
 *   `bert-wordpiece-uncased sha256:<vocab.txt>`. The same files give the same spec on every platform.
 * - **Threading.** [embed] runs on [Dispatchers.Default] and may be called concurrently. [close] waits
 *   for running inferences; [embed] after [close] throws [IllegalStateException]. A failure returns no
 *   vectors at all, so a store writes nothing for that batch. The shared `OrtEnvironment` is never
 *   closed.
 * - **Android.** The module needs minSdk 24 (onnxruntime-android's minimum). Its manifest removes
 *   onnxruntime-android's `TelemetryInitializer` provider, so nothing runs at app start, and [open]
 *   turns ONNX Runtime telemetry off. The AAR's INTERNET and ACCESS_NETWORK_STATE permissions are left
 *   to the app.
 */
class OnnxEmbedder private constructor(
    private val backend: OrtEncoderBackend,
    private val embedder: BertEmbedder
) : Embedder, AutoCloseable {
    override val spec: EmbeddingSpec get() = embedder.spec

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        if (texts.isEmpty()) emptyList() else withContext(Dispatchers.Default) { embedder.embed(texts) }

    /** Closes the session; idempotent. Waits for inferences already running. */
    override fun close() = backend.close()

    /** Durations of the phases of one [open], in milliseconds, for measurements. */
    internal class OpenTimings(val digestMs: Double, val sessionMs: Double, val warmupMs: Double)

    companion object {
        /**
         * Opens [modelPath] (`model.onnx`) with the vocabulary at [vocabPath] (`vocab.txt`) for [model].
         * Blocking work runs on [Dispatchers.IO]. Throws [IllegalArgumentException] naming the file when a
         * file is missing or unreadable, or the graph does not match [model] (inputs, output name, type
         * or dimensions); [IllegalStateException] when the warm-up inference fails.
         */
        suspend fun open(
            modelPath: String,
            vocabPath: String,
            model: BertEmbeddingModel = BertEmbeddingModel.BGE_SMALL_EN_V1_5,
            options: OnnxOptions = OnnxOptions()
        ): OnnxEmbedder = open(modelPath, vocabPath, model, options, timings = null)

        internal suspend fun open(
            modelPath: String,
            vocabPath: String,
            model: BertEmbeddingModel,
            options: OnnxOptions,
            timings: ((OpenTimings) -> Unit)?
        ): OnnxEmbedder {
            var opened: OnnxEmbedder? = null
            try {
                withContext(Dispatchers.IO) { opened = openBlocking(modelPath, vocabPath, model, options, timings) }
                return opened!!
            } catch (e: Throwable) {
                // withContext can throw after its block completed (cancelled while returning).
                opened?.close()
                throw e
            }
        }

        private suspend fun openBlocking(
            modelPath: String,
            vocabPath: String,
            model: BertEmbeddingModel,
            options: OnnxOptions,
            timings: ((OpenTimings) -> Unit)?
        ): OnnxEmbedder {
            val modelFile = readableFile(modelPath, "model")
            val vocabFile = readableFile(vocabPath, "vocabulary")

            val digestStart = System.nanoTime()
            val modelDigest = sha256Hex(modelFile)
            val vocabDigest = sha256Hex(vocabFile)
            val tokenizer = try {
                BertTokenizer.fromVocabulary(vocabFile.readText())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("vocabulary file '$vocabPath' is not a BERT WordPiece vocabulary: ${e.message}", e)
            }
            val digestNs = System.nanoTime() - digestStart
            currentCoroutineContext().ensureActive()

            val sessionStart = System.nanoTime()
            val environment = OrtEnvironment.getEnvironment()
            try {
                environment.setTelemetry(false)
            } catch (_: OrtException) {
                // Builds without telemetry support report an error here; there is nothing to turn off.
            }
            val backend = createBackend(environment, modelFile, model, options)
            try {
                val spec = EmbeddingSpec(
                    model = model.name,
                    modelDigest = "sha256:$modelDigest",
                    tokenizer = "bert-wordpiece-uncased sha256:$vocabDigest",
                    dimensions = model.dimensions,
                    pooling = model.pooling.id,
                    normalized = model.normalize,
                    metric = VectorMetric.COSINE,
                    maxInputTokens = model.maxInputTokens
                )
                val embedder = BertEmbedder(model, tokenizer, backend, spec, options.maxBatchSize)
                val sessionNs = System.nanoTime() - sessionStart
                currentCoroutineContext().ensureActive()

                val warmupStart = System.nanoTime()
                val warmup = try {
                    embedder.embed(listOf(""))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalStateException("model file '${modelFile.path}' failed its warm-up inference: ${e.message}", e)
                }
                check(warmup.single().size == model.dimensions) {
                    "model file '${modelFile.path}' returned ${warmup.single().size} dimensions, expected ${model.dimensions}"
                }
                val warmupNs = System.nanoTime() - warmupStart
                timings?.invoke(OpenTimings(digestNs / 1e6, sessionNs / 1e6, warmupNs / 1e6))
                return OnnxEmbedder(backend, embedder)
            } catch (e: Throwable) {
                backend.close()
                throw e
            }
        }

        private fun readableFile(path: String, what: String): File {
            val file = File(path)
            require(file.isFile && file.canRead()) { "$what file '$path' does not exist or is not a readable file" }
            return file
        }

        /** A session for [modelFile] whose inputs and output match [model]; nothing stays open on failure. */
        private fun createBackend(environment: OrtEnvironment, modelFile: File, model: BertEmbeddingModel, options: OnnxOptions): OrtEncoderBackend {
            val path = modelFile.path
            val sessionOptions = OrtSession.SessionOptions()
            var session: OrtSession? = null
            try {
                sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.intraOpThreads?.let { sessionOptions.setIntraOpNumThreads(it) }
                session = try {
                    environment.createSession(path, sessionOptions)
                } catch (e: OrtException) {
                    throw IllegalArgumentException("model file '$path' could not be loaded by ONNX Runtime: ${e.message}", e)
                }

                val inputs = session.inputNames
                val known = setOf(OrtEncoderBackend.INPUT_IDS, OrtEncoderBackend.ATTENTION_MASK, OrtEncoderBackend.TOKEN_TYPE_IDS)
                require(OrtEncoderBackend.INPUT_IDS in inputs && OrtEncoderBackend.ATTENTION_MASK in inputs && known.containsAll(inputs)) {
                    "model file '$path' has inputs ${inputs.sorted()}; expected input_ids and attention_mask, and optionally token_type_ids"
                }
                val outputs = session.outputInfo
                val output = outputs[model.outputName]
                    ?: throw IllegalArgumentException("model file '$path' has no output '${model.outputName}'; its outputs are ${outputs.keys.sorted()}")
                val info = output.info
                require(info is TensorInfo && info.type == OnnxJavaType.FLOAT && info.shape.size == 3) {
                    "model file '$path' output '${model.outputName}' is $info; expected a float tensor [batch, sequence, ${model.dimensions}]"
                }
                // A dynamic dimension is reported as -1; the warm-up and every inference check the actual shape.
                val hidden = info.shape.last()
                require(hidden < 0 || hidden == model.dimensions.toLong()) {
                    "model file '$path' output '${model.outputName}' has $hidden dimensions; ${model.name} expects ${model.dimensions}"
                }
                return OrtEncoderBackend(environment, session, sessionOptions, model.outputName, model.dimensions)
            } catch (e: Throwable) {
                try {
                    session?.close()
                } finally {
                    sessionOptions.close()
                }
                throw e
            }
        }
    }
}
