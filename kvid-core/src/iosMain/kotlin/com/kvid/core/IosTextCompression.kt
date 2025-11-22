@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.kvid.core

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.zlib.*

/**
 * iOS implementation of text compression using zlib
 */
internal actual fun compressBytes(data: ByteArray): ByteArray {
    return data.usePinned { pinned ->
        val nsData = NSData.create(
            bytes = pinned.addressOf(0),
            length = data.size.toULong()
        )

        // Use NSData compression (iOS 13+)
        val compressed = nsData.compressedDataUsingAlgorithm(
            NSDataCompressionAlgorithmZlib,
            error = null
        ) ?: throw RuntimeException("Compression failed")

        val size = compressed.length.toInt()
        val result = ByteArray(size)
        result.usePinned { resultPinned ->
            compressed.getBytes(resultPinned.addressOf(0), size.toULong())
        }
        result
    }
}

internal actual fun decompressBytes(data: ByteArray): ByteArray {
    return data.usePinned { pinned ->
        val nsData = NSData.create(
            bytes = pinned.addressOf(0),
            length = data.size.toULong()
        )

        // Use NSData decompression (iOS 13+)
        val decompressed = nsData.decompressedDataUsingAlgorithm(
            NSDataCompressionAlgorithmZlib,
            error = null
        ) ?: throw RuntimeException("Decompression failed")

        val size = decompressed.length.toInt()
        val result = ByteArray(size)
        result.usePinned { resultPinned ->
            decompressed.getBytes(resultPinned.addressOf(0), size.toULong())
        }
        result
    }
}
