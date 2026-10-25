package com.kvid.embed

/**
 * Runtime settings of an [OnnxEmbedder]; none of them change the vectors beyond float rounding. The
 * defaults come from measurements on Android (roadmap, Milestone 5): sorted batches of 4 were the fastest
 * setting on notes of mixed length, and the CPU arena is on because turning it off made inference 1.6 to
 * 1.9 times slower on a Pixel 8.
 */
data class OnnxOptions(
    /** Threads ONNX Runtime uses inside one inference; null keeps its default (one per physical core). */
    val intraOpThreads: Int? = null,
    /** Most texts run in one inference; larger requests are split. Larger batches raise peak memory. */
    val maxBatchSize: Int = 4,
    /** Group texts of similar token length into one inference, so they pad less; results keep input order. */
    val sortByLength: Boolean = true,
    /**
     * ONNX Runtime's CPU memory arena, which keeps its peak allocation for reuse while the embedder is open
     * and releases it on [OnnxEmbedder.close]. With the default batch size the process holds about 460 MB
     * after embedding on Android, against about 220 MB without the arena. Set it to false in
     * memory-constrained processes that keep the embedder open: memory then returns to its level after
     * open once each call finishes, but inference took 1.6 to 1.9 times as long on a Pixel 8 (about 1.1
     * times on an emulator).
     */
    val cpuMemoryArena: Boolean = true,
    /** ONNX Runtime's memory pattern planning, which caches allocation plans per input shape. */
    val memoryPattern: Boolean = true
) {
    init {
        require(intraOpThreads == null || intraOpThreads > 0) { "intraOpThreads must be positive" }
        require(maxBatchSize > 0) { "maxBatchSize must be positive" }
    }
}
