package com.kvid.embed

import com.kvid.store.Kvid
import com.kvid.store.VectorIndexReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
 * The notes are inserted first, timed separately. The indexing total runs from opening the embedder to
 * closing it after the last vector is stored (pending = 0), as an app's background job would. Memory as in
 * the tuning test, with the sampled peak a lower bound. Prints one `[kvid-index-measure]` line; nothing is
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
                val insertMs = timedMs { store.transaction { notes.forEach { put(it) } } }
                val memBefore = memoryKb()
                val start = TimeSource.Monotonic.markNow()
                val embedder = openForTuning(fixture.modelPath, fixture.vocabPath, model, options, ep, providerThreads = threads ?: 4)
                val openMs = start.elapsedNow().inWholeMicroseconds / 1000.0
                val memAfterOpen = memoryKb()
                val calls = ArrayList<Pair<Int, Double>>()  // documents embedded and milliseconds, per indexVectors call
                val peak = AtomicLong(memAfterOpen ?: 0L)
                var memAfterIndex: Long? = null
                val closeMs: Double
                try {
                    withContext(Dispatchers.Default) {
                        val sampler = launch {
                            while (isActive) {
                                memoryKb()?.let { kb -> if (kb > peak.load()) peak.store(kb) }
                                delay(100.milliseconds)
                            }
                        }
                        try {
                            while (true) {
                                var report: VectorIndexReport? = null
                                val ms = timedMs { report = store.indexVectors(embedder, batchSize = outer, maxDocuments = chunk) }
                                calls += report!!.embedded to ms
                                if (report!!.pending == 0L || report!!.embedded == 0) break
                            }
                        } finally {
                            sampler.cancelAndJoin()
                        }
                    }
                    memAfterIndex = memoryKb()
                } finally {
                    closeMs = timedMs { embedder.close() }
                }
                val totalMs = start.elapsedNow().inWholeMicroseconds / 1000.0
                val status = store.vectorStatus()
                assertEquals(0L, status.pending, "every document is embedded")
                assertEquals(notes.size.toLong(), status.embedded)

                fun rate(part: List<Pair<Int, Double>>) = part.sumOf { it.first } / (part.sumOf { it.second } / 1000)
                val fifth = maxOf(1, calls.size / 5)
                val memAfterClose = memoryKb()
                val mem = if (memBefore == null) "mem=not-measured"
                else "mem_kind=$memoryKind mem_peak=sampled-100ms mem_before_kb=$memBefore mem_after_open_kb=$memAfterOpen " +
                    "mem_peak_kb=${maxOf(peak.load(), memAfterIndex ?: 0L)} mem_after_index_kb=$memAfterIndex mem_after_close_kb=$memAfterClose"
                println(
                    "[kvid-index-measure] platform=$testPlatform config=$config model=${model.name} variant=${variant ?: "fixture"} " +
                        "model_digest=${embedder.spec.modelDigest} max_input_tokens=${model.maxInputTokens} batch=${options.maxBatchSize} " +
                        "sort=${if (options.sortByLength) "on" else "off"} arena=${if (options.cpuMemoryArena) "on" else "off"} " +
                        "threads=${options.intraOpThreads ?: "default"} ep=$ep outer=$outer chunk=$chunk notes=${notes.size} tokens=$tokens " +
                        "insert_ms=${fmt("%.0f", insertMs)} open_ms=${fmt("%.0f", openMs)} close_ms=${fmt("%.0f", closeMs)} " +
                        "index_total_ms=${fmt("%.0f", totalMs)} notes_per_s=${fmt("%.2f", notes.size / (totalMs / 1000))} " +
                        "early_notes_per_s=${fmt("%.2f", rate(calls.take(fifth)))} late_notes_per_s=${fmt("%.2f", rate(calls.takeLast(fifth)))} " +
                        "calls=${calls.size} file_bytes=${SystemFileSystem.metadataOrNull(Path(path))?.size} $mem"
                )
            } finally {
                store.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** The settings IndexingMeasurementTest accepts. */
internal val INDEX_SETTINGS = setOf("corpus", "notes", "model", "maxTokens", "batch", "threads", "ep", "sort", "arena", "pattern", "outer", "chunk")
