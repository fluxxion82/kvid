package com.kvid.store

import kotlin.random.Random

/**
 * UUID version 7 (RFC 9562): 48-bit Unix millisecond timestamp, then random bits. Time-ordered,
 * so ids sort by creation time and cluster well in a B-tree. The random part uses the platform's
 * default generator, which is adequate for identity, not for secrecy.
 */
internal object Ids {
    private const val HEX = "0123456789abcdef"

    fun uuidV7(nowMs: Long, random: Random = Random.Default): String {
        val bytes = ByteArray(16)
        var t = nowMs
        for (i in 5 downTo 0) { bytes[i] = (t and 0xFF).toByte(); t = t ushr 8 }
        random.nextBytes(bytes, 6, 16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()   // version 7
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()   // RFC 4122 variant
        val sb = StringBuilder(36)
        for ((i, b) in bytes.withIndex()) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Caller-supplied ids are accepted when they are non-empty, printable, and at most 128 bytes. */
    fun validateDocumentId(id: String) {
        if (id.isEmpty()) throw KvidException.Usage("documentId must not be empty")
        if (id.encodeToByteArray().size > 128) throw KvidException.LimitExceeded("documentId exceeds 128 bytes")
        if (id.any { it.isISOControl() }) throw KvidException.Usage("documentId must not contain control characters")
    }

    fun isUuid(id: String): Boolean = uuidPattern.matches(id)
}
