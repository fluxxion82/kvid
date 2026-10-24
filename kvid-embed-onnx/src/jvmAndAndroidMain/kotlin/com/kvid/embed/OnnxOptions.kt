package com.kvid.embed

/** Runtime settings of an [OnnxEmbedder]; none of them change the vectors beyond float rounding. */
data class OnnxOptions(
    /** Threads ONNX Runtime uses inside one inference; null keeps its default (one per physical core). */
    val intraOpThreads: Int? = null,
    /** Most texts run in one inference; larger requests are split, smaller batches pad less. */
    val maxBatchSize: Int = 16
) {
    init {
        require(intraOpThreads == null || intraOpThreads > 0) { "intraOpThreads must be positive" }
        require(maxBatchSize > 0) { "maxBatchSize must be positive" }
    }
}
