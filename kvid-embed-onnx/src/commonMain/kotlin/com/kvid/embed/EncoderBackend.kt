package com.kvid.embed

/**
 * Runs the encoder graph on one padded batch. Inputs are row-major `[batch, seqLen]`; the result is the
 * token hidden states, row-major `[batch, seqLen, dimensions]`. Implementations check the shape their
 * runtime reports ([checkHiddenStatesShape]) and are safe to call from several threads.
 */
internal interface EncoderBackend : AutoCloseable {
    fun run(inputIds: LongArray, attentionMask: LongArray, tokenTypeIds: LongArray, batch: Int, seqLen: Int): FloatArray
}
