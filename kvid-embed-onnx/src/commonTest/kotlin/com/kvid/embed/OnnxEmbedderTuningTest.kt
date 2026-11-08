package com.kvid.embed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.concurrent.atomics.AtomicLong
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Compares runtime settings of [OnnxEmbedder] on the Milestone 3 synthetic notes. Runs only when
 * KVID_EMBEDDING_TUNE names one configuration, such as `corpus=mixed,batch=8,sort=on,arena=off,pattern=on,notes=200`
 * (`+` also separates settings, for Android instrumentation arguments, which end at a comma);
 * on Android each configuration runs in its own instrumentation run, so PSS belongs to it alone. Prints
 * one `[kvid-embedding-tune]` line with time per note, padding and memory (`mem_kind=pss` on Android,
 * `footprint` on iOS) before, after open, at its peak while embedding (sampled every 100 ms) and after.
 * Nothing is asserted about time or memory. Timing and sampling run on [Dispatchers.Default].
 */
class OnnxEmbedderTuningTest {
    @Test fun measureOneConfiguration() = runTest(timeout = 60.minutes) {
        val config = testEnvironment("KVID_EMBEDDING_TUNE") ?: run {
            println("[kvid] embedding tuning skipped: set KVID_EMBEDDING_TUNE (and KVID_EMBEDDING_FIXTURE_DIR)")
            return@runTest
        }
        withContext(Dispatchers.Default) { measure(config) }
    }

    private suspend fun measure(config: String) {
        val fixture = EmbeddingFixture.loadOrSkip("embedding tuning")
            ?: fail("KVID_EMBEDDING_TUNE needs the embedding fixture (KVID_EMBEDDING_FIXTURE_DIR) for its model files")
        val settings = config.split(',', '+').filter { it.isNotBlank() }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        fun flag(name: String, default: Boolean) = settings[name]?.let { it == "on" } ?: default
        val defaults = OnnxOptions()
        val options = OnnxOptions(
            maxBatchSize = settings["batch"]?.toInt() ?: defaults.maxBatchSize,
            sortByLength = flag("sort", defaults.sortByLength),
            cpuMemoryArena = flag("arena", defaults.cpuMemoryArena),
            memoryPattern = flag("pattern", defaults.memoryPattern)
        )
        val count = settings["notes"]?.toInt() ?: 200
        val notes = when (val corpus = settings["corpus"] ?: "m3") {
            "m3" -> SyntheticNotes().take(count).toList()
            "mixed" -> mixedLengthNotes(count)
            else -> fail("unknown corpus '$corpus'; use m3 or mixed")
        }

        // Token and padded token counts for these settings, computed as BertEmbedder batches.
        val tokenizer = BertTokenizer.fromVocabulary(Path(fixture.vocabPath).readText())
        val lengths = notes.map { tokenizer.encode(it, 512).ids.size }
        val order = if (options.sortByLength) lengths.indices.sortedBy { lengths[it] } else lengths.indices.toList()
        val padded = order.chunked(options.maxBatchSize).sumOf { rows -> rows.size * rows.maxOf { lengths[it] } }

        val memBefore = memoryKb()
        val embedder = OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, BertEmbeddingModel.BGE_SMALL_EN_V1_5, options)
        val memAfterOpen = memoryKb()
        val line = embedder.use {
            val result = withContext(Dispatchers.Default) {
                // The sampler is the only writer; the result is read after it has been joined.
                val peak = AtomicLong(memAfterOpen ?: 0L)
                val sampler = launch {
                    while (isActive) {
                        memoryKb()?.let { kb -> if (kb > peak.load()) peak.store(kb) }
                        delay(100.milliseconds)
                    }
                }
                var vectors: List<FloatArray> = emptyList()
                val totalMs = try {
                    timedMs { vectors = embedder.embed(notes) }
                } finally {
                    sampler.cancelAndJoin()
                }
                Triple(vectors.size, totalMs, peak.load())
            }
            val (count, totalMs, peak) = result
            assertEquals(notes.size, count)
            val memAfter = memoryKb()
            val mem = if (memBefore == null) "mem=not-measured"
            else "mem_kind=$memoryKind mem_before_kb=$memBefore mem_after_open_kb=$memAfterOpen mem_peak_kb=${maxOf(peak, memAfter ?: 0L)} mem_after_kb=$memAfter"
            "[kvid-embedding-tune] platform=$testPlatform config=$config notes=${notes.size} tokens=${lengths.sum()} " +
                "max_tokens=${lengths.max()} padded_tokens=$padded total_ms=${fmt("%.1f", totalMs)} " +
                "per_note_ms=${fmt("%.1f", totalMs / notes.size)} $mem"
        }
        println(line)
    }
}

/**
 * Notes of English words whose lengths spread from a line to several pages (5 to 600 words, log-uniform),
 * unlike the Milestone 3 notes, which almost all reach the 512-token limit. Padding and length sorting
 * only matter when lengths differ, so this corpus shows their effect.
 */
internal fun mixedLengthNotes(count: Int): List<String> {
    val random = Random(20261024)
    val words = ("the of and to in is that for it with as was on be at by this had not are but from or have an they which " +
        "one you were her all she there would their we him been has when who will more no if out so said what up plan " +
        "budget review note meeting search store document version token embedding model vector query garden recipe " +
        "travel invoice project deadline weekend family doctor appointment library chapter summary idea draft").split(' ')
    return List(count) {
        val n = exp(random.nextDouble(ln(5.0), ln(600.0))).toInt()
        (1..n).joinToString(" ") { words[random.nextInt(words.size)] }
    }
}
