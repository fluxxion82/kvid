@file:OptIn(ExperimentalTime::class)

package com.kvid.embed

import com.kvid.store.DocumentId
import com.kvid.store.FindOptions
import com.kvid.store.Kvid
import com.kvid.store.MatchMode
import com.kvid.store.VectorIndexReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
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
 * Store-level indexing for the mobile indexing study (planning branch, docs/plans/mobile-indexing.md, as
 * Codex's review asks): how long `Kvid.indexVectors` takes to embed a whole store at the store's own call
 * boundaries, instead of an inference microbenchmark multiplied out. Runs only when KVID_INDEX_MEASURE names
 * one configuration, such as `corpus=mixed,notes=5000,model=bge-int8,chunk=500` (`+` also separates settings):
 * - `corpus`, `notes`, `model`, `maxTokens`, `batch`, `threads`, `ep`, `sort`, `arena`, `pattern`: as in
 *   OnnxEmbedderTuningTest (default: 5,000 `mixed` notes with the embedding fixture's model);
 * - `outer=<n>` (default 32, the store's default): documents per embed call;
 * - `chunk=<n>` (default notes / 10): documents per `indexVectors` call. Each call is timed, which gives
 *   progress and early against late throughput (the first and the last fifth of the documents).
 *
 * The notes are inserted first, timed separately. The indexing total is the time to open the embedder,
 * store every vector (pending = 0) and close it, as an app's background job would.
 *
 * After indexing, with the same open embedder (the targets in answer 5 of Codex's review of the study
 * plan), in this order:
 * - `pause=<s>` (default 0) waits, so the phone can cool after indexing;
 * - `queries=<n>` (default 0): n queries of 2 to 6 words from the notes' vocabulary, against exactly the
 *   indexed notes (they run before any edit; `query_store_notes` prints the live count). It reports p50 and
 *   p95 of the query embedding alone (`query_embed_`), lexical `find` (`query_lexical_`), `findSimilar`
 *   (`query_similar_`) and `findHybrid` (`query_hybrid_`), with the default 20 results and lexical
 *   matching on any word, as in the quality study (random words rarely all occur in one note). The four
 *   calls of a query run in the orders of a 4×4 Williams square, one row per query in turn
 *   (`query_order=williams4`), so each call is in each position, and follows each other call, equally often
 *   over every 4 counted queries; 5 warm-up queries are not counted. These are continuous warm measurements
 *   on a synthetic vocabulary, not representative search latency;
 * - `edits=<n>` (default 0): n persisted edits, alternately a new note (`put`) and new text for an
 *   existing one (`update`), each followed by `indexVectors` for that note alone. It reports p50 and p95 of
 *   the write, of embedding and saving the vector (`edit_save_`), and of both together (`edit_total_`),
 *   overall and by token length. The texts are `mixed`-length notes from a separate seed; 5 warm-up edits
 *   are not counted.
 *
 * `open_epoch_ms` is the wall-clock time at the open, and `phase_start_s` each phase's start in seconds from
 * it, for lining the line up with a driver's thermal readings. After the result line, one
 * `[kvid-index-samples]` line per series gives every counted call's value (`edit_total_ms`, `edit_save_ms`,
 * `edit_write_ms`, `edit_tokens`, `edit_kind`, and `query_embed_ms` to `query_hybrid_ms`), with the same
 * `open_epoch_ms`, so repeated runs can be pooled.
 *
 * Memory ([PhaseSampler], `mem_peak=sampled-100ms+readings-from-before-open`) is sampled every 100 ms and read
 * explicitly before the open and at the end of every phase, all in the same peaks. `mem_peak_kb` is the
 * indexing phase's peak, including the reading after indexing, as before. Every phase prints its peak
 * (`not-run` for a phase with a zero setting), and `mem_lifecycle_peak_kb` is the highest reading of all, so it
 * is at least every other memory value on the line. A sample during which the phase changed counts for the
 * lifecycle only. Sampled peaks are lower bounds. Prints one `[kvid-index-measure]` line; nothing is asserted
 * about time or memory.
 */
class IndexingMeasurementTest {
    @Test fun measureStoreIndexing() = runTest(timeout = 240.minutes) {
        val config = testEnvironment("KVID_INDEX_MEASURE") ?: run {
            println("[kvid] store indexing measurement skipped: set KVID_INDEX_MEASURE (and KVID_EMBEDDING_FIXTURE_DIR or KVID_MODEL_VARIANTS_DIR)")
            return@runTest
        }
        withContext(Dispatchers.Default) { measure(config) }
    }

    private suspend fun measure(config: String) {
        val settings = parseSettings(config, "KVID_INDEX_MEASURE", INDEX_SETTINGS)
        fun count(name: String) = settings[name]?.toInt()
        fun flag(name: String, default: Boolean) = settings[name]?.let { it == "on" } ?: default
        val variant = settings["model"]
        val fixture = if (variant != null) EmbeddingFixture.variant(variant) else EmbeddingFixture.loadOrSkip("store indexing measurement")
            ?: fail("KVID_INDEX_MEASURE needs the embedding fixture (KVID_EMBEDDING_FIXTURE_DIR) or a model variant")
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
        val total = count("notes") ?: 5000
        val outer = count("outer") ?: 32
        val chunk = count("chunk") ?: maxOf(1, total / 10)
        val edits = settings["edits"]?.toInt() ?: 0
        val queries = settings["queries"]?.toInt() ?: 0
        val pause = settings["pause"]?.toInt() ?: 0
        val notes = when (val corpus = settings["corpus"] ?: "mixed") {
            "m3" -> SyntheticNotes().take(total).toList()
            "mixed" -> mixedLengthNotes(total)
            else -> fail("unknown corpus '$corpus'; use m3 or mixed")
        }
        val tokenizer = BertTokenizer.fromVocabulary(Path(fixture.vocabPath).readText())
        val tokens = notes.sumOf { tokenizer.encode(it, model.maxInputTokens).ids.size.toLong() }

        val dir = createTempDirectory("kvid-index")
        try {
            val path = Path(dir, "notes.kvid").toString()
            val store = Kvid.create(path)
            try {
                val ids = ArrayList<DocumentId>(notes.size)
                val insertMs = timedMs { store.transaction { notes.forEach { ids += put(it) } } }
                val sampler = PhaseSampler()
                val clock = TimeSource.Monotonic.markNow()
                val openEpochMs = Clock.System.now().toEpochMilliseconds()
                fun now() = clock.elapsedNow().inWholeMicroseconds / 1_000_000.0
                val marks = linkedMapOf("open" to now())
                // Reading before the open: OPEN runs from here until the model is open.
                val memBefore = sampler.reading()
                val calls = ArrayList<Pair<Int, Double>>()  // documents embedded and milliseconds, per indexVectors call
                var memAfterOpen: Long? = null
                var memAfterIndex: Long? = null
                var memAfterClose: Long? = null
                var queryPhase: PhaseResult? = null
                var editPhase: PhaseResult? = null
                var openMs = 0.0
                var indexMs = 0.0
                var closeMs = 0.0
                var digest = ""
                coroutineScope {
                    val sampling = launch { sampler.run() }
                    try {
                        var embedder: OnnxEmbedder? = null
                        openMs = timedMs { embedder = openForTuning(fixture.modelPath, fixture.vocabPath, model, options, ep, providerThreads = threads ?: 4) }
                        val opened = embedder!!
                        digest = opened.spec.modelDigest
                        try {
                            memAfterOpen = sampler.reading()
                            sampler.phase = Phase.INDEX
                            marks["index"] = now()
                            indexMs = timedMs {
                                while (true) {
                                    var report: VectorIndexReport? = null
                                    val ms = timedMs { report = store.indexVectors(opened, batchSize = outer, maxDocuments = chunk) }
                                    calls += report!!.embedded to ms
                                    if (report!!.pending == 0L || report!!.embedded == 0) break
                                }
                            }
                            memAfterIndex = sampler.reading()
                            val status = store.vectorStatus()
                            assertEquals(0L, status.pending, "every document is embedded")
                            assertEquals(notes.size.toLong(), status.embedded)
                            if (pause > 0) {
                                sampler.phase = Phase.PAUSE
                                marks["pause"] = now()
                                delay(pause.seconds)
                                sampler.reading()
                            }
                            if (queries > 0) {
                                sampler.phase = Phase.QUERIES
                                marks["queries"] = now()
                                queryPhase = measureQueries(store, opened, queries)
                                sampler.reading()
                            }
                            if (edits > 0) {
                                sampler.phase = Phase.EDITS
                                marks["edits"] = now()
                                editPhase = measureEdits(store, opened, ids, tokenizer, model, outer, edits)
                                sampler.reading()
                            }
                        } finally {
                            sampler.phase = Phase.CLOSE
                            marks["close"] = now()
                            closeMs = timedMs { opened.close() }
                        }
                        memAfterClose = sampler.reading()
                        marks["end"] = now()
                    } finally {
                        sampling.cancelAndJoin()
                    }
                }
                // Built only after the sampler has stopped, so every reading is in the summary.
                // Opening, indexing and closing, as before the later phases existed.
                val totalMs = openMs + indexMs + closeMs
                fun rate(part: List<Pair<Int, Double>>) = part.sumOf { it.first } / (part.sumOf { it.second } / 1000)
                val fifth = maxOf(1, calls.size / 5)
                val mem = if (memBefore == null) "mem=not-measured"
                else "mem_kind=$memoryKind mem_peak=$MEMORY_METHOD mem_before_kb=$memBefore mem_after_open_kb=$memAfterOpen " +
                    "mem_peak_kb=${sampler.peak(Phase.INDEX)} mem_after_index_kb=$memAfterIndex mem_after_close_kb=$memAfterClose " +
                    sampler.summary(INDEX_PHASES)
                println(
                    "[kvid-index-measure] platform=$testPlatform config=$config model=${model.name} variant=${variant ?: "fixture"} " +
                        "model_digest=$digest max_input_tokens=${model.maxInputTokens} batch=${options.maxBatchSize} " +
                        "sort=${if (options.sortByLength) "on" else "off"} arena=${if (options.cpuMemoryArena) "on" else "off"} " +
                        "threads=${options.intraOpThreads ?: "default"} ep=$ep outer=$outer chunk=$chunk notes=${notes.size} tokens=$tokens " +
                        "insert_ms=${fmt("%.0f", insertMs)} open_epoch_ms=$openEpochMs open_ms=${fmt("%.0f", openMs)} close_ms=${fmt("%.0f", closeMs)} " +
                        "index_total_ms=${fmt("%.0f", totalMs)} notes_per_s=${fmt("%.2f", notes.size / (totalMs / 1000))} " +
                        "early_notes_per_s=${fmt("%.2f", rate(calls.take(fifth)))} late_notes_per_s=${fmt("%.2f", rate(calls.takeLast(fifth)))} " +
                        "calls=${calls.size} file_bytes=${SystemFileSystem.metadataOrNull(Path(path))?.size} pause_s=$pause " +
                        "phase_start_s=${phaseStarts(marks)}${queryPhase?.let { " " + it.fields } ?: ""}${editPhase?.let { " " + it.fields } ?: ""} $mem"
                )
                // Every counted call's value, one series per line (logcat keeps lines up to about 4 KB), tied to the
                // result line by open_epoch_ms: so runs can be pooled and the printed percentiles recomputed.
                for ((series, values) in (queryPhase?.series ?: emptyList()) + (editPhase?.series ?: emptyList())) {
                    val parts = values.chunked(SAMPLES_PER_LINE)
                    parts.forEachIndexed { k, part ->
                        println(
                            "[kvid-index-samples] open_epoch_ms=$openEpochMs series=$series part=${k + 1}/${parts.size} " +
                                "n=${values.size} values=${part.joinToString(",")}"
                        )
                    }
                }
            } finally {
                store.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** The fields a phase adds to the result line, and its raw values by series name. */
private class PhaseResult(val fields: String, val series: List<Pair<String, List<String>>>)

private fun List<Double>.formatted() = map { fmt("%.1f", it) }

/**
 * Persisted edits against [store]: alternately a new note and new text for one of [ids], each followed by
 * `indexVectors` for that note alone, timed separately; the first [EDIT_WARMUP] are not counted.
 */
private suspend fun measureEdits(
    store: Kvid,
    embedder: OnnxEmbedder,
    ids: List<DocumentId>,
    tokenizer: BertTokenizer,
    model: BertEmbeddingModel,
    outer: Int,
    count: Int
): PhaseResult {
    val texts = mixedLengthNotes(count + EDIT_WARMUP, seed = 20261111)
    val random = Random(20261111)
    val writes = ArrayList<Double>()
    val saves = ArrayList<Double>()
    val tokens = ArrayList<Int>()
    val kinds = ArrayList<String>()
    texts.forEachIndexed { i, text ->
        val writeMs = timedMs { if (i % 2 == 0) store.put(text) else store.update(ids[random.nextInt(ids.size)], text) }
        var report: VectorIndexReport? = null
        val saveMs = timedMs { report = store.indexVectors(embedder, batchSize = outer, maxDocuments = 1) }
        check(report!!.embedded == 1 && report!!.pending == 0L) { "edit $i embedded ${report!!.embedded} with ${report!!.pending} pending" }
        if (i >= EDIT_WARMUP) {
            writes += writeMs
            saves += saveMs
            tokens += tokenizer.encode(text, model.maxInputTokens).ids.size
            kinds += if (i % 2 == 0) "put" else "update"
        }
    }
    val totals = writes.indices.map { writes[it] + saves[it] }
    return PhaseResult(
        "edits=$count ${percentiles(writes, "edit_write_")} ${latencySummary(saves, tokens, "edit_save_")} " +
            latencySummary(totals, tokens, "edit_total_"),
        listOf(
            "edit_total_ms" to totals.formatted(), "edit_save_ms" to saves.formatted(), "edit_write_ms" to writes.formatted(),
            "edit_tokens" to tokens.map { it.toString() }, "edit_kind" to kinds
        )
    )
}

/**
 * Queries of 2 to 6 words from [NOTE_WORDS] against [store], each of the four calls timed, in the order of
 * row (counted query index mod 4) of [QUERY_ORDERS]; the first [QUERY_WARMUP] are not counted.
 */
private suspend fun measureQueries(store: Kvid, embedder: OnnxEmbedder, count: Int): PhaseResult {
    val live = store.vectorStatus().embedded
    val random = Random(20261112)
    val queries = List(count + QUERY_WARMUP) { (1..2 + random.nextInt(5)).joinToString(" ") { NOTE_WORDS[random.nextInt(NOTE_WORDS.size)] } }
    val times = List(4) { ArrayList<Double>(count) }  // embed, lexical, similar, hybrid
    queries.forEachIndexed { i, query ->
        val ms = DoubleArray(4)
        for (call in QUERY_ORDERS[(i - QUERY_WARMUP).mod(QUERY_ORDERS.size)]) {
            ms[call] = when (call) {
                0 -> timedMs { embedder.embed(listOf(query)) }
                1 -> timedMs { store.find(query, FindOptions(match = MatchMode.ANY)) }
                2 -> timedMs { store.findSimilar(query, embedder) }
                else -> timedMs { store.findHybrid(query, embedder, FindOptions(match = MatchMode.ANY)) }
            }
        }
        if (i >= QUERY_WARMUP) for (call in 0..3) times[call] += ms[call]
    }
    val names = listOf("query_embed_", "query_lexical_", "query_similar_", "query_hybrid_")
    return PhaseResult(
        "queries=$count query_store_notes=$live query_order=williams4 " + names.indices.joinToString(" ") { percentiles(times[it], names[it]) },
        names.indices.map { "${names[it]}ms" to times[it].formatted() }
    )
}

/**
 * A 4×4 Williams square over the query calls (0 embed, 1 lexical, 2 similar, 3 hybrid): each call is in each
 * position once, and each ordered pair of calls is adjacent once, over the four rows.
 */
internal val QUERY_ORDERS = listOf(intArrayOf(0, 1, 3, 2), intArrayOf(1, 2, 0, 3), intArrayOf(2, 3, 1, 0), intArrayOf(3, 0, 2, 1))

/** The indexing test's phases, in the order they run. */
private val INDEX_PHASES = listOf(Phase.OPEN, Phase.INDEX, Phase.PAUSE, Phase.QUERIES, Phase.EDITS, Phase.CLOSE)

private const val EDIT_WARMUP = 5
private const val SAMPLES_PER_LINE = 200
private const val QUERY_WARMUP = 5

/** The settings IndexingMeasurementTest accepts. */
internal val INDEX_SETTINGS = setOf(
    "corpus", "notes", "model", "maxTokens", "batch", "threads", "ep", "sort", "arena", "pattern", "outer", "chunk", "edits", "queries", "pause"
)
