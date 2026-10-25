package com.kvid.embed

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.posix.FILE
import platform.posix.R_OK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.access
import platform.posix.errno
import platform.posix.fclose
import platform.posix.feof
import platform.posix.ferror
import platform.posix.fopen
import platform.posix.fread
import platform.posix.stat
import platform.posix.strerror

private const val HEX = "0123456789abcdef"
private const val BLOCK = 1 shl 20

@OptIn(ExperimentalForeignApi::class)
private fun lastError(): String = strerror(errno)?.toKString() ?: "errno $errno"

/** True when [path] is a regular file this process may read. */
@OptIn(ExperimentalForeignApi::class)
internal fun isReadableFile(path: String): Boolean = memScoped {
    val info = alloc<stat>()
    stat(path, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFREG && access(path, R_OK) == 0
}

/**
 * Streams the file at [path] to [block] in chunks of at most 1 MiB. An I/O error, as distinct from the
 * end of the file (`ferror` against `feof`), throws, so a caller never sees a prefix as the whole file;
 * the file is closed on every path, and a failing `fclose` throws too.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun readBlocks(path: String, block: (CPointer<*>, Int) -> Unit) {
    val file: CPointer<FILE> = fopen(path, "rb") ?: throw IllegalStateException("file '$path' could not be opened: ${lastError()}")
    try {
        ByteArray(BLOCK).usePinned { buffer ->
            while (true) {
                val read = fread(buffer.addressOf(0), 1u, BLOCK.convert(), file).toInt()
                if (read > 0) block(buffer.addressOf(0), read)
                if (read < BLOCK) {
                    if (ferror(file) != 0) throw IllegalStateException("file '$path' could not be read: ${lastError()}")
                    if (feof(file) != 0) break
                }
            }
        }
    } catch (e: Throwable) {
        fclose(file)
        throw e
    }
    if (fclose(file) != 0) throw IllegalStateException("file '$path' could not be closed: ${lastError()}")
}

/** The whole file at [path], decoded as UTF-8 (malformed bytes become U+FFFD, as on the JVM). */
@OptIn(ExperimentalForeignApi::class)
internal fun readTextFile(path: String): String {
    val chunks = ArrayList<ByteArray>()
    var size = 0L
    readBlocks(path) { data, length ->
        val chunk = ByteArray(length)
        chunk.usePinned { platform.posix.memcpy(it.addressOf(0), data, length.convert()) }
        chunks += chunk
        size += length
    }
    check(size <= Int.MAX_VALUE) { "file '$path' is too large: $size bytes" }
    val bytes = ByteArray(size.toInt())
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(bytes, offset)
        offset += chunk.size
    }
    return bytes.decodeToString()
}

/**
 * SHA-256 of the file at [path] as lowercase hex, with CommonCrypto, read in 1 MiB blocks so a large model
 * is never held in memory. Every CommonCrypto return value is checked (1 is success).
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256Hex(path: String): String = memScoped {
    val context = alloc<CC_SHA256_CTX>()
    check(CC_SHA256_Init(context.ptr) == 1) { "CC_SHA256_Init failed" }
    readBlocks(path) { data, length ->
        check(CC_SHA256_Update(context.ptr, data, length.toUInt()) == 1) { "CC_SHA256_Update failed for '$path'" }
    }
    val digest = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
    check(CC_SHA256_Final(digest, context.ptr) == 1) { "CC_SHA256_Final failed for '$path'" }
    buildString(2 * CC_SHA256_DIGEST_LENGTH) {
        for (i in 0 until CC_SHA256_DIGEST_LENGTH) {
            val b = digest[i].toInt()
            append(HEX[b shr 4]).append(HEX[b and 0xF])
        }
    }
}
