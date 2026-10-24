package com.kvid.embed

import kotlinx.coroutines.runBlocking
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Milestone 5 measurements of [OnnxEmbedder] on the Milestone 3 synthetic notes. Runs only with
 * KVID_EMBEDDING_MEASURE=true and the embedding fixture (for its model files), and prints one
 * `[kvid-embedding-measure]` line; nothing here asserts a time budget. On Android it runs in its own
 * instrumentation run so earlier tests do not inflate PSS. Durations are milliseconds with one decimal.
 */
class OnnxEmbedderMeasurementTest {
    @Test fun measureOnnxEmbedder() = runBlocking {
        if (testEnvironment("KVID_EMBEDDING_MEASURE") != "true") {
            println("[kvid] embedding measurement skipped: set KVID_EMBEDDING_MEASURE=true (and KVID_EMBEDDING_FIXTURE_DIR)")
            return@runBlocking
        }
        val fixture = EmbeddingFixture.loadOrSkip("embedding measurement")
            ?: fail("KVID_EMBEDDING_MEASURE=true needs the embedding fixture (KVID_EMBEDDING_FIXTURE_DIR) for its model files")
        val notes = SyntheticNotes().take(500).toList()

        val pssBefore = pssKb()
        var timings: OnnxEmbedder.OpenTimings? = null
        val openStart = System.nanoTime()
        val embedder = OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, BertEmbeddingModel.BGE_SMALL_EN_V1_5, OnnxOptions(), timings = { timings = it })
        val openMs = (System.nanoTime() - openStart) / 1e6
        val pssAfterOpen = pssKb()
        val phases = timings!!

        val line = embedder.use {
            val batch1 = notes.take(20).map { note -> timedMs { embedder.embed(listOf(note)) } }
            val call16 = notes.drop(20).take(16 * 5).chunked(16).map { batch -> timedMs { embedder.embed(batch) } / batch.size }
            var vectors = 0
            val notes500Ms = timedMs { vectors = embedder.embed(notes).size }
            assertEquals(notes.size, vectors)
            val pssAfterEmbed = pssKb()
            val pss = if (pssBefore == null) "pss=not-measured"
            else "pss_before_kb=$pssBefore pss_after_open_kb=$pssAfterOpen pss_after_embed_kb=$pssAfterEmbed"
            "[kvid-embedding-measure] platform=$testPlatform open_ms=${ms(openMs)} digest_ms=${ms(phases.digestMs)} " +
                "session_ms=${ms(phases.sessionMs)} warmup_ms=${ms(phases.warmupMs)} batch1_median_ms=${ms(batch1.median())} " +
                "call16_per_note_median_ms=${ms(call16.median())} notes500_ms=${ms(notes500Ms)} " +
                "notes5100_extrapolated_ms=${ms(notes500Ms * 5100 / notes.size)} $pss"
        }
        println(line)
    }

    private inline fun timedMs(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1e6
    }

    private fun ms(value: Double): String = fmt("%.1f", value)
    private fun List<Double>.median(): Double = sorted()[size / 2]
}

/**
 * The note texts of kvid-core's SearchMeasurementTest (kvid-core/src/commonTest/.../SearchMeasurementTest.kt,
 * not visible from this module): same seed, syllables, vocabulary and lengths, with the random draws
 * for tags kept so the sequence matches. Each text is what the store embeds, the title, a blank line
 * and the body.
 */
internal class SyntheticNotes : Sequence<String> {
    private val random = Random(20261004)
    private val syllables = listOf("ka", "to", "ri", "mo", "sen", "dal", "ve", "lu", "pra", "ni", "sho", "ter", "ba", "ex", "on", "mi", "ul", "gra", "fo", "qui")
    private val vocabulary: List<String> = (0 until 3000).map { i ->
        buildString { repeat(2 + i % 3) { append(syllables[random.nextInt(syllables.size)]) }; append(i) }
    }
    private val tagCount = 20

    private fun word(): String = vocabulary[(random.nextDouble().pow(2.5) * vocabulary.size).toInt().coerceIn(0, vocabulary.size - 1)]
    private fun words(n: Int): String = (1..n).joinToString(" ") { word() }

    override fun iterator(): Iterator<String> = iterator {
        while (true) {
            val body = words(40 + random.nextInt(260))
            val title = words(2 + random.nextInt(5))
            random.nextInt(tagCount)
            yield("$title\n\n$body")
        }
    }
}
