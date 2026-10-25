package com.kvid.embed

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Compares every case of a fixture written by scripts/make_tokenizer_fixture.py (Hugging Face
 * `tokenizers` encodings of curated and seeded random text) with [BertTokenizer]. CI requires the
 * fixture; locally the test is skipped with a message when KVID_TOKENIZER_FIXTURE_DIR is unset.
 */
class BertTokenizerParityTest {
    private fun read(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

    @Test fun matchesHuggingFaceTokenizersOnTheReferenceFixture() {
        val dir = testEnvironment("KVID_TOKENIZER_FIXTURE_DIR")
        val required = testEnvironment("KVID_REQUIRE_TOKENIZER_FIXTURE") == "true"
        if (dir == null || !SystemFileSystem.exists(Path(dir, "cases.jsonl"))) {
            if (required) fail("KVID_REQUIRE_TOKENIZER_FIXTURE=true but there is no fixture at '$dir'")
            println("[kvid] tokenizer parity skipped: set KVID_TOKENIZER_FIXTURE_DIR to a directory written by scripts/make_tokenizer_fixture.py")
            return
        }
        val tokenizer = BertTokenizer.fromVocabulary(read(Path(dir, "vocab.txt")))
        val mismatches = ArrayList<String>()
        var cases = 0
        for (line in read(Path(dir, "cases.jsonl")).lineSequence()) {
            if (line.isBlank()) continue
            val case = Json.parseToJsonElement(line).jsonObject
            val text = case.getValue("text").jsonPrimitive.content
            val expected = case.getValue("ids").jsonArray.map { it.jsonPrimitive.int }
            val actual = tokenizer.encode(text).ids.toList()
            cases++
            if (actual != expected) mismatches += describe(tokenizer, text, expected, actual)
        }
        val source = runCatching { read(Path(dir, "source.json")).replace(Regex("\\s+"), " ") }.getOrDefault("?")
        println("[kvid-tokenizer] cases=$cases mismatches=${mismatches.size} tables=${BertTables.SOURCE} fixture=$source")
        assertTrue(cases > 0, "the fixture has no cases")
        assertTrue(mismatches.isEmpty(), "${mismatches.size} of $cases cases differ; first ones:\n" + mismatches.take(10).joinToString("\n"))
    }

    private fun describe(tokenizer: BertTokenizer, text: String, expected: List<Int>, actual: List<Int>): String {
        val at = expected.indices.firstOrNull { it >= actual.size || expected[it] != actual[it] } ?: expected.size
        fun window(ids: List<Int>) = ids.subList(maxOf(0, at - 3), minOf(ids.size, at + 4)).map { tokenizer.tokenOf(it) }
        return "  text=${escape(text.take(120))} at=$at expected=${window(expected)} actual=${window(actual)}"
    }

    private fun escape(text: String): String = buildString {
        append('"')
        for (c in text) if (c.code in 0x20..0x7E) append(c) else append("\\u").append(c.code.toString(16).padStart(4, '0'))
        append('"')
    }
}
