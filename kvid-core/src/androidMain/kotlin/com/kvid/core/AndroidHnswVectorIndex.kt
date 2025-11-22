package com.kvid.core

import android.content.Context
import java.io.File

/**
 * Android-specific HNSW implementation with file persistence
 *
 * Uses Android's context to store index files in the app's cache or files directory
 */
class AndroidHnswVectorIndex(
    embedding: SemanticEmbedding,
    private val context: Context,
    maxM: Int = 16,
    efConstruction: Int = 200,
    ml: Float = 1.0f / kotlin.math.ln(2.0f),
    seed: Long = 42
) : HnswVectorIndex(embedding, maxM, efConstruction, ml, seed) {

    override fun saveToDisk(path: String, content: String) {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
        } catch (e: Exception) {
            throw RuntimeException("Failed to save vector index to $path: ${e.message}", e)
        }
    }

    override fun loadFromDisk(path: String): String? {
        return try {
            val file = File(path)
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            throw RuntimeException("Failed to load vector index from $path: ${e.message}", e)
        }
    }

    companion object {
        /**
         * Create a vector index that persists to app's cache directory
         * Files are cleaned up when app cache is cleared
         */
        fun createInCacheDir(
            context: Context,
            embedding: SemanticEmbedding,
            fileName: String = "vector_index.bin",
            maxM: Int = 16,
            efConstruction: Int = 200
        ): AndroidHnswVectorIndex {
            val indexPath = File(context.cacheDir, fileName)
            return AndroidHnswVectorIndex(
                embedding = embedding,
                context = context,
                maxM = maxM,
                efConstruction = efConstruction
            )
        }

        /**
         * Create a vector index that persists to app's files directory
         * Files persist across app sessions
         */
        fun createInFilesDir(
            context: Context,
            embedding: SemanticEmbedding,
            fileName: String = "vector_index.bin",
            maxM: Int = 16,
            efConstruction: Int = 200
        ): AndroidHnswVectorIndex {
            return AndroidHnswVectorIndex(
                embedding = embedding,
                context = context,
                maxM = maxM,
                efConstruction = efConstruction
            )
        }
    }
}

/**
 * Android-specific Flat Vector Index implementation with file persistence
 */
class AndroidFlatVectorIndex(
    embedding: SemanticEmbedding,
    private val context: Context
) : FlatVectorIndex(embedding) {

    override fun saveToDisk(path: String, content: String) {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
        } catch (e: Exception) {
            throw RuntimeException("Failed to save vector index to $path: ${e.message}", e)
        }
    }

    override fun loadFromDisk(path: String): String? {
        return try {
            val file = File(path)
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            throw RuntimeException("Failed to load vector index from $path: ${e.message}", e)
        }
    }

    companion object {
        /**
         * Create a flat vector index that persists to app's cache directory
         */
        fun createInCacheDir(
            context: Context,
            embedding: SemanticEmbedding,
            fileName: String = "flat_vector_index.bin"
        ): AndroidFlatVectorIndex {
            return AndroidFlatVectorIndex(embedding, context)
        }

        /**
         * Create a flat vector index that persists to app's files directory
         */
        fun createInFilesDir(
            context: Context,
            embedding: SemanticEmbedding,
            fileName: String = "flat_vector_index.bin"
        ): AndroidFlatVectorIndex {
            return AndroidFlatVectorIndex(embedding, context)
        }
    }
}

/**
 * Factory functions for creating the appropriate indexes for Android
 */
fun createAndroidHnswIndex(
    context: Context,
    embedding: SemanticEmbedding,
    fileName: String = "vector_index.bin",
    maxM: Int = 16,
    efConstruction: Int = 200
): VectorIndex {
    return AndroidHnswVectorIndex(embedding, context, maxM, efConstruction)
}

fun createAndroidFlatIndex(
    context: Context,
    embedding: SemanticEmbedding,
    fileName: String = "flat_vector_index.bin"
): VectorIndex {
    return AndroidFlatVectorIndex(embedding, context)
}
