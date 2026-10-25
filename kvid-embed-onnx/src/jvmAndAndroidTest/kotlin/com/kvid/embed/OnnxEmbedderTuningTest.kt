package com.kvid.embed

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Compares runtime settings of [OnnxEmbedder] on the Milestone 3 synthetic notes. Runs only when
 * KVID_EMBEDDING_TUNE names one configuration, such as `corpus=mixed,batch=8,sort=on,arena=off,pattern=on,notes=200`
 * (`+` also separates settings, for Android instrumentation arguments, which end at a comma);
 * on Android each configuration runs in its own instrumentation run, so PSS belongs to it alone. Prints
 * one `[kvid-embedding-tune]` line with time per note, padding and PSS before, after open, at its peak
 * while embedding (sampled every 100 ms) and after. Nothing is asserted about time or memory.
 */
class OnnxEmbedderTuningTest {
    @Test fun measureOneConfiguration() = runBlocking {
        val config = testEnvironment("KVID_EMBEDDING_TUNE") ?: run {
            println("[kvid] embedding tuning skipped: set KVID_EMBEDDING_TUNE (and KVID_EMBEDDING_FIXTURE_DIR)")
            return@runBlocking
        }
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
        val tokenizer = BertTokenizer.fromVocabulary(java.io.File(fixture.vocabPath).readText())
        val lengths = notes.map { tokenizer.encode(it, 512).ids.size }
        val order = if (options.sortByLength) lengths.indices.sortedBy { lengths[it] } else lengths.indices.toList()
        val padded = order.chunked(options.maxBatchSize).sumOf { rows -> rows.size * rows.maxOf { lengths[it] } }

        val pssBefore = pssKb()
        val embedder = OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, BertEmbeddingModel.BGE_SMALL_EN_V1_5, options)
        val pssAfterOpen = pssKb()
        val peak = AtomicLong(pssAfterOpen ?: 0L)
        val sampling = AtomicBoolean(true)
        val sampler = thread(isDaemon = true) {
            while (sampling.get()) {
                pssKb()?.let { kb -> peak.accumulateAndGet(kb, ::maxOf) }
                Thread.sleep(100)
            }
        }
        val line = embedder.use {
            val start = System.nanoTime()
            val vectors = embedder.embed(notes)
            val totalMs = (System.nanoTime() - start) / 1e6
            sampling.set(false)
            sampler.join()
            assertEquals(notes.size, vectors.size)
            val pssAfter = pssKb()
            val pss = if (pssBefore == null) "pss=not-measured"
            else "pss_before_kb=$pssBefore pss_after_open_kb=$pssAfterOpen pss_peak_kb=${maxOf(peak.get(), pssAfter ?: 0L)} pss_after_kb=$pssAfter"
            "[kvid-embedding-tune] platform=$testPlatform config=$config notes=${notes.size} tokens=${lengths.sum()} " +
                "max_tokens=${lengths.max()} padded_tokens=$padded total_ms=${fmt("%.1f", totalMs)} " +
                "per_note_ms=${fmt("%.1f", totalMs / notes.size)} $pss"
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
    val random = kotlin.random.Random(20261024)
    val words = ("the of and to in is that for it with as was on be at by this had not are but from or have an they which " +
        "one you were her all she there would their we him been has when who will more no if out so said what up plan " +
        "budget review note meeting search store document version token embedding model vector query garden recipe " +
        "travel invoice project deadline weekend family doctor appointment library chapter summary idea draft").split(' ')
    return List(count) {
        val n = kotlin.math.exp(random.nextDouble(kotlin.math.ln(5.0), kotlin.math.ln(600.0))).toInt()
        (1..n).joinToString(" ") { words[random.nextInt(words.size)] }
    }
}
