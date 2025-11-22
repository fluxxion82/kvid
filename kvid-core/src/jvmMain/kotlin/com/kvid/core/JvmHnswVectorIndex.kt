package com.kvid.core

import java.io.File

/**
 * JVM-specific HNSW implementation with file persistence
 */
class JvmHnswVectorIndex(
    embedding: SemanticEmbedding,
    maxM: Int = 16,
    efConstruction: Int = 200,
    ml: Float = 1.0f / kotlin.math.ln(2.0f),
    seed: Long = 42
) : HnswVectorIndex(embedding, maxM, efConstruction, ml, seed) {

    override fun saveToDisk(path: String, content: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override fun loadFromDisk(path: String): String? {
        val file = File(path)
        return if (file.exists()) file.readText() else null
    }
}

/**
 * JVM-specific Flat Vector Index implementation with file persistence
 */
class JvmFlatVectorIndex(embedding: SemanticEmbedding) : FlatVectorIndex(embedding) {

    override fun saveToDisk(path: String, content: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override fun loadFromDisk(path: String): String? {
        val file = File(path)
        return if (file.exists()) file.readText() else null
    }
}

/**
 * Factory functions for creating the appropriate indexes for the JVM platform
 */
fun createHnswIndex(
    embedding: SemanticEmbedding,
    maxM: Int = 16,
    efConstruction: Int = 200,
    ml: Float = 1.0f / kotlin.math.ln(2.0f),
    seed: Long = 42
): VectorIndex {
    return JvmHnswVectorIndex(embedding, maxM, efConstruction, ml, seed)
}

fun createFlatIndex(embedding: SemanticEmbedding): VectorIndex {
    return JvmFlatVectorIndex(embedding)
}
