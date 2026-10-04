package com.kvid.spike

import com.kvid.spike.SpikeDb.exec
import com.kvid.spike.SpikeDb.query
import com.kvid.spike.SpikeDb.scalarLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * ADR 0001: order-of-magnitude timings for a notes-sized corpus. Numbers are printed for the ADR;
 * timings are observational, not correctness assertions or phone performance budgets.
 */
class TimingTest {
    private lateinit var dir: TempDir

    @BeforeTest fun setUp() { dir = TempDir() }
    @AfterTest fun tearDown() { dir.cleanup() }

    @Test
    fun ingestAndQueryFiveThousandDocuments() {
        val rnd = Random(42)
        val words = listOf("plan", "budget", "meeting", "lisbon", "flight", "milk", "retro", "quarter", "review", "notes",
            "standup", "design", "kotlin", "sqlite", "memory", "search", "index", "journal", "trip", "finance")
        fun doc(): String = (1..rnd.nextInt(20, 80)).joinToString(" ") { words[rnd.nextInt(words.size)] }

        val path = dir.file("store.db")
        val clock = TimeSource.Monotonic
        SpikeDb.open(path).use { conn ->
            conn.exec("CREATE TABLE versions(version_id INTEGER PRIMARY KEY, title TEXT, body TEXT, event_time_ms INTEGER)")
            conn.exec("CREATE VIRTUAL TABLE versions_fts USING fts5(title, body, content='versions', content_rowid='version_id')")
            conn.exec("CREATE TRIGGER versions_ai AFTER INSERT ON versions BEGIN INSERT INTO versions_fts(rowid, title, body) VALUES (new.version_id, new.title, new.body); END")

            val ingest = clock.markNow()
            conn.exec("BEGIN")
            conn.prepare("INSERT INTO versions(title, body, event_time_ms) VALUES (?, ?, ?)").use { stmt ->
                repeat(5000) { i ->
                    stmt.reset(); stmt.clearBindings()
                    stmt.bindText(1, "note $i"); stmt.bindText(2, doc()); stmt.bindLong(3, 1_700_000_000_000L + i * 60_000L)
                    stmt.step()
                }
            }
            conn.exec("COMMIT")
            val ingestMs = ingest.elapsedNow().inWholeMilliseconds
            assertEquals(5000L, conn.scalarLong("SELECT count(*) FROM versions"))

            val queries = listOf("plan", "budget review", "lisbon OR flight", "kot*", "\"design review\"", "search NOT index")
            val search = clock.markNow()
            var hits = 0
            repeat(50) { i ->
                val q = queries[i % queries.size]
                hits += conn.query(
                    "SELECT rowid FROM versions_fts WHERE versions_fts MATCH ? ORDER BY bm25(versions_fts) LIMIT 20",
                    bind = { bindText(1, q) }
                ) { getLong(0) }.size
            }
            val searchMs = search.elapsedNow().inWholeMilliseconds

            val open = clock.markNow()
            SpikeDb.open(path).use { c2 -> c2.scalarLong("SELECT count(*) FROM versions") }
            val openMs = open.elapsedNow().inWholeMilliseconds
            val fileBytes = conn.scalarLong("SELECT page_count * page_size FROM pragma_page_count(), pragma_page_size()")

            println("[spike] ingest 5000 docs: ${ingestMs} ms; 50 fts queries: ${searchMs} ms (${hits} hits); second-open+count: ${openMs} ms; file: ${fileBytes / 1024} KiB")
            assertTrue(hits > 0)
        }
    }
}
