@file:OptIn(ExperimentalTime::class)

package com.kvid.embed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * Compares runtime settings of [OnnxEmbedder] on the Milestone 3 synthetic notes. Runs only when
 * KVID_EMBEDDING_TUNE names one configuration, such as `corpus=mixed,batch=8,sort=on,arena=off,pattern=on,notes=200`
 * (`+` also separates settings, for Android instrumentation arguments, which end at a comma);
 * on Android each configuration runs in its own instrumentation run, so PSS belongs to it alone. Prints
 * one `[kvid-embedding-tune]` line with time per note, padding and memory (`mem_kind=pss` on Android,
 * `footprint` on iOS) before, after open, at its peak while embedding (sampled every 100 ms) and after.
 * Nothing is asserted about time or memory. Timing and sampling run on [Dispatchers.Default].
 *
 * Settings of the mobile indexing study (planning branch, docs/plans/mobile-indexing.md):
 * - `model=<variant>`: a directory of scripts/build_variants.py under KVID_MODEL_VARIANTS_DIR (such as
 *   `bge-int8` or `minilm-fp32`) instead of the embedding fixture's model;
 * - `maxTokens=<n>`: the model's input limit instead of the variant's own;
 * - `threads=<n>`: ONNX Runtime intra-op threads (default: ONNX Runtime's choice);
 * - `ep=cpu|xnnpack`: the execution provider ([openForTuning]). XNNPACK gets the `threads` value (4 when
 *   unset) for its own thread pool, and the session one thread without spinning, as ONNX Runtime recommends
 *   for it. Only onnxruntime-android includes XNNPACK.
 *
 * - `warmup=<n>` (default 8): the first n notes are embedded once after open, outside every timing, so the
 *   timed work starts warm on representative shapes rather than on open's two-token warm-up;
 * - `latency=on`: each note is embedded alone (a new or edited note), and the line adds p50 and p95 call
 *   latency, overall and by token length (up to 64, 128, 256 and above).
 *
 * Memory ([PhaseSampler], `mem_peak=sampled-100ms+readings-from-before-open`) is sampled every 100 ms and
 * read explicitly before the open, after it, after embedding, after close and after 5 s idle, all in the
 * same peaks:
 * - `mem_open_peak_kb` covers the reading before the open, the open and the warm-up; then
 *   `mem_embed_peak_kb`, `mem_close_peak_kb` and `mem_idle_peak_kb` (the 5 s after close);
 * - `mem_peak_kb` is the embedding phase's peak, including the reading after embedding, as before;
 * - `mem_lifecycle_peak_kb` is the highest reading of all, so it is at least every other memory value on
 *   the line.
 *
 * A sample during which the phase changed counts for the lifecycle only. Sampled peaks are lower bounds
 * that can miss short peaks. `open_epoch_ms` is the wall-clock time at the open, and `phase_start_s` the
 * phases' starts in seconds from it, for lining the line up with a driver's thermal readings.
 *
 * An unknown setting, a flag other than `on`/`off` or a count that is not positive fails the test, so a
 * misspelt option cannot produce a plausible default measurement. The line repeats the resolved settings
 * and the model's digest. A variant with a `max_batch_size` (the dynamically quantized ones) defaults to it;
 * a larger `batch` is measured but marked `batch_invariant=false`, since its vectors then depend on the batch.
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
        val settings = parseSettings(config, "KVID_EMBEDDING_TUNE", TUNING_SETTINGS)
        fun flag(name: String, default: Boolean) = settings[name]?.let { it == "on" } ?: default
        fun count(name: String) = settings[name]?.toInt()
        val variant = settings["model"]
        val fixture = if (variant != null) EmbeddingFixture.variant(variant) else EmbeddingFixture.loadOrSkip("embedding tuning")
            ?: fail("KVID_EMBEDDING_TUNE needs the embedding fixture (KVID_EMBEDDING_FIXTURE_DIR) for its model files")
        val model = count("maxTokens")?.let { fixture.model(it) } ?: fixture.model
        val threads = count("threads")
        val ep = settings["ep"] ?: "cpu"
        val defaults = OnnxOptions()
        val options = OnnxOptions(
            intraOpThreads = if (ep == "xnnpack") 1 else threads,
            maxBatchSize = count("batch") ?: fixture.maxBatchSize ?: defaults.maxBatchSize,
            sortByLength = flag("sort", defaults.sortByLength),
            cpuMemoryArena = flag("arena", defaults.cpuMemoryArena),
            memoryPattern = flag("pattern", defaults.memoryPattern)
        )
        val count = count("notes") ?: 200
        val notes = when (val corpus = settings["corpus"] ?: "m3") {
            "m3" -> SyntheticNotes().take(count).toList()
            "mixed" -> mixedLengthNotes(count)
            else -> fail("unknown corpus '$corpus'; use m3 or mixed")
        }

        // Token and padded token counts for these settings, computed as BertEmbedder batches.
        val tokenizer = BertTokenizer.fromVocabulary(Path(fixture.vocabPath).readText())
        val lengths = notes.map { tokenizer.encode(it, model.maxInputTokens).ids.size }
        val order = if (options.sortByLength) lengths.indices.sortedBy { lengths[it] } else lengths.indices.toList()
        val padded = order.chunked(options.maxBatchSize).sumOf { rows -> rows.size * rows.maxOf { lengths[it] } }

        val warmup = settings["warmup"]?.toInt() ?: 8
        val latency = flag("latency", false)
        val sampler = PhaseSampler()
        val callMs = ArrayList<Double>()
        val clock = TimeSource.Monotonic.markNow()
        val openEpochMs = Clock.System.now().toEpochMilliseconds()
        fun now() = clock.elapsedNow().inWholeMicroseconds / 1_000_000.0
        val marks = linkedMapOf("open" to 0.0)  // the origin of the clock above, exactly
        // Reading before the open: OPEN runs from here until open and warm-up are done.
        val memBefore = sampler.reading()
        var memAfterOpen: Long? = null
        var memAfter: Long? = null
        var memAfterClose: Long? = null
        var memIdle: Long? = null
        var openMs = 0.0
        var totalMs = 0.0
        var digest = ""
        coroutineScope {
            val sampling = launch { sampler.run() }
            try {
                var opened: OnnxEmbedder? = null
                openMs = timedMs { opened = openForTuning(fixture.modelPath, fixture.vocabPath, model, options, ep, providerThreads = threads ?: 4) }
                val embedder = opened!!
                digest = embedder.spec.modelDigest
                embedder.use {
                    memAfterOpen = sampler.reading()
                    if (warmup > 0) embedder.embed(notes.take(warmup))
                    sampler.phase = Phase.EMBED
                    marks["embed"] = now()
                    var vectors: List<FloatArray> = emptyList()
                    totalMs = if (latency) {
                        val each = ArrayList<FloatArray>(notes.size)
                        for (note in notes) callMs += timedMs { each += embedder.embed(listOf(note)) }
                        vectors = each
                        callMs.sum()
                    } else {
                        timedMs { vectors = embedder.embed(notes) }
                    }
                    assertEquals(notes.size, vectors.size)
                    memAfter = sampler.reading()
                    sampler.phase = Phase.CLOSE
                    marks["close"] = now()
                }
                memAfterClose = sampler.reading()
                // After the process has been idle for a while: what an app keeps once indexing ends.
                sampler.phase = Phase.IDLE
                marks["idle"] = now()
                delay(5.seconds)
                memIdle = sampler.reading()
                marks["end"] = now()
            } finally {
                sampling.cancelAndJoin()
            }
        }
        // Built only after the sampler has stopped, so every reading is in the summary.
        val mem = if (memBefore == null) "mem=not-measured"
        else "mem_kind=$memoryKind mem_peak=$MEMORY_METHOD mem_before_kb=$memBefore mem_after_open_kb=$memAfterOpen " +
            "mem_peak_kb=${sampler.peak(Phase.EMBED)} mem_after_kb=$memAfter mem_after_close_kb=$memAfterClose mem_idle_5s_kb=$memIdle " +
            sampler.summary(TUNING_PHASES)
        val latencies = if (!latency) "" else " " + latencySummary(callMs, lengths)
        val resolved = "model=${model.name} variant=${variant ?: "fixture"} max_input_tokens=${model.maxInputTokens} " +
            "batch=${options.maxBatchSize} sort=${onOff(options.sortByLength)} arena=${onOff(options.cpuMemoryArena)} " +
            "pattern=${onOff(options.memoryPattern)} threads=${options.intraOpThreads ?: "default"} ep=$ep" +
            (if (ep == "xnnpack") " ep_threads=${threads ?: 4}" else "")
        println(
            "[kvid-embedding-tune] platform=$testPlatform config=$config $resolved " +
                "batch_invariant=${fixture.maxBatchSize.let { it == null || options.maxBatchSize <= it }} " +
                "model_digest=$digest model_bytes=${SystemFileSystem.metadataOrNull(Path(fixture.modelPath))?.size} " +
                "open_epoch_ms=$openEpochMs open_ms=${fmt("%.1f", openMs)} notes=${notes.size} tokens=${lengths.sum()} " +
                "max_tokens=${lengths.max()} padded_tokens=$padded total_ms=${fmt("%.1f", totalMs)} " +
                "warmup=$warmup latency=${onOff(latency)} " +
                "per_note_ms=${fmt("%.1f", totalMs / notes.size)} tokens_per_s=${fmt("%.0f", lengths.sum() / (totalMs / 1000))}$latencies " +
                "phase_start_s=${phaseStarts(marks)} $mem"
        )
    }
}

/** The tuning test's phases, in the order they run. */
private val TUNING_PHASES = listOf(Phase.OPEN, Phase.EMBED, Phase.CLOSE, Phase.IDLE)

/** `name:seconds` for each mark, comma separated, as `phase_start_s` prints them. */
internal fun phaseStarts(marks: Map<String, Double>): String = marks.entries.joinToString(",") { "${it.key}:${fmt("%.1f", it.value)}" }

private fun onOff(value: Boolean) = if (value) "on" else "off"

/** The [p] quantile of [values] by the nearest-rank method. */
internal fun percentile(values: List<Double>, p: Double): Double {
    val sorted = values.sorted()
    return sorted[minOf(sorted.size - 1, kotlin.math.ceil(p * sorted.size).toInt() - 1).coerceAtLeast(0)]
}

/** `<prefix>p50_ms` and `<prefix>p95_ms` of [values]. */
internal fun percentiles(values: List<Double>, prefix: String): String =
    "${prefix}p50_ms=${fmt("%.1f", percentile(values, 0.5))} ${prefix}p95_ms=${fmt("%.1f", percentile(values, 0.95))}"

/**
 * p50 and p95 of single-note calls, overall and by token length (`p95_le128_ms` covers 65 to 128 tokens),
 * each key starting with [prefix].
 */
internal fun latencySummary(callMs: List<Double>, tokens: List<Int>, prefix: String = ""): String {
    val parts = mutableListOf(percentiles(callMs, prefix))
    var lower = 0
    for ((upper, name) in listOf(64 to "le64", 128 to "le128", 256 to "le256", Int.MAX_VALUE to "gt256")) {
        val bucket = callMs.indices.filter { tokens[it] in (lower + 1)..upper }.map { callMs[it] }
        if (bucket.isNotEmpty()) parts += "${prefix}n_$name=${bucket.size} " + percentiles(bucket, "${prefix}${name}_")
        lower = upper
    }
    return parts.joinToString(" ")
}

private val FLAGS = setOf("sort", "arena", "pattern", "latency")
private val COUNTS = setOf("notes", "batch", "maxTokens", "threads", "outer", "chunk")
private val NON_NEGATIVE = setOf("warmup", "edits", "queries", "pause")
private val CHOICES = mapOf("corpus" to setOf("m3", "mixed"), "ep" to setOf("cpu", "xnnpack"))

/** The settings OnnxEmbedderTuningTest accepts. */
internal val TUNING_SETTINGS = setOf("corpus", "notes", "model", "maxTokens", "batch", "threads", "ep", "sort", "arena", "pattern", "warmup", "latency")

/**
 * [config] (from [variable]) as settings, `,` or `+` between them. Fails on a key outside [allowed], a
 * repeated key, a flag other than `on`/`off`, a count that is not a positive integer, or a value outside a
 * setting's choices. `model` names a variant directory and is checked when it is loaded.
 */
internal fun parseSettings(config: String, variable: String, allowed: Set<String>): Map<String, String> {
    val settings = LinkedHashMap<String, String>()
    for (item in config.split(',', '+').map { it.trim() }.filter { it.isNotEmpty() }) {
        val key = item.substringBefore('=').trim()
        val value = item.substringAfter('=', "").trim()
        if (key !in allowed) fail("unknown $variable setting '$key'; known: ${allowed.sorted()}")
        if (settings.put(key, value) != null) fail("$variable sets '$key' twice")
        when {
            key in FLAGS && value != "on" && value != "off" -> fail("$key must be on or off, not '$value'")
            key in COUNTS && (value.toIntOrNull() ?: 0) <= 0 -> fail("$key must be a positive integer, not '$value'")
            key in NON_NEGATIVE && (value.toIntOrNull() ?: -1) < 0 -> fail("$key must be zero or a positive integer, not '$value'")
            key in CHOICES && value !in CHOICES.getValue(key) -> fail("$key must be one of ${CHOICES.getValue(key)}, not '$value'")
            key == "model" && value.isEmpty() -> fail("model needs a variant name")
        }
    }
    return settings
}

/**
 * Notes of English words whose lengths spread from a line to several pages (5 to 600 words, log-uniform),
 * unlike the Milestone 3 notes, which almost all reach the 512-token limit. Padding and length sorting
 * only matter when lengths differ, so this corpus shows their effect.
 */
internal fun mixedLengthNotes(count: Int, seed: Int = 20261024): List<String> {
    val random = Random(seed)
    return List(count) {
        val n = exp(random.nextDouble(ln(5.0), ln(600.0))).toInt()
        (1..n).joinToString(" ") { NOTE_WORDS[random.nextInt(NOTE_WORDS.size)] }
    }
}

/** The vocabulary of [mixedLengthNotes], also used for the store measurement's queries. */
internal val NOTE_WORDS = ("the of and to in is that for it with as was on be at by this had not are but from or have an they which " +
    "one you were her all she there would their we him been has when who will more no if out so said what up plan " +
    "budget review note meeting search store document version token embedding model vector query garden recipe " +
    "travel invoice project deadline weekend family doctor appointment library chapter summary idea draft").split(' ')
