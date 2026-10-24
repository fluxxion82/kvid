package com.kvid.embed

/**
 * Requires a runtime output [shape] of exactly `[batch, seqLen, dimensions]`. Checking the element
 * count alone would accept a transposed or reshaped output and pool the wrong values.
 */
internal fun checkHiddenStatesShape(shape: LongArray, batch: Int, seqLen: Int, dimensions: Int) {
    val expected = longArrayOf(batch.toLong(), seqLen.toLong(), dimensions.toLong())
    check(shape.contentEquals(expected)) {
        "the model output has shape ${shape.contentToString()}, expected [batch, sequence, dimensions] = ${expected.contentToString()}"
    }
}
