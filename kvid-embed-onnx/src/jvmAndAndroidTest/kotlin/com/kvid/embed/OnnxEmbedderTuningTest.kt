package com.kvid.embed

import ai.onnxruntime.OrtSession
import kotlinx.coroutines.runBlocking
import java.io.File
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
 *
 * Settings of the mobile indexing study (planning branch, docs/plans/mobile-indexing.md):
 * - `model=<variant>`: a directory of scripts/build_variants.py under KVID_MODEL_VARIANTS_DIR (such as
 *   `bge-int8` or `minilm-fp32`) instead of the embedding fixture's model;
 * - `maxTokens=<n>`: the model's input limit instead of the variant's own;
 * - `threads=<n>`: ONNX Runtime intra-op threads (default: ONNX Runtime's choice);
 * - `ep=cpu|xnnpack`: the execution provider. XNNPACK gets the `threads` value (4 when unset) for its own
 *   thread pool, and the session itself one thread without spinning, as ONNX Runtime recommends for it.
 *   Only onnxruntime-android includes XNNPACK; the JVM's ONNX Runtime reports it unsupported.
 *
 * A variant with a `max_batch_size` (the dynamically quantized ones) defaults to that batch size; a larger
 * `batch` is measured but marked `batch_invariant=false`, since its vectors then depend on the batch.
 */
class OnnxEmbedderTuningTest {
    @Test fun measureOneConfiguration() = runBlocking {
        val config = testEnvironment("KVID_EMBEDDING_TUNE") ?: run {
            println("[kvid] embedding tuning skipped: set KVID_EMBEDDING_TUNE (and KVID_EMBEDDING_FIXTURE_DIR)")
            return@runBlocking
        }
        val settings = config.split(',', '+').filter { it.isNotBlank() }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val variant = settings["model"]
        val fixture = if (variant != null) EmbeddingFixture.variant(variant) else EmbeddingFixture.loadOrSkip("embedding tuning")
            ?: fail("KVID_EMBEDDING_TUNE needs the embedding fixture (KVID_EMBEDDING_FIXTURE_DIR) for its model files")
        val model = settings["maxTokens"]?.toInt()?.let { fixture.model(it) } ?: fixture.model
        fun flag(name: String, default: Boolean) = settings[name]?.let { it == "on" } ?: default
        val threads = settings["threads"]?.toInt()
        val ep = settings["ep"] ?: "cpu"
        val configureSession: ((OrtSession.SessionOptions) -> Unit)? = when (ep) {
            "cpu" -> null
            "xnnpack" -> { session ->
                session.addConfigEntry("session.intra_op.allow_spinning", "0")
                session.addXnnpack(mapOf("intra_op_num_threads" to (threads ?: 4).toString()))
            }
            else -> fail("unknown ep '$ep'; use cpu or xnnpack")
        }
        val defaults = OnnxOptions()
        val options = OnnxOptions(
            intraOpThreads = if (ep == "xnnpack") 1 else threads,
            maxBatchSize = settings["batch"]?.toInt() ?: fixture.maxBatchSize ?: defaults.maxBatchSize,
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
        val lengths = notes.map { tokenizer.encode(it, model.maxInputTokens).ids.size }
        val order = if (options.sortByLength) lengths.indices.sortedBy { lengths[it] } else lengths.indices.toList()
        val padded = order.chunked(options.maxBatchSize).sumOf { rows -> rows.size * rows.maxOf { lengths[it] } }

        val pssBefore = pssKb()
        val openStart = System.nanoTime()
        val embedder = OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath, model, options, timings = null, configureSession = configureSession)
        val openMs = (System.nanoTime() - openStart) / 1e6
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
            "[kvid-embedding-tune] platform=$testPlatform config=$config model=${model.name} variant=${variant ?: "fixture"} " +
                "batch=${options.maxBatchSize} batch_invariant=${fixture.maxBatchSize.let { it == null || options.maxBatchSize <= it }} " +
                "model_bytes=${File(fixture.modelPath).length()} max_input_tokens=${model.maxInputTokens} threads=${threads ?: "default"} ep=$ep " +
                "open_ms=${fmt("%.1f", openMs)} notes=${notes.size} tokens=${lengths.sum()} " +
                "max_tokens=${lengths.max()} padded_tokens=$padded total_ms=${fmt("%.1f", totalMs)} " +
                "per_note_ms=${fmt("%.1f", totalMs / notes.size)} tokens_per_s=${fmt("%.0f", lengths.sum() / (totalMs / 1000))} $pss"
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
