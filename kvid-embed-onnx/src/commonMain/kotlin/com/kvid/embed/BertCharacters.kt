package com.kvid.embed

/**
 * Per-code-point BERT normalization and pre-tokenization classes, decoded once from [BertTables].
 * Using generated tables instead of the platform's Unicode data keeps JVM, Android and iOS identical
 * to each other and to the Hugging Face reference.
 */
internal object BertCharacters {
    private val removed = CodePointRanges.parse(BertTables.REMOVED)
    private val padded = CodePointRanges.parse(BertTables.PADDED)
    private val punctuation = CodePointRanges.parse(BertTables.PUNCTUATION)
    private val whitespace = CodePointRanges.parse(BertTables.WHITESPACE)
    private val mapped: Map<Int, String> = buildMap {
        for (entry in BertTables.MAPPED.joinToString("").split(';')) {
            if (entry.isEmpty()) continue
            val (from, to) = entry.split('=')
            put(from.toInt(16), buildString { to.split('.').forEach { appendCodePoint(it.toInt(16)) } })
        }
    }

    /**
     * The BERT uncased normalization: removes control, format and private-use characters, maps
     * whitespace to spaces, surrounds CJK ideographs with spaces, strips accents and lowercases.
     * Unpaired surrogates, which have no Unicode scalar value, are removed.
     */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length + 8)
        forEachCodePoint(text) { cp ->
            when {
                cp < 0 || cp in removed -> {}
                cp in padded -> { out.append(' '); out.appendCodePoint(cp); out.append(' ') }
                else -> mapped[cp]?.let { out.append(it) } ?: out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    fun isWhitespace(cp: Int): Boolean = cp in whitespace
    fun isPunctuation(cp: Int): Boolean = cp in punctuation
}

/** Sorted, non-overlapping inclusive code point ranges with binary search. */
internal class CodePointRanges private constructor(private val starts: IntArray, private val ends: IntArray) {
    operator fun contains(cp: Int): Boolean {
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < starts[mid] -> hi = mid - 1
                cp > ends[mid] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    companion object {
        fun parse(chunks: Array<String>): CodePointRanges {
            val items = chunks.joinToString("").split(',').filter { it.isNotEmpty() }
            val starts = IntArray(items.size)
            val ends = IntArray(items.size)
            items.forEachIndexed { i, item ->
                val dash = item.indexOf('-')
                starts[i] = (if (dash < 0) item else item.substring(0, dash)).toInt(16)
                ends[i] = (if (dash < 0) item else item.substring(dash + 1)).toInt(16)
            }
            return CodePointRanges(starts, ends)
        }
    }
}

/** Calls [action] with each code point of [text]; an unpaired surrogate is reported as -1. */
internal inline fun forEachCodePoint(text: String, action: (Int) -> Unit) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            action(0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00))
            i += 2
        } else {
            action(if (c.isSurrogate()) -1 else c.code)
            i++
        }
    }
}

internal fun StringBuilder.appendCodePoint(cp: Int): StringBuilder {
    if (cp < 0x10000) return append(cp.toChar())
    val v = cp - 0x10000
    append((0xD800 + (v ushr 10)).toChar())
    return append((0xDC00 + (v and 0x3FF)).toChar())
}
