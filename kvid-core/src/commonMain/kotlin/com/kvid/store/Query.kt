package com.kvid.store

/**
 * Compiles [QuerySyntax.PLAIN] text into an FTS5 expression (persistence contract, section 7).
 *
 * The query is split on Unicode whitespace. Each piece becomes one quoted FTS5 string, so operators,
 * parentheses, quotes and column filters stay literal, and FTS5's `unicode61` tokenizer applies the same
 * case folding, diacritic removal and separator rules to the query as to the indexed text. Pieces that
 * FTS5 would tokenize to nothing (punctuation, symbols, most emoji) are dropped instead of being passed
 * on as empty phrases, so `plan ???` means `plan`. Pieces are deduplicated case-insensitively so a
 * repeated word does not weigh twice in the score.
 */
internal object PlainQuery {
    /** The FTS5 expression, or null when the query has no searchable pieces. */
    fun compile(query: String, match: MatchMode): String? {
        val pieces = LinkedHashMap<String, String>()
        for (rawPiece in split(query)) {
            // FTS5 treats NUL as the end of its expression; preserve it as a token separator.
            val piece = rawPiece.replace('\u0000', ' ')
            if (!hasTokenCharacter(piece)) continue
            val key = piece.lowercase()
            if (key !in pieces) pieces[key] = piece
        }
        if (pieces.isEmpty()) return null
        if (pieces.size > Limits.MAX_QUERY_TERMS) throw KvidException.LimitExceeded("query has more than ${Limits.MAX_QUERY_TERMS} terms")
        val operator = if (match == MatchMode.ALL) " AND " else " OR "
        return pieces.values.joinToString(operator) { "\"" + it.replace("\"", "\"\"") + "\"" }
    }

    private fun split(query: String): List<String> {
        val pieces = ArrayList<String>()
        val current = StringBuilder()
        for (ch in query) {
            if (ch.isWhitespace()) {
                if (current.isNotEmpty()) { pieces += current.toString(); current.clear() }
            } else current.append(ch)
        }
        if (current.isNotEmpty()) pieces += current.toString()
        return pieces
    }

    /**
     * Whether unicode61 would produce at least one token: its default token categories are `L* N* Co`.
     * Supplementary code points cannot be classified from common Kotlin. The symbol blocks listed in
     * [SUPPLEMENTARY_SEPARATORS] count as separators and every other supplementary code point (historic
     * scripts, CJK extensions, mathematical letters) as a letter. This is an approximation of SQLite's
     * own tables: a piece made only of characters SQLite does not treat as token characters becomes an
     * empty phrase, which makes a [MatchMode.ALL] query match nothing.
     */
    private fun hasTokenCharacter(piece: String): Boolean {
        var i = 0
        while (i < piece.length) {
            val ch = piece[i]
            if (ch.isHighSurrogate() && i + 1 < piece.length && piece[i + 1].isLowSurrogate()) {
                val codePoint = 0x10000 + ((ch.code - 0xD800) shl 10) + (piece[i + 1].code - 0xDC00)
                if (SUPPLEMENTARY_SEPARATORS.none { codePoint in it }) return true
                i += 2
                continue
            }
            when (ch.category) {
                CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER,
                CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER,
                CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER,
                CharCategory.PRIVATE_USE -> return true
                else -> {}
            }
            i++
        }
        return false
    }

    /** Musical symbols, Tai Xuan Jing symbols, emoji and pictographs, tags and variation selectors. */
    private val SUPPLEMENTARY_SEPARATORS = listOf(0x1D000..0x1D24F, 0x1D300..0x1D35F, 0x1F000..0x1FFFF, 0xE0000..0xE0FFF)
}
