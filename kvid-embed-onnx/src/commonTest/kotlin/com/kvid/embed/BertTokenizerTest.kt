package com.kvid.embed

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BertTokenizerTest {
    private val vocab = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "hello", "world", ",", "!", "un", "##aff", "##able",
        "cafe", "中", "文", "k", "i", "ß", "[", "]", "sep", "a", "##a", "😀", "ab", "##c"
    ).joinToString("\n", postfix = "\n")
    private val tokenizer = BertTokenizer.fromVocabulary(vocab)

    @Test fun splitsOnWhitespaceAndIsolatesPunctuation() {
        assertEquals(listOf("hello", ",", "world", "!"), tokenizer.tokenize("Hello, World!"))
    }

    @Test fun wordPiecesAreTheLongestMatchesFromTheLeft() {
        assertEquals(listOf("un", "##aff", "##able"), tokenizer.tokenize("unaffable"))
        assertEquals(listOf("ab", "##c"), tokenizer.tokenize("abc"))
    }

    @Test fun aWordWithoutACompleteSplitBecomesOneUnknownToken() {
        assertEquals(listOf("[UNK]"), tokenizer.tokenize("unaffablex"))
        assertEquals(listOf("hello", "[UNK]"), tokenizer.tokenize("hello xyz"))
    }

    @Test fun normalizationFoldsCaseAccentsAndCompatibilityLetters() {
        assertEquals(listOf("cafe", "cafe", "cafe"), tokenizer.tokenize("CAFÉ Café café"))
        assertEquals(listOf("k", "i", "ß"), tokenizer.tokenize("K İ ẞ"))
    }

    @Test fun cjkIdeographsAreSeparateWords() {
        assertEquals(listOf("中", "文"), tokenizer.tokenize("中文"))
    }

    @Test fun controlFormatAndPrivateUseCharactersAreRemoved() {
        assertEquals(listOf("hello"), tokenizer.tokenize("hel‍lo­\u0000"))
    }

    @Test fun unicodeWhitespaceSeparatesWords() {
        assertEquals(listOf("hello", "world", "hello", "world"), tokenizer.tokenize("hello world　hello world"))
    }

    @Test fun emojiStayWithinWordsLikeLetters() {
        assertEquals(listOf("hello", "😀"), tokenizer.tokenize("hello 😀"))
    }

    @Test fun punctuationFollowsTheReferenceTablesNotThePlatform() {
        assertEquals(listOf("a", "[UNK]", "a"), tokenizer.tokenize("a—a"), "an em dash is punctuation")
        assertEquals(listOf("[UNK]"), tokenizer.tokenize("a⹝a"), "U+2E5D (Unicode 14) is not punctuation in the reference")
    }

    @Test fun wordsLongerThanOneHundredCodePointsAreUnknown() {
        assertEquals(listOf("a") + List(99) { "##a" }, tokenizer.tokenize("a".repeat(100)))
        assertEquals(listOf("[UNK]"), tokenizer.tokenize("a".repeat(101)))
    }

    @Test fun specialTokensMatchTheRawTextCaseSensitively() {
        assertEquals(listOf("hello", "[SEP]", "world"), tokenizer.tokenize("hello[SEP]world"))
        assertEquals(listOf("[", "sep", "]"), tokenizer.tokenize("[sep]"))
    }

    @Test fun unpairedSurrogatesAreRemoved() {
        assertEquals(listOf("hello"), tokenizer.tokenize("hel\uD800lo"))
    }

    @Test fun encodeAddsTheFrameAndTruncatesToTheModelLimit() {
        val encoding = tokenizer.encode("hello world hello", maxLength = 4)
        assertEquals(listOf("[CLS]", "hello", "world", "[SEP]"), encoding.tokens)
        assertContentEquals(intArrayOf(2, 5, 6, 3), encoding.ids)
        assertEquals(listOf("[CLS]", "[SEP]"), tokenizer.encode("").tokens)
        assertFailsWith<IllegalArgumentException> { tokenizer.encode("hello", maxLength = 1) }
    }

    @Test fun theVocabularyMustContainTheSpecialTokens() {
        assertFailsWith<IllegalArgumentException> { BertTokenizer.fromVocabulary("hello\nworld\n") }
    }

    @Test fun aRepeatedVocabularyLineKeepsTheLastId() {
        val repeated = BertTokenizer.fromVocabulary("[PAD]\n[UNK]\n[CLS]\n[SEP]\n[MASK]\nhello\nhello\n")
        assertEquals(6, repeated.idOf("hello"))
        assertEquals(7, repeated.vocabularySize)
    }

    @Test fun theTablesComeFromTheReferenceLibrary() {
        assertTrue(BertTables.SOURCE.startsWith("tokenizers "), BertTables.SOURCE)
    }
}
