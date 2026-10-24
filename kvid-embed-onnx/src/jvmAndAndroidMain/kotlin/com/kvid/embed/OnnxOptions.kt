package com.kvid.embed

/**
 * Runtime settings of an [OnnxEmbedder]; none of them change the vectors beyond float rounding. The
 * defaults come from measurements on Android (roadmap, Milestone 5): sorted batches of 4 were the fastest
 * setting on notes of mixed length, and without the CPU arena memory returns to its level after open
 * once a call finishes, at about 10% more time per note.
 */
data class OnnxOptions(
    /** Threads ONNX Runtime uses inside one inference; null keeps its default (one per physical core). */
    val intraOpThreads: Int? = null,
    /** Most texts run in one inference; larger requests are split. Larger batches raise peak memory. */
    val maxBatchSize: Int = 4,
    /** Group texts of similar token length into one inference, so they pad less; results keep input order. */
    val sortByLength: Boolean = true,
    /** ONNX Runtime's CPU memory arena, which keeps its peak allocation for reuse while the embedder is open. */
    val cpuMemoryArena: Boolean = false,
    /** ONNX Runtime's memory pattern planning, which caches allocation plans per input shape. */
    val memoryPattern: Boolean = true
) {
    init {
        require(intraOpThreads == null || intraOpThreads > 0) { "intraOpThreads must be positive" }
        require(maxBatchSize > 0) { "maxBatchSize must be positive" }
    }
}
