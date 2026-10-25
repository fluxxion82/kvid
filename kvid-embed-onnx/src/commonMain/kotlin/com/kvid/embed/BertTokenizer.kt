package com.kvid.embed

/**
 * The BERT uncased WordPiece tokenizer of `bert-base-uncased`-derived models such as
 * `BAAI/bge-small-en-v1.5` (ADR 0002). It reproduces Hugging Face `tokenizers` token for token, with the
 * same result on every platform:
 *
 * 1. Special tokens ([Options.specialTokens]) are matched in the raw text, case-sensitively and even
 *    inside words, and passed through unchanged.
 * 2. The rest is normalized: control, format and private-use characters are removed, whitespace becomes
 *    a space, CJK ideographs are surrounded by spaces, accents are stripped and text is lowercased.
 * 3. It is split on whitespace, and every punctuation character becomes its own word.
 * 4. Each word is split into the longest vocabulary pieces from the left, later pieces carrying the
 *    `##` prefix. A word with no complete split, or longer than [Options.maxInputCharsPerWord] code
 *    points, becomes the unknown token.
 *
 * Normalization works code point by code point from tables generated from the reference library; it
 * differs from whole-string normalization only in the canonical order of combining marks that accent
 * stripping keeps, which does not occur in practice.
 */
class BertTokenizer private constructor(
    private val ids: Map<String, Int>,
    private val pieces: List<String>,
    val options: Options
) {
    data class Options(
        val unknownToken: String = "[UNK]",
        val classifierToken: String = "[CLS]",
        val separatorToken: String = "[SEP]",
        val specialTokens: List<String> = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"),
        val continuingSubwordPrefix: String = "##",
        val maxInputCharsPerWord: Int = 100
    ) {
        init {
            require(maxInputCharsPerWord > 0) { "maxInputCharsPerWord must be positive" }
            require(specialTokens.none { it.isEmpty() }) { "special tokens must not be empty" }
        }
    }

    /** Token ids and their pieces, framed as `[CLS] … [SEP]`. */
    class Encoding(val ids: IntArray, val tokens: List<String>)

    private val unknownId = idOf(options.unknownToken)
    private val classifierId = idOf(options.classifierToken)
    private val separatorId = idOf(options.separatorToken)
    private val specials = options.specialTokens.distinct().sortedByDescending { it.length }.map { it to idOf(it) }

    init {
        require(unknownId != null && classifierId != null && separatorId != null) {
            "the vocabulary lacks ${listOf(options.unknownToken, options.classifierToken, options.separatorToken).filter { it !in ids }}"
        }
        require(specials.all { it.second != null }) { "the vocabulary lacks special tokens ${specials.filter { it.second == null }.map { it.first }}" }
    }

    val vocabularySize: Int get() = pieces.size

    fun idOf(token: String): Int? = ids[token]

    fun tokenOf(id: Int): String? = pieces.getOrNull(id)

    /** The word pieces of [text], without the `[CLS]`/`[SEP]` frame and without truncation. */
    fun tokenize(text: String): List<String> = pieceIds(text).map { pieces[it] }

    /**
     * Model input for [text]: `[CLS]`, its pieces, `[SEP]`, truncating the pieces so the whole has at
     * most [maxLength] ids (the model's limit, 512 for BERT-sized models).
     */
    fun encode(text: String, maxLength: Int = 512): Encoding {
        require(maxLength >= 2) { "maxLength must leave room for [CLS] and [SEP]" }
        val body = pieceIds(text).let { if (it.size > maxLength - 2) it.subList(0, maxLength - 2) else it }
        val out = IntArray(body.size + 2)
        out[0] = classifierId!!
        body.forEachIndexed { i, id -> out[i + 1] = id }
        out[out.size - 1] = separatorId!!
        return Encoding(out, out.map { pieces[it] })
    }

    private fun pieceIds(text: String): List<Int> {
        val out = ArrayList<Int>()
        var segmentStart = 0
        var i = 0
        while (i < text.length) {
            val special = specials.firstOrNull { (token, _) -> text.startsWith(token, i) }
            if (special == null) { i++; continue }
            wordPieces(text.substring(segmentStart, i), out)
            out += special.second!!
            i += special.first.length
            segmentStart = i
        }
        wordPieces(text.substring(segmentStart), out)
        return out
    }

    private fun wordPieces(segment: String, out: MutableList<Int>) {
        if (segment.isEmpty()) return
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) { splitWord(word.toString(), out); word.clear() }
        }
        forEachCodePoint(BertCharacters.normalize(segment)) { cp ->
            when {
                BertCharacters.isWhitespace(cp) -> flush()
                BertCharacters.isPunctuation(cp) -> { flush(); word.appendCodePoint(cp); flush() }
                else -> word.appendCodePoint(cp)
            }
        }
        flush()
    }

    /** Greedy longest-match-first WordPiece over code points. */
    private fun splitWord(word: String, out: MutableList<Int>) {
        val offsets = ArrayList<Int>(word.length + 1)
        forEachCodePointOffset(word) { offsets += it }
        offsets += word.length
        val codePoints = offsets.size - 1
        if (codePoints > options.maxInputCharsPerWord) { out += unknownId!!; return }
        val found = ArrayList<Int>()
        var start = 0
        while (start < codePoints) {
            var end = codePoints
            var match: Int? = null
            while (start < end) {
                val piece = word.substring(offsets[start], offsets[end])
                match = ids[if (start > 0) options.continuingSubwordPrefix + piece else piece]
                if (match != null) break
                end--
            }
            if (match == null) { out += unknownId!!; return }
            found += match
            start = end
        }
        out += found
    }

    companion object {
        /** A tokenizer over `vocab.txt` content: one token per line, the id being the line number. */
        fun fromVocabulary(vocabText: String, options: Options = Options()): BertTokenizer {
            val pieces = vocabText.lines().let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }.map { it.trimEnd() }
            val ids = HashMap<String, Int>(pieces.size * 2)
            // As in the reference, a repeated token keeps the id of its last line.
            pieces.forEachIndexed { index, token -> ids[token] = index }
            return BertTokenizer(ids, pieces, options)
        }
    }
}

/** Calls [action] with the char offset of each code point of [text]. */
private inline fun forEachCodePointOffset(text: String, action: (Int) -> Unit) {
    var i = 0
    while (i < text.length) {
        action(i)
        i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
    }
}
