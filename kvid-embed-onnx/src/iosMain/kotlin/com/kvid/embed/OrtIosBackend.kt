package com.kvid.embed

import cnames.structs.OrtMemoryInfo
import cnames.structs.OrtSession
import cnames.structs.OrtTensorTypeAndShapeInfo
import cnames.structs.OrtValue
import com.kvid.embed.onnxruntime.ONNXTensorElementDataType
import com.kvid.embed.onnxruntime.OrtApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSCondition
import platform.posix.memcpy
import platform.posix.size_tVar
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * [EncoderBackend] over an ONNX Runtime C API session (implementation plan, D4 as revised by R1 and R5).
 *
 * - **Ownership.** [resources] holds what the open created for this backend, in creation order: the
 *   session options (which must outlive the session), the session, the CPU memory info, and the
 *   `nativeHeap` name strings and pointer arrays that `Run` reads. [close] releases them once, in reverse.
 * - **Lifecycle.** An `NSCondition` guards a state, OPEN, CLOSING or CLOSED, and the count of active
 *   inferences. [run] registers itself while OPEN and then works without holding the lock; [close] moves
 *   to CLOSING (new inferences are refused), waits until none is active, releases, and sets CLOSED. A
 *   second or concurrent [close] waits for CLOSED. The condition is an Objective-C object freed with the
 *   backend, never destroyed by [close], so nothing ever touches a destroyed primitive. Lock and unlock
 *   always happen on one thread, with no suspension between them.
 * - **Inference.** The three input tensors wrap pinned Kotlin arrays (`CreateTensorWithDataAsOrtValue`,
 *   no copy); each input value is released inside its pin. The output is checked to be a FLOAT tensor of
 *   exactly `[batch, seqLen, dimensions]` ([checkHiddenStatesShape]) before it is copied, and released
 *   after the copy.
 */
@OptIn(ExperimentalForeignApi::class)
internal class OrtIosBackend(
    private val runtime: OrtRuntime,
    private val session: CPointer<OrtSession>,
    private val memoryInfo: CPointer<OrtMemoryInfo>,
    /** `input_ids`, `attention_mask` and, when the model has it, `token_type_ids`, as C strings. */
    private val inputNames: CPointer<CPointerVar<ByteVar>>,
    private val inputCount: Int,
    private val outputNames: CPointer<CPointerVar<ByteVar>>,
    private val outputName: String,
    private val dimensions: Int,
    private val resources: NativeLedger
) : EncoderBackend {
    private enum class State { OPEN, CLOSING, CLOSED }

    private val condition = NSCondition()
    private var state = State.OPEN
    private var active = 0

    init {
        BackendHooks.live.incrementAndFetch()
    }

    override fun run(inputIds: LongArray, attentionMask: LongArray, tokenTypeIds: LongArray, batch: Int, seqLen: Int): FloatArray {
        condition.lock()
        try {
            if (state != State.OPEN) throw IllegalStateException("OnnxEmbedder is closed")
            active++
        } finally {
            condition.unlock()
        }
        try {
            BackendHooks.insideRun.load()?.invoke()
            return infer(inputIds, attentionMask, tokenTypeIds, batch, seqLen)
        } finally {
            condition.lock()
            active--
            condition.broadcast()
            condition.unlock()
        }
    }

    /** Waits for running inferences, then releases the session and everything it uses; later calls do nothing. */
    override fun close() {
        condition.lock()
        try {
            when (state) {
                State.CLOSED -> return
                State.CLOSING -> {
                    while (state != State.CLOSED) condition.wait()
                    return
                }
                State.OPEN -> {
                    state = State.CLOSING
                    while (active > 0) condition.wait()
                }
            }
        } finally {
            condition.unlock()
        }
        try {
            resources.releaseAll()
        } finally {
            condition.lock()
            state = State.CLOSED
            BackendHooks.live.decrementAndFetch()
            condition.broadcast()
            condition.unlock()
        }
    }

    private fun infer(inputIds: LongArray, attentionMask: LongArray, tokenTypeIds: LongArray, batch: Int, seqLen: Int): FloatArray {
        val elements = batch.toLong() * seqLen
        require(batch > 0 && seqLen > 0 && inputIds.size.toLong() == elements && attentionMask.size.toLong() == elements &&
            tokenTypeIds.size.toLong() == elements) { "inputs do not have $batch × $seqLen elements" }
        val api = runtime.api
        return inputIds.usePinned { ids ->
            attentionMask.usePinned { mask ->
                tokenTypeIds.usePinned { types ->
                    memScoped {
                        val ledger = NativeLedger()
                        try {
                            val shape = allocArray<LongVar>(2)
                            shape[0] = batch.toLong()
                            shape[1] = seqLen.toLong()
                            val inputs = allocArray<CPointerVar<OrtValue>>(inputCount)
                            inputs[0] = tensor(ledger, ids, elements, shape)
                            inputs[1] = tensor(ledger, mask, elements, shape)
                            if (inputCount == 3) inputs[2] = tensor(ledger, types, elements, shape)
                            OrtIosFaults.reach(OrtStep.RUN_INPUTS)

                            val outputs = allocArray<CPointerVar<OrtValue>>(1)
                            outputs[0] = null
                            val error = api.errorOf(
                                api.Run!!(session, null, inputNames, inputs, inputCount.convert(), outputNames, 1u, outputs)
                            )
                            outputs[0]?.let { ledger.add(NativeKind.VALUE, it) { value -> api.ReleaseValue!!(value) } }
                            if (error != null) throw IllegalStateException("ONNX Runtime inference failed: $error")
                            val output = outputs[0] ?: throw IllegalStateException("ONNX Runtime returned no output '$outputName'")
                            copyHiddenStates(ledger, output, batch, seqLen)
                        } finally {
                            // Inside the pins: ONNX Runtime never sees unpinned input memory.
                            ledger.releaseAll()
                        }
                    }
                }
            }
        }
    }

    private fun MemScope.tensor(ledger: NativeLedger, pinned: Pinned<LongArray>, elements: Long, shape: CPointer<LongVar>): CPointer<OrtValue> {
        val api = runtime.api
        val bytes = checkedBytes(elements, Long.SIZE_BYTES)
        val out = alloc<CPointerVar<OrtValue>>()
        out.value = null
        val error = api.errorOf(
            api.CreateTensorWithDataAsOrtValue!!(
                memoryInfo, pinned.addressOf(0), bytes.convert(), shape, 2u,
                ONNXTensorElementDataType.ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, out.ptr
            )
        )
        out.value?.let { ledger.add(NativeKind.VALUE, it) { value -> api.ReleaseValue!!(value) } }
        if (error != null) throw IllegalStateException("ONNX Runtime could not create an input tensor: $error")
        return out.value ?: throw IllegalStateException("ONNX Runtime returned no input tensor")
    }

    /** Checks the output's type and exact shape, then copies it; [output] is released by the caller's ledger after the copy. */
    private fun MemScope.copyHiddenStates(ledger: NativeLedger, output: CPointer<OrtValue>, batch: Int, seqLen: Int): FloatArray {
        val api = runtime.api
        val isTensor = alloc<IntVar>()
        api.check(api.IsTensor!!(output, isTensor.ptr), "IsTensor")
        check(isTensor.value != 0) { "the model output '$outputName' is not a tensor, expected a float tensor" }

        val infoOut = alloc<CPointerVar<OrtTensorTypeAndShapeInfo>>()
        infoOut.value = null
        val infoError = api.errorOf(api.GetTensorTypeAndShape!!(output, infoOut.ptr))
        infoOut.value?.let { ledger.add(NativeKind.TENSOR_TYPE_AND_SHAPE_INFO, it) { info -> api.ReleaseTensorTypeAndShapeInfo!!(info) } }
        if (infoError != null) throw IllegalStateException("ONNX Runtime could not describe the output '$outputName': $infoError")
        val info = infoOut.value ?: throw IllegalStateException("ONNX Runtime returned no type and shape for '$outputName'")
        val (type, shape) = typeAndShape(api, info)
        OrtIosFaults.reach(OrtStep.RUN_OUTPUT)
        check(type == ONNXTensorElementDataType.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
            "the model output '$outputName' is a tensor of $type, expected a float tensor"
        }
        checkHiddenStatesShape(shape, batch, seqLen, dimensions)

        val count = batch.toLong() * seqLen * dimensions
        check(count <= Int.MAX_VALUE) { "the model output has $count values, more than an array holds" }
        val countOut = alloc<size_tVar>()
        api.check(api.GetTensorShapeElementCount!!(info, countOut.ptr), "GetTensorShapeElementCount")
        check(countOut.value.toLong() == count) { "the model output has ${countOut.value} values, expected $count" }
        val data = alloc<COpaquePointerVar>()
        data.value = null
        api.check(api.GetTensorMutableData!!(output, data.ptr), "GetTensorMutableData")
        val source = data.value ?: throw IllegalStateException("ONNX Runtime returned no data for '$outputName'")
        val result = FloatArray(count.toInt())
        if (count > 0) result.usePinned { memcpy(it.addressOf(0), source, checkedBytes(count, Float.SIZE_BYTES).convert()) }
        return result
    }

    companion object {
        const val INPUT_IDS = "input_ids"
        const val ATTENTION_MASK = "attention_mask"
        const val TOKEN_TYPE_IDS = "token_type_ids"
    }
}

/** [elements] × [size] bytes, failing instead of overflowing. */
internal fun checkedBytes(elements: Long, size: Int): Long {
    require(elements >= 0) { "negative element count $elements" }
    check(elements <= Long.MAX_VALUE / size) { "$elements elements of $size bytes overflow" }
    return elements * size
}

/** The element type and dimensions of [info]; dynamic dimensions are -1. */
@OptIn(ExperimentalForeignApi::class)
internal fun MemScope.typeAndShape(api: OrtApi, info: CPointer<OrtTensorTypeAndShapeInfo>): Pair<ONNXTensorElementDataType, LongArray> {
    val type = alloc<ONNXTensorElementDataType.Var>()
    api.check(api.GetTensorElementType!!(info, type.ptr), "GetTensorElementType")
    val rank = alloc<size_tVar>()
    api.check(api.GetDimensionsCount!!(info, rank.ptr), "GetDimensionsCount")
    val n = rank.value.toInt()
    val dims = allocArray<LongVar>(maxOf(n, 1))
    api.check(api.GetDimensions!!(info, dims, rank.value), "GetDimensions")
    return type.value to LongArray(n) { dims[it] }
}
