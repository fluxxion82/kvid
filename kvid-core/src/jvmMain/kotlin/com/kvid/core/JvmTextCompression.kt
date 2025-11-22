package com.kvid.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * JVM implementation of text compression using GZIP
 */
internal actual fun compressBytes(data: ByteArray): ByteArray {
    val outputStream = ByteArrayOutputStream()
    GZIPOutputStream(outputStream).use { gzip ->
        gzip.write(data)
    }
    return outputStream.toByteArray()
}

internal actual fun decompressBytes(data: ByteArray): ByteArray {
    val inputStream = ByteArrayInputStream(data)
    val outputStream = ByteArrayOutputStream()
    GZIPInputStream(inputStream).use { gzip ->
        gzip.copyTo(outputStream)
    }
    return outputStream.toByteArray()
}
