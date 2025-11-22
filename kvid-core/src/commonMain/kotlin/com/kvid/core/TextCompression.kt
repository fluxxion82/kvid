package com.kvid.core

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Text compression utilities for QR code data
 *
 * This provides a platform-independent interface for compressing text data
 * before encoding into QR codes, similar to memvid's gzip compression.
 *
 * The compression format uses a "GZ:" prefix to indicate compressed data,
 * allowing transparent compression/decompression.
 */
@OptIn(ExperimentalEncodingApi::class)
object TextCompression {
    const val COMPRESSION_PREFIX = "GZ:"
    const val MIN_COMPRESSION_LENGTH = 100

    /**
     * Compress text data if beneficial
     * @param data The text to compress
     * @param threshold Minimum length to attempt compression (default: 100)
     * @return Compressed data with GZ: prefix, or original if compression not beneficial
     */
    fun compress(data: String, threshold: Int = MIN_COMPRESSION_LENGTH): String {
        if (data.length < threshold) {
            return data
        }

        return try {
            val compressed = compressBytes(data.encodeToByteArray())
            val encoded = Base64.encode(compressed)
            COMPRESSION_PREFIX + encoded
        } catch (e: Exception) {
            // If compression fails, return original
            data
        }
    }

    /**
     * Decompress text data if it was compressed
     * @param data The text to decompress
     * @return Decompressed data or original if not compressed
     */
    fun decompress(data: String): String {
        if (!data.startsWith(COMPRESSION_PREFIX)) {
            return data
        }

        return try {
            val encoded = data.substring(COMPRESSION_PREFIX.length)
            val compressed = Base64.decode(encoded)
            val decompressed = decompressBytes(compressed)
            decompressed.decodeToString()
        } catch (e: Exception) {
            throw RuntimeException("Failed to decompress data: ${e.message}", e)
        }
    }

    /**
     * Check if data is compressed
     */
    fun isCompressed(data: String): Boolean {
        return data.startsWith(COMPRESSION_PREFIX)
    }
}

/**
 * Platform-specific compression implementation
 */
internal expect fun compressBytes(data: ByteArray): ByteArray

/**
 * Platform-specific decompression implementation
 */
internal expect fun decompressBytes(data: ByteArray): ByteArray
