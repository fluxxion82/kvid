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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
 * After indexing, with the same open embedder and the 5,000-note store (the targets in answer 5 of Codex's
 * review of the study plan):
 * - `pause=<s>` (default 0) waits before the phases below, so the phone can cool after indexing;
 *   `phase_start_s` gives each phase's start in seconds, to line up with a driver's thermal readings;
 * - `edits=<n>` (default 0): n persisted edits, alternately a new note (`put`) and new text for an
 *   existing one (`update`), each followed by `indexVectors` for that note alone. It reports p50 and p95 of
 *   the write, of embedding and saving the vector (`edit_save_`), and of both together (`edit_total_`),
 *   overall and by token length. The texts are `mixed`-length notes from a separate seed; 5 warm-up edits
 *   are not counted;
 * - `queries=<n>` (default 0): n queries of 2 to 6 words from the notes' vocabulary. It reports p50 and p95
 *   of the query embedding alone (`query_embed_`), lexical `find` (`query_lexical_`), `findSimilar`
 *   (`query_similar_`) and `findHybrid` (`query_hybrid_`), with the default 20 results and lexical matching
 *   on any word, as in the quality study (random words rarely all occur in one note); 5 warm-up queries are
 *   not counted.
 *
 * Memory is sampled every 100 ms from before the model is opened until after it is closed: `mem_peak_kb`
 * is the peak while indexing, as before, and the phase peaks and `mem_lifecycle_peak_kb` cover the rest
 * ([PhaseSampler]). Sampled peaks are lower bounds. Prints one `[kvid-index-measure]` line; nothing is
 * asserted about time or memory.
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
                val memBefore = memoryKb()
                val clock = TimeSource.Monotonic.markNow()
                fun now() = clock.elapsedNow().inWholeMicroseconds / 1_000_000.0
                val sampler = PhaseSampler(memBefore)
                coroutineScope {
                    // Sampling starts before the model is opened, so opening it is inside the measured lifecycle.
                    val sampling = launch { sampler.run() }
                    try {
                        sampler.phase = Phase.OPEN
                        var embedder: OnnxEmbedder? = null
                        val openMs = timedMs { embedder = openForTuning(fixture.modelPath, fixture.vocabPath, model, options, ep, providerThreads = threads ?: 4) }
                        val opened = embedder!!
                        val memAfterOpen = memoryKb()
                        val calls = ArrayList<Pair<Int, Double>>()  // documents embedded and milliseconds, per indexVectors call
                        var memAfterIndex: Long? = null
                        val marks = linkedMapOf("index" to now())
                        var editLine = ""
                        var queryLine = ""
                        var indexMs = 0.0
                        val closeMs: Double
                        try {
                            sampler.phase = Phase.INDEX
                            indexMs = timedMs {
                                while (true) {
                                    var report: VectorIndexReport? = null
                                    val ms = timedMs { report = store.indexVectors(opened, batchSize = outer, maxDocuments = chunk) }
                                    calls += report!!.embedded to ms
                                    if (report!!.pending == 0L || report!!.embedded == 0) break
                                }
                            }
                            memAfterIndex = memoryKb()
                            val status = store.vectorStatus()
                            assertEquals(0L, status.pending, "every document is embedded")
                            assertEquals(notes.size.toLong(), status.embedded)
                            if (pause > 0) {
                                sampler.phase = Phase.PAUSE
                                marks["pause"] = now()
                                delay(pause.seconds)
                            }
                            if (edits > 0) {
                                sampler.phase = Phase.EDITS
                                marks["edits"] = now()
                                editLine = " " + measureEdits(store, opened, ids, tokenizer, model, outer, edits)
                            }
                            if (queries > 0) {
                                sampler.phase = Phase.QUERIES
                                marks["queries"] = now()
                                queryLine = " " + measureQueries(store, opened, queries)
                            }
                        } finally {
                            sampler.phase = Phase.CLOSE
                            marks["close"] = now()
                            closeMs = timedMs { opened.close() }
                        }
                        marks["end"] = now()
                        // Opening, indexing and closing, as before the edit and query phases existed.
                        val totalMs = openMs + indexMs + closeMs

                        fun rate(part: List<Pair<Int, Double>>) = part.sumOf { it.first } / (part.sumOf { it.second } / 1000)
                        val fifth = maxOf(1, calls.size / 5)
                        val memAfterClose = memoryKb()
                        val mem = if (memBefore == null) "mem=not-measured"
                        else "mem_kind=$memoryKind mem_peak=sampled-100ms-from-before-open mem_before_kb=$memBefore mem_after_open_kb=$memAfterOpen " +
                            "mem_peak_kb=${maxOf(sampler.peak(Phase.INDEX), memAfterIndex ?: 0L)} mem_after_index_kb=$memAfterIndex " +
                            "mem_after_close_kb=$memAfterClose ${sampler.summary()}"
                        println(
                            "[kvid-index-measure] platform=$testPlatform config=$config model=${model.name} variant=${variant ?: "fixture"} " +
                                "model_digest=${opened.spec.modelDigest} max_input_tokens=${model.maxInputTokens} batch=${options.maxBatchSize} " +
                                "sort=${if (options.sortByLength) "on" else "off"} arena=${if (options.cpuMemoryArena) "on" else "off"} " +
                                "threads=${options.intraOpThreads ?: "default"} ep=$ep outer=$outer chunk=$chunk notes=${notes.size} tokens=$tokens " +
                                "insert_ms=${fmt("%.0f", insertMs)} open_ms=${fmt("%.0f", openMs)} close_ms=${fmt("%.0f", closeMs)} " +
                                "index_total_ms=${fmt("%.0f", totalMs)} notes_per_s=${fmt("%.2f", notes.size / (totalMs / 1000))} " +
                                "early_notes_per_s=${fmt("%.2f", rate(calls.take(fifth)))} late_notes_per_s=${fmt("%.2f", rate(calls.takeLast(fifth)))} " +
                                "calls=${calls.size} file_bytes=${SystemFileSystem.metadataOrNull(Path(path))?.size} pause_s=$pause " +
                                "phase_start_s=${marks.entries.joinToString(",") { "${it.key}:${fmt("%.1f", it.value)}" }}$editLine$queryLine $mem"
                        )
                    } finally {
                        sampling.cancelAndJoin()
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
): String {
    val texts = mixedLengthNotes(count + EDIT_WARMUP, seed = 20261111)
    val random = Random(20261111)
    val writes = ArrayList<Double>()
    val saves = ArrayList<Double>()
    val tokens = ArrayList<Int>()
    texts.forEachIndexed { i, text ->
        val writeMs = timedMs { if (i % 2 == 0) store.put(text) else store.update(ids[random.nextInt(ids.size)], text) }
        var report: VectorIndexReport? = null
        val saveMs = timedMs { report = store.indexVectors(embedder, batchSize = outer, maxDocuments = 1) }
        check(report!!.embedded == 1 && report!!.pending == 0L) { "edit $i embedded ${report!!.embedded} with ${report!!.pending} pending" }
        if (i >= EDIT_WARMUP) {
            writes += writeMs
            saves += saveMs
            tokens += tokenizer.encode(text, model.maxInputTokens).ids.size
        }
    }
    val totals = writes.indices.map { writes[it] + saves[it] }
    return "edits=$count ${percentiles(writes, "edit_write_")} ${latencySummary(saves, tokens, "edit_save_")} " +
        latencySummary(totals, tokens, "edit_total_")
}

/** Queries of 2 to 6 words from [NOTE_WORDS] against [store], each step timed; the first [QUERY_WARMUP] are not counted. */
private suspend fun measureQueries(store: Kvid, embedder: OnnxEmbedder, count: Int): String {
    val random = Random(20261112)
    val queries = List(count + QUERY_WARMUP) { (1..2 + random.nextInt(5)).joinToString(" ") { NOTE_WORDS[random.nextInt(NOTE_WORDS.size)] } }
    val embed = ArrayList<Double>()
    val lexical = ArrayList<Double>()
    val similar = ArrayList<Double>()
    val hybrid = ArrayList<Double>()
    queries.forEachIndexed { i, query ->
        val e = timedMs { embedder.embed(listOf(query)) }
        val l = timedMs { store.find(query, FindOptions(match = MatchMode.ANY)) }
        val s = timedMs { store.findSimilar(query, embedder) }
        val h = timedMs { store.findHybrid(query, embedder, FindOptions(match = MatchMode.ANY)) }
        if (i >= QUERY_WARMUP) {
            embed += e
            lexical += l
            similar += s
            hybrid += h
        }
    }
    return "queries=$count ${percentiles(embed, "query_embed_")} ${percentiles(lexical, "query_lexical_")} " +
        "${percentiles(similar, "query_similar_")} ${percentiles(hybrid, "query_hybrid_")}"
}

private const val EDIT_WARMUP = 5
private const val QUERY_WARMUP = 5

/** The settings IndexingMeasurementTest accepts. */
internal val INDEX_SETTINGS = setOf(
    "corpus", "notes", "model", "maxTokens", "batch", "threads", "ep", "sort", "arena", "pattern", "outer", "chunk", "edits", "queries", "pause"
)
