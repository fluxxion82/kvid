package com.kvid.core

import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSError
import kotlinx.cinterop.*
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.stringByDeletingLastPathComponent
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS-specific HNSW implementation with file persistence
 *
 * Uses Foundation's FileManager to store index files in the app's documents
 * or cache directories
 */
@OptIn(ExperimentalForeignApi::class)
class IosHnswVectorIndex(
    embedding: SemanticEmbedding,
    maxM: Int = 16,
    efConstruction: Int = 200,
    ml: Float = 1.0f / kotlin.math.ln(2.0f),
    seed: Long = 42
) : HnswVectorIndex(embedding, maxM, efConstruction, ml, seed) {

    private val fileManager = NSFileManager.defaultManager

    override fun saveToDisk(path: String, content: String) {
        try {
            val nsPath = path as NSString
            val parentPath = nsPath.stringByDeletingLastPathComponent

            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                fileManager.createDirectoryAtPath(
                    parentPath,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = error.ptr
                )

                if (error.value != null) {
                    throw RuntimeException("Failed to create directory at $parentPath")
                }
            }

            // Write file
            memScoped {
                val errorWrite = alloc<ObjCObjectVar<NSError?>>()
                val nsContent = content as NSString
                val success = nsContent.writeToFile(
                    path,
                    atomically = true,
                    encoding = NSUTF8StringEncoding,
                    error = errorWrite.ptr
                )

                if (!success) {
                    throw RuntimeException("Failed to write vector index to $path")
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to save vector index to $path: ${e.message}", e)
        }
    }

    override fun loadFromDisk(path: String): String? {
        return try {
            if (fileManager.fileExistsAtPath(path)) {
                memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    NSString.stringWithContentsOfFile(
                        path = path,
                        encoding = NSUTF8StringEncoding,
                        error = error.ptr
                    )
                }
            } else {
                null
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to load vector index from $path: ${e.message}", e)
        }
    }

    companion object {
        /**
         * Get path to app's documents directory
         */
        fun getDocumentsDirectoryPath(): String {
            val paths = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true
            )
            return (paths.firstOrNull() as? NSString)?.toString() ?: ""
        }

        /**
         * Get path to app's cache directory
         */
        fun getCacheDirectoryPath(): String {
            val paths = NSSearchPathForDirectoriesInDomains(
                NSCachesDirectory,
                NSUserDomainMask,
                true
            )
            return (paths.firstOrNull() as? NSString)?.toString() ?: ""
        }

        /**
         * Create a vector index that persists to app's documents directory
         * Files persist across app sessions
         */
        fun createInDocumentsDir(
            embedding: SemanticEmbedding,
            fileName: String = "vector_index.bin",
            maxM: Int = 16,
            efConstruction: Int = 200
        ): IosHnswVectorIndex {
            val docsPath = getDocumentsDirectoryPath()
            val nsPath = docsPath as NSString
            val filePath = nsPath.stringByAppendingPathComponent(fileName)
            return IosHnswVectorIndex(
                embedding = embedding,
                maxM = maxM,
                efConstruction = efConstruction
            )
        }

        /**
         * Create a vector index that persists to app's cache directory
         * Files are cleaned up when app cache is cleared
         */
        fun createInCacheDir(
            embedding: SemanticEmbedding,
            fileName: String = "vector_index.bin",
            maxM: Int = 16,
            efConstruction: Int = 200
        ): IosHnswVectorIndex {
            val cachePath = getCacheDirectoryPath()
            val nsPath = cachePath as NSString
            val filePath = nsPath.stringByAppendingPathComponent(fileName)
            return IosHnswVectorIndex(
                embedding = embedding,
                maxM = maxM,
                efConstruction = efConstruction
            )
        }
    }
}

/**
 * iOS-specific Flat Vector Index implementation with file persistence
 */
@OptIn(ExperimentalForeignApi::class)
class IosFlatVectorIndex(embedding: SemanticEmbedding) : FlatVectorIndex(embedding) {

    private val fileManager = NSFileManager.defaultManager

    override fun saveToDisk(path: String, content: String) {
        try {
            val nsPath = path as NSString
            val parentPath = nsPath.stringByDeletingLastPathComponent

            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                fileManager.createDirectoryAtPath(
                    parentPath,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = error.ptr
                )

                if (error.value != null) {
                    throw RuntimeException("Failed to create directory at $parentPath")
                }
            }

            memScoped {
                val errorWrite = alloc<ObjCObjectVar<NSError?>>()
                val nsContent = content as NSString
                val success = nsContent.writeToFile(
                    path,
                    atomically = true,
                    encoding = NSUTF8StringEncoding,
                    error = errorWrite.ptr
                )

                if (!success) {
                    throw RuntimeException("Failed to write vector index to $path")
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to save vector index to $path: ${e.message}", e)
        }
    }

    override fun loadFromDisk(path: String): String? {
        return try {
            if (fileManager.fileExistsAtPath(path)) {
                memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    NSString.stringWithContentsOfFile(
                        path = path,
                        encoding = NSUTF8StringEncoding,
                        error = error.ptr
                    )
                }
            } else {
                null
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to load vector index from $path: ${e.message}", e)
        }
    }

    companion object {
        /**
         * Create a flat vector index that persists to app's documents directory
         */
        fun createInDocumentsDir(
            embedding: SemanticEmbedding,
            fileName: String = "flat_vector_index.bin"
        ): IosFlatVectorIndex {
            return IosFlatVectorIndex(embedding)
        }

        /**
         * Create a flat vector index that persists to app's cache directory
         */
        fun createInCacheDir(
            embedding: SemanticEmbedding,
            fileName: String = "flat_vector_index.bin"
        ): IosFlatVectorIndex {
            return IosFlatVectorIndex(embedding)
        }
    }
}

/**
 * Factory functions for creating the appropriate indexes for iOS
 */
fun createIosHnswIndex(
    embedding: SemanticEmbedding,
    fileName: String = "vector_index.bin",
    maxM: Int = 16,
    efConstruction: Int = 200
): VectorIndex {
    return IosHnswVectorIndex(embedding, maxM, efConstruction)
}

fun createIosFlatIndex(
    embedding: SemanticEmbedding,
    fileName: String = "flat_vector_index.bin"
): VectorIndex {
    return IosFlatVectorIndex(embedding)
}
