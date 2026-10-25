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
 * The JVM and Android [OnnxEmbedder]: ONNX Runtime's Java API (`ai.onnxruntime`), which the JVM jar and
 * the Android AAR both expose, so this one source directory compiles into both targets.
 */
actual class OnnxEmbedder private constructor(
    private val backend: OrtEncoderBackend,
    private val embedder: BertEmbedder
) : Embedder, AutoCloseable {
    actual override val spec: EmbeddingSpec get() = embedder.spec

    actual override suspend fun embed(texts: List<String>): List<FloatArray> =
        if (texts.isEmpty()) emptyList() else withContext(Dispatchers.Default) { embedder.embed(texts) }

    actual override fun close() = backend.close()

    actual companion object {
        actual suspend fun open(modelPath: String, vocabPath: String, model: BertEmbeddingModel, options: OnnxOptions): OnnxEmbedder =
            openOnnxEmbedder(modelPath, vocabPath, model, options, timings = null)

        internal suspend fun openTimed(
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
                val embedder = BertEmbedder(model, tokenizer, backend, spec, options.maxBatchSize, options.sortByLength)
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
                sessionOptions.setCPUArenaAllocator(options.cpuMemoryArena)
                sessionOptions.setMemoryPatternOptimization(options.memoryPattern)
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

internal actual suspend fun openOnnxEmbedder(
    modelPath: String,
    vocabPath: String,
    model: BertEmbeddingModel,
    options: OnnxOptions,
    timings: ((OpenTimings) -> Unit)?
): OnnxEmbedder = OnnxEmbedder.openTimed(modelPath, vocabPath, model, options, timings)
