package com.kvid.embed

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * [EncoderBackend] over an ONNX Runtime session. It owns the session and the [OrtSession.SessionOptions] it was
 * created with (ONNX Runtime requires the options to outlive the session) and closes both, in that
 * order, once. Inference holds a read lock only for the synchronous native call, so calls from several
 * threads run concurrently, and [close] waits for running calls before releasing native memory.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class OrtEncoderBackend(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val sessionOptions: OrtSession.SessionOptions,
    private val outputName: String,
    private val dimensions: Int
) : EncoderBackend {
    private val lock = ReentrantReadWriteLock()
    private var closed = false
    private val tokenTypeIdsInput = TOKEN_TYPE_IDS in session.inputNames
    private val outputs = setOf(outputName)

    init {
        BackendHooks.live.incrementAndFetch()
    }

    override fun run(inputIds: LongArray, attentionMask: LongArray, tokenTypeIds: LongArray, batch: Int, seqLen: Int): FloatArray = lock.read {
        check(!closed) { "OnnxEmbedder is closed" }
        BackendHooks.insideRun.load()?.invoke()
        val shape = longArrayOf(batch.toLong(), seqLen.toLong())
        val tensors = ArrayList<OnnxTensor>(3)
        try {
            val inputs = LinkedHashMap<String, OnnxTensor>()
            fun input(name: String, values: LongArray) {
                val tensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)
                tensors += tensor
                inputs[name] = tensor
            }
            input(INPUT_IDS, inputIds)
            input(ATTENTION_MASK, attentionMask)
            if (tokenTypeIdsInput) input(TOKEN_TYPE_IDS, tokenTypeIds)
            session.run(inputs, outputs).use { result ->
                val value = result.get(outputName).orElse(null)
                check(value is OnnxTensor && value.info.type == OnnxJavaType.FLOAT) {
                    "the model output '$outputName' is ${value?.info ?: "missing"}, expected a float tensor"
                }
                checkHiddenStatesShape(value.info.shape, batch, seqLen, dimensions)
                val buffer = value.floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    /** Waits for running inferences, then closes the session and its options; later calls do nothing. */
    override fun close() {
        lock.write {
            if (closed) return
            closed = true
            try {
                session.close()
            } finally {
                sessionOptions.close()
                BackendHooks.live.decrementAndFetch()
            }
        }
    }

    companion object {
        const val INPUT_IDS = "input_ids"
        const val ATTENTION_MASK = "attention_mask"
        const val TOKEN_TYPE_IDS = "token_type_ids"
    }
}
