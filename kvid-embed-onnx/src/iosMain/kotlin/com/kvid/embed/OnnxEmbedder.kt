package com.kvid.embed

import cnames.structs.OrtMemoryInfo
import cnames.structs.OrtSession
import cnames.structs.OrtSessionOptions
import cnames.structs.OrtTensorTypeAndShapeInfo
import cnames.structs.OrtTypeInfo
import com.kvid.embed.onnxruntime.ONNXTensorElementDataType
import com.kvid.embed.onnxruntime.ORT_ENABLE_ALL
import com.kvid.embed.onnxruntime.OrtAllocator
import com.kvid.embed.onnxruntime.OrtApi
import com.kvid.embed.onnxruntime.OrtDeviceAllocator
import com.kvid.embed.onnxruntime.OrtMemTypeDefault
import com.kvid.store.EmbeddingSpec
import com.kvid.store.Embedder
import com.kvid.store.VectorMetric
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.posix.size_tVar
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * The iOS [OnnxEmbedder]: ONNX Runtime 1.30's C API through cinterop, linked from the pinned archive
 * (scripts/runtime-pins.json) into every binary built from this module. The open, its checks and its
 * error messages follow the JVM/Android actual; [OrtIosBackend] runs the session.
 */
actual class OnnxEmbedder private constructor(
    private val backend: OrtIosBackend,
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
            requireReadableFile(modelPath, "model")
            requireReadableFile(vocabPath, "vocabulary")

            val digestStart = TimeSource.Monotonic.markNow()
            val modelDigest = sha256Hex(modelPath)
            val vocabDigest = sha256Hex(vocabPath)
            val tokenizer = try {
                BertTokenizer.fromVocabulary(readTextFile(vocabPath))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("vocabulary file '$vocabPath' is not a BERT WordPiece vocabulary: ${e.message}", e)
            }
            val digestMs = digestStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
            currentCoroutineContext().ensureActive()

            val sessionStart = TimeSource.Monotonic.markNow()
            val runtime = try {
                OrtIosRuntime.initialize()
            } catch (e: IllegalStateException) {
                throw IllegalStateException("ONNX Runtime could not be initialized: ${e.message}", e)
            }
            val backend = createBackend(runtime, modelPath, model, options)
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
                val sessionMs = sessionStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
                currentCoroutineContext().ensureActive()

                val warmupStart = TimeSource.Monotonic.markNow()
                val warmup = try {
                    embedder.embed(listOf(""))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalStateException("model file '$modelPath' failed its warm-up inference: ${e.message}", e)
                }
                check(warmup.single().size == model.dimensions) {
                    "model file '$modelPath' returned ${warmup.single().size} dimensions, expected ${model.dimensions}"
                }
                val warmupMs = warmupStart.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
                timings?.invoke(OpenTimings(digestMs, sessionMs, warmupMs))
                return OnnxEmbedder(backend, embedder)
            } catch (e: Throwable) {
                backend.close()
                throw e
            }
        }

        private fun requireReadableFile(path: String, what: String) {
            require(isReadableFile(path)) { "$what file '$path' does not exist or is not a readable file" }
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

/**
 * A session for [path] whose inputs and output match [model], configured from [options]; CPU only (no
 * execution provider is registered). Every object is registered in a ledger right after the call that
 * returned it; on any failure the ledger releases exactly what exists, in reverse order, and on success
 * the backend takes over the objects it keeps.
 */
@OptIn(ExperimentalForeignApi::class)
private fun createBackend(runtime: OrtRuntime, path: String, model: BertEmbeddingModel, options: OnnxOptions): OrtIosBackend {
    val api = runtime.api
    val owned = NativeLedger()
    try {
        return memScoped {
            val optionsOut = alloc<CPointerVar<OrtSessionOptions>>()
            optionsOut.value = null
            val optionsError = api.errorOf(api.CreateSessionOptions!!(optionsOut.ptr))
            optionsOut.value?.let { owned.add(NativeKind.SESSION_OPTIONS, it) { o -> api.ReleaseSessionOptions!!(o) } }
            if (optionsError != null) throw IllegalStateException("ONNX Runtime could not create session options: $optionsError")
            val sessionOptions = optionsOut.value!!
            api.check(api.SetSessionGraphOptimizationLevel!!(sessionOptions, ORT_ENABLE_ALL), "SetSessionGraphOptimizationLevel")
            options.intraOpThreads?.let { api.check(api.SetIntraOpNumThreads!!(sessionOptions, it), "SetIntraOpNumThreads") }
            // Both are on by default in ONNX Runtime, as with the Java API's setters.
            if (!options.cpuMemoryArena) api.check(api.DisableCpuMemArena!!(sessionOptions), "DisableCpuMemArena")
            if (!options.memoryPattern) api.check(api.DisableMemPattern!!(sessionOptions), "DisableMemPattern")

            val sessionOut = alloc<CPointerVar<OrtSession>>()
            sessionOut.value = null
            val sessionError = api.errorOf(api.CreateSession!!(runtime.env, path.cstr.ptr, sessionOptions, sessionOut.ptr))
            sessionOut.value?.let { owned.add(NativeKind.SESSION, it) { s -> api.ReleaseSession!!(s) } }
            if (sessionError != null) throw IllegalArgumentException("model file '$path' could not be loaded by ONNX Runtime: $sessionError")
            val session = sessionOut.value!!
            OrtIosFaults.reach(OrtStep.SESSION)

            val allocatorOut = alloc<CPointerVar<OrtAllocator>>()
            allocatorOut.value = null
            api.check(api.GetAllocatorWithDefaultOptions!!(allocatorOut.ptr), "GetAllocatorWithDefaultOptions")
            val allocator = allocatorOut.value!! // borrowed, never released

            val inputs = names(api, session, allocator, input = true)
            val known = setOf(OrtIosBackend.INPUT_IDS, OrtIosBackend.ATTENTION_MASK, OrtIosBackend.TOKEN_TYPE_IDS)
            require(OrtIosBackend.INPUT_IDS in inputs && OrtIosBackend.ATTENTION_MASK in inputs && known.containsAll(inputs)) {
                "model file '$path' has inputs ${inputs.sorted()}; expected input_ids and attention_mask, and optionally token_type_ids"
            }
            val outputs = names(api, session, allocator, input = false)
            val outputIndex = outputs.indexOf(model.outputName)
            require(outputIndex >= 0) { "model file '$path' has no output '${model.outputName}'; its outputs are ${outputs.sorted()}" }
            checkOutput(api, session, outputIndex, path, model)

            val memoryOut = alloc<CPointerVar<OrtMemoryInfo>>()
            memoryOut.value = null
            val memoryError = api.errorOf(api.CreateCpuMemoryInfo!!(OrtDeviceAllocator, OrtMemTypeDefault, memoryOut.ptr))
            memoryOut.value?.let { owned.add(NativeKind.MEMORY_INFO, it) { m -> api.ReleaseMemoryInfo!!(m) } }
            if (memoryError != null) throw IllegalStateException("ONNX Runtime could not create CPU memory info: $memoryError")
            val memoryInfo = memoryOut.value!!
            OrtIosFaults.reach(OrtStep.MEMORY_INFO)

            val runInputs = listOfNotNull(
                OrtIosBackend.INPUT_IDS, OrtIosBackend.ATTENTION_MASK, OrtIosBackend.TOKEN_TYPE_IDS.takeIf { it in inputs }
            )
            val inputNames = heapStrings(owned, runInputs)
            val outputNames = heapStrings(owned, listOf(model.outputName))
            OrtIosBackend(runtime, session, memoryInfo, inputNames, runInputs.size, outputNames, model.outputName, model.dimensions, owned.transfer())
        }
    } catch (e: Throwable) {
        owned.releaseAllAfter(e)
        throw e
    }
}

/** The session's input or output names, copied to Kotlin; each allocator-owned string is freed with that allocator. */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.names(api: OrtApi, session: CPointer<OrtSession>, allocator: CPointer<OrtAllocator>, input: Boolean): List<String> {
    val ledger = NativeLedger()
    try {
        val count = alloc<size_tVar>()
        if (input) api.check(api.SessionGetInputCount!!(session, count.ptr), "SessionGetInputCount")
        else api.check(api.SessionGetOutputCount!!(session, count.ptr), "SessionGetOutputCount")
        return List(count.value.toInt()) { i ->
            val out = alloc<CPointerVar<ByteVar>>()
            out.value = null
            val status = if (input) api.SessionGetInputName!!(session, i.toULong(), allocator, out.ptr)
            else api.SessionGetOutputName!!(session, i.toULong(), allocator, out.ptr)
            val error = api.errorOf(status)
            out.value?.let { ledger.add(NativeKind.ALLOCATOR_STRING, it) { p -> api.check(api.AllocatorFree!!(allocator, p), "AllocatorFree") } }
            if (error != null) throw IllegalStateException("ONNX Runtime could not read ${if (input) "input" else "output"} name $i: $error")
            if (input && i == 0) OrtIosFaults.reach(OrtStep.INPUT_INSPECTION)
            out.value!!.toKString()
        }
    } catch (e: Throwable) {
        ledger.releaseAllAfter(e)
        throw e
    } finally {
        ledger.releaseAll()
    }
}

/** Requires output [index] to be a float tensor `[batch, sequence, dimensions]`; a dynamic dimension is -1. */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.checkOutput(api: OrtApi, session: CPointer<OrtSession>, index: Int, path: String, model: BertEmbeddingModel) {
    val ledger = NativeLedger()
    try {
        val typeOut = alloc<CPointerVar<OrtTypeInfo>>()
        typeOut.value = null
        val error = api.errorOf(api.SessionGetOutputTypeInfo!!(session, index.toULong(), typeOut.ptr))
        typeOut.value?.let { ledger.add(NativeKind.TYPE_INFO, it) { t -> api.ReleaseTypeInfo!!(t) } }
        if (error != null) throw IllegalStateException("ONNX Runtime could not describe output '${model.outputName}': $error")
        OrtIosFaults.reach(OrtStep.OUTPUT_INSPECTION)
        // A view into the type info, borrowed: valid while the type info lives, never released.
        val tensorOut = alloc<CPointerVar<OrtTensorTypeAndShapeInfo>>()
        tensorOut.value = null
        api.check(api.CastTypeInfoToTensorInfo!!(typeOut.value, tensorOut.ptr), "CastTypeInfoToTensorInfo")
        val info = tensorOut.value?.let { typeAndShape(api, it) }
        require(info != null && info.first == ONNXTensorElementDataType.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT && info.second.size == 3) {
            val description = if (info == null) "not a tensor" else "a tensor of ${info.first} with shape ${info.second.contentToString()}"
            "model file '$path' output '${model.outputName}' is $description; expected a float tensor [batch, sequence, ${model.dimensions}]"
        }
        val hidden = info.second.last()
        require(hidden < 0 || hidden == model.dimensions.toLong()) {
            "model file '$path' output '${model.outputName}' has $hidden dimensions; ${model.name} expects ${model.dimensions}"
        }
    } catch (e: Throwable) {
        ledger.releaseAllAfter(e)
        throw e
    } finally {
        ledger.releaseAll()
    }
}

/** [values] as NUL-terminated strings plus a pointer array, in `nativeHeap` memory owned by [ledger]. */
@OptIn(ExperimentalForeignApi::class)
private fun heapStrings(ledger: NativeLedger, values: List<String>): CPointer<CPointerVar<ByteVar>> {
    val array = ledger.add(NativeKind.HEAP, nativeHeap.allocArray<CPointerVar<ByteVar>>(values.size)) { nativeHeap.free(it.rawValue) }
    values.forEachIndexed { i, value ->
        val bytes = value.encodeToByteArray()
        val string = ledger.add(NativeKind.HEAP, nativeHeap.allocArray<ByteVar>(bytes.size + 1)) { nativeHeap.free(it.rawValue) }
        bytes.forEachIndexed { j, b -> string[j] = b }
        string[bytes.size] = 0
        array[i] = string
    }
    return array
}
