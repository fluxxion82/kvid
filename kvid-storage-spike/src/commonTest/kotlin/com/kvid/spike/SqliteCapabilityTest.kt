package com.kvid.spike

import com.kvid.spike.SpikeDb.exec
import com.kvid.spike.SpikeDb.query
import com.kvid.spike.SpikeDb.scalarText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR 0001 verification: is one SQLite build with FTS5 available on this target? */
class SqliteCapabilityTest {

    @Test
    fun bundledSqliteReportsVersionAndFts5() {
        SpikeDb.openInMemory().use { conn ->
            val version = SpikeDb.sqliteVersion(conn)
            val options = SpikeDb.compileOptions(conn)
            println("[spike] sqlite_version=$version")
            println("[spike] compile_options=${options.joinToString(",")}")

            val (major, minor) = version.split(".").map { it.toInt() }
            assertTrue(major > 3 || (major == 3 && minor >= 40), "expected SQLite >= 3.40, got $version")
            assertTrue("ENABLE_FTS5" in options, "FTS5 must be compiled into the bundled driver; options=$options")
            assertEquals("ok", conn.scalarText("PRAGMA integrity_check"))
        }
    }

    @Test
    fun fts5MatchAndBm25RankDocuments() {
        SpikeDb.openInMemory().use { conn ->
            conn.exec("CREATE VIRTUAL TABLE docs USING fts5(title, body, tokenize = 'unicode61')")
            val rows = listOf(
                1L to ("Q4 planning" to "We met Sam about the Q4 plan and budget."),
                2L to ("Grocery list" to "Milk, eggs, bread."),
                3L to ("Planning retro" to "Retro on last quarter planning: what went well."),
                4L to ("Trip" to "Flights to Lisbon booked for May."),
                5L to ("Budget" to "Budget review with finance; Q4 numbers look fine."),
                6L to ("Standup" to "Daily standup notes: blocked on the plan review.")
            )
            conn.prepare("INSERT INTO docs(rowid, title, body) VALUES (?, ?, ?)").use { stmt ->
                for ((id, tb) in rows) {
                    stmt.reset(); stmt.clearBindings()
                    stmt.bindLong(1, id); stmt.bindText(2, tb.first); stmt.bindText(3, tb.second)
                    stmt.step()
                }
            }

            fun search(match: String): List<Pair<Long, Double>> = conn.query(
                "SELECT rowid, bm25(docs) FROM docs WHERE docs MATCH ? ORDER BY bm25(docs), rowid",
                bind = { bindText(1, match) }
            ) { getLong(0) to getDouble(1) }

            assertEquals(setOf(1L, 6L), search("plan").map { it.first }.toSet(), "exact token")
            assertEquals(setOf(1L, 3L, 6L), search("plan*").map { it.first }.toSet(), "prefix query")
            assertEquals(listOf(1L), search("\"Q4 plan\"").map { it.first }, "phrase query")
            assertEquals(listOf(5L), search("title:budget").map { it.first }, "column filter")
            assertEquals(setOf(1L, 5L), search("budget").map { it.first }.toSet(), "both columns searched")
            assertTrue(search("plan").all { it.second < 0.0 }, "bm25() returns negative scores, lower is better")

            val snippet = conn.query(
                "SELECT snippet(docs, 1, '[', ']', '…', 6) FROM docs WHERE docs MATCH ? AND rowid = 1",
                bind = { bindText(1, "plan") }
            ) { getText(0) }.single()
            assertTrue("[plan]" in snippet, "snippet() should highlight the match, got: $snippet")
        }
    }

    @Test
    fun externalContentFtsStaysInSyncThroughTriggers() {
        // The contract stores text once (versions table) and indexes it through an external-content FTS table.
        SpikeDb.openInMemory().use { conn ->
            conn.exec("CREATE TABLE versions(version_id INTEGER PRIMARY KEY, title TEXT, body TEXT, tombstone INTEGER NOT NULL DEFAULT 0)")
            conn.exec("CREATE VIRTUAL TABLE versions_fts USING fts5(title, body, content='versions', content_rowid='version_id')")
            conn.exec("CREATE TRIGGER versions_ai AFTER INSERT ON versions BEGIN INSERT INTO versions_fts(rowid, title, body) VALUES (new.version_id, new.title, new.body); END")
            conn.exec("CREATE TRIGGER versions_ad AFTER DELETE ON versions BEGIN INSERT INTO versions_fts(versions_fts, rowid, title, body) VALUES ('delete', old.version_id, old.title, old.body); END")

            conn.exec("INSERT INTO versions(title, body) VALUES ('first', 'alpha beta'), ('second', 'beta gamma'), ('third', 'gamma delta')")
            val beta = conn.query("SELECT rowid FROM versions_fts WHERE versions_fts MATCH 'beta' ORDER BY rowid") { getLong(0) }
            assertEquals(listOf(1L, 2L), beta)

            conn.exec("DELETE FROM versions WHERE version_id = 2")
            val afterDelete = conn.query("SELECT rowid FROM versions_fts WHERE versions_fts MATCH 'beta' ORDER BY rowid") { getLong(0) }
            assertEquals(listOf(1L), afterDelete, "deleting the content row must drop it from the index")

            conn.exec("INSERT INTO versions_fts(versions_fts) VALUES ('integrity-check')")
            conn.exec("INSERT INTO versions_fts(versions_fts) VALUES ('rebuild')")
            val rebuilt = conn.query("SELECT rowid FROM versions_fts WHERE versions_fts MATCH 'gamma' ORDER BY rowid") { getLong(0) }
            assertEquals(listOf(3L), rebuilt, "rebuild from authoritative content must reproduce the index")
        }
    }
}
