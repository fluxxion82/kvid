package com.kvid.embed

import java.io.File
import java.security.MessageDigest

private const val HEX = "0123456789abcdef"

/** SHA-256 of [file] as lowercase hex, read in 1 MiB chunks so a large model is never held in memory. */
internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 20)
    file.inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return buildString(64) {
        for (b in digest.digest()) append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
    }
}
