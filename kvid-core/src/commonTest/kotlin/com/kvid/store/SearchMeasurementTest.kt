package com.kvid.store

import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Milestone 3 measurements on a synthetic 5,000-note corpus (Zipf-like vocabulary of 3,000 words, 40 to
 * 300 words per note). Prints one `[kvid-measure]` line per platform for the roadmap; nothing here asserts
 * a time budget. Durations are milliseconds with one decimal, as median/p95 where several runs are timed.
 */
class SearchMeasurementTest {
    private val random = Random(20261004)
    private val syllables = listOf("ka", "to", "ri", "mo", "sen", "dal", "ve", "lu", "pra", "ni", "sho", "ter", "ba", "ex", "on", "mi", "ul", "gra", "fo", "qui")
    private val vocabulary: List<String> = (0 until 3000).map { i ->
        buildString { repeat(2 + i % 3) { append(syllables[random.nextInt(syllables.size)]) }; append(i) }
    }
    private val tags = (1..20).map { "tag$it" }

    private fun word(): String = vocabulary[(random.nextDouble().pow(2.5) * vocabulary.size).toInt().coerceIn(0, vocabulary.size - 1)]
    private fun words(n: Int): String = (1..n).joinToString(" ") { word() }

    @Test fun measureSyntheticNotesCorpus() = storeTest { dir ->
        val clock = TimeSource.Monotonic
        val path = dir.file("notes.kvid")
        val store = Kvid.create(path)
        val docs = 5000
        val batchSize = 500

        val ingestStart = clock.markNow()
        repeat(docs / batchSize) { batch ->
            store.transaction {
                repeat(batchSize) { i ->
                    val n = batch * batchSize + i
                    put(
                        words(40 + random.nextInt(260)),
                        PutOptions(title = words(2 + random.nextInt(5)), tags = listOf(tags[random.nextInt(tags.size)]), eventTimeMs = 1_700_000_000_000 + n * 60_000L)
                    )
                }
            }
        }
        val ingestMs = ingestStart.elapsedNow().inWholeMicroseconds.toMs()

        val autocommit = (1..100).map { timed(clock) { store.put(words(80), PutOptions(title = words(3))) } }
        val queries = (1..50).map { words(1 + random.nextInt(3)) }
        val all = queries.map { q -> timed(clock) { store.find(q, FindOptions(limit = 20)) } }
        val any = queries.map { q -> timed(clock) { store.find(q, FindOptions(limit = 20, match = MatchMode.ANY)) } }
        val tagged = queries.map { q -> timed(clock) { store.find(q, FindOptions(limit = 20, match = MatchMode.ANY, tags = listOf("tag7"))) } }
        val listed = (1..20).map { timed(clock) { store.list(ListOptions(limit = 50)) } }
        val commonHits = store.find(vocabulary[0], FindOptions(match = MatchMode.ANY)).items.size
        val stats = store.stats()
        store.close()

        val reopenStart = clock.markNow()
        val reopened = Kvid.open(path)
        reopened.find(queries.first(), FindOptions(limit = 20, match = MatchMode.ANY))
        val reopenMs = reopenStart.elapsedNow().inWholeMicroseconds.toMs()
        reopened.close()

        println(
            "[kvid-measure] platform=${PlatformInfo.name} docs=${stats.liveDocuments} ingest_batched_ms=$ingestMs " +
                "put_autocommit_ms=${autocommit.median()}/${autocommit.p95()} find_all_ms=${all.median()}/${all.p95()} " +
                "find_any_ms=${any.median()}/${any.p95()} find_any_tag_ms=${tagged.median()}/${tagged.p95()} " +
                "list_ms=${listed.median()}/${listed.p95()} reopen_first_find_ms=$reopenMs file_bytes=${stats.fileBytes}"
        )
        assertEquals((docs + 100).toLong(), stats.liveDocuments)
        assertTrue(commonHits > 0, "the most frequent word has hits")
    }

    private inline fun timed(clock: TimeSource.Monotonic, block: () -> Unit): Long {
        val start = clock.markNow()
        block()
        return start.elapsedNow().inWholeMicroseconds
    }

    private fun Long.toMs(): Double = (this / 100) / 10.0
    private fun List<Long>.median(): Double = sorted()[size / 2].toMs()
    private fun List<Long>.p95(): Double = sorted()[(size * 95 / 100).coerceAtMost(size - 1)].toMs()
}
