package com.kvid.spike

import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.kvid.spike.SpikeDb.exec
import com.kvid.spike.SpikeDb.query
import com.kvid.spike.SpikeDb.scalarLong
import com.kvid.spike.SpikeDb.scalarText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Connection/transaction primitives, not process-kill or power-loss verification. */
class DurabilityTest {
    private lateinit var dir: TempDir

    @BeforeTest fun setUp() { dir = TempDir() }
    @AfterTest fun tearDown() { dir.cleanup() }

    @Test
    fun writableConnectionsUseExtraAndEnforceForeignKeys() {
        SpikeDb.open(dir.file("store.db")).use { conn ->
            assertEquals(3L, conn.scalarLong("PRAGMA synchronous"), "DELETE-mode durability requires EXTRA")
            assertEquals(1L, conn.scalarLong("PRAGMA foreign_keys"), "relations must be enforced explicitly")
        }
    }

    @Test
    fun uncommittedWriteIsLostOnCloseAndCommittedWriteSurvivesReopen() {
        val path = dir.file("store.db")
        SpikeDb.open(path).use { conn ->
            conn.exec("CREATE TABLE t(x TEXT NOT NULL)")
            conn.exec("BEGIN")
            conn.exec("INSERT INTO t(x) VALUES ('staged only')")
            assertEquals(1L, conn.scalarLong("SELECT count(*) FROM t"), "read-your-writes inside the transaction")
            // close() without COMMIT: SQLite rolls the transaction back
        }
        SpikeDb.open(path).use { conn ->
            assertEquals(0L, conn.scalarLong("SELECT count(*) FROM t"), "staged work must not survive close()")
            conn.exec("INSERT INTO t(x) VALUES ('autocommit')")
            conn.exec("BEGIN")
            conn.exec("INSERT INTO t(x) VALUES ('explicit')")
            conn.exec("COMMIT")
        }
        SpikeDb.open(path).use { conn ->
            assertEquals(listOf("autocommit", "explicit"), conn.query("SELECT x FROM t ORDER BY rowid") { getText(0) })
        }
    }

    @Test
    fun rollbackJournalModeLeavesExactlyOneFileAfterClose() {
        val path = dir.file("store.db")
        SpikeDb.open(path).use { conn ->
            assertEquals("delete", conn.scalarText("PRAGMA journal_mode").lowercase())
            conn.exec("CREATE TABLE t(x TEXT)")
            conn.exec("BEGIN")
            conn.exec("INSERT INTO t(x) VALUES ('a')")
            // During a write transaction a -journal sidecar is allowed (contract section 2)
            conn.exec("COMMIT")
        }
        assertEquals(listOf("store.db"), dir.listNames(), "exactly one file after a clean close")
    }

    @Test
    fun walSidecarsAreRemovedByCheckpointAndClose() {
        val path = dir.file("store.db")
        SpikeDb.open(path).use { conn ->
            assertEquals("wal", conn.scalarText("PRAGMA journal_mode = WAL").lowercase())
            conn.exec("CREATE TABLE t(x TEXT)")
            conn.exec("INSERT INTO t(x) VALUES ('a')")
            assertTrue(dir.exists("store.db-wal"), "WAL sidecar exists while open and written")
            // close() procedure from the contract: checkpoint and switch back to DELETE
            assertEquals("delete", conn.scalarText("PRAGMA journal_mode = DELETE").lowercase())
        }
        assertFalse(dir.exists("store.db-wal"), "-wal must be gone after close")
        assertFalse(dir.exists("store.db-shm"), "-shm must be gone after close")
        assertEquals(listOf("store.db"), dir.listNames())
        SpikeDb.open(path).use { conn ->
            assertEquals(1L, conn.scalarLong("SELECT count(*) FROM t"))
        }
    }

    @Test
    fun vacuumIntoSnapshotsCommittedRowsWhileWriterHasStagedChanges() {
        val path = dir.file("store.db")
        val snapshot = dir.file("snapshot's.db")
        SpikeDb.open(path).use { writer ->
            assertEquals("wal", writer.scalarText("PRAGMA journal_mode = WAL"))
            writer.exec("CREATE TABLE t(x TEXT)")
            writer.exec("BEGIN IMMEDIATE")
            repeat(200) { writer.exec("INSERT INTO t(x) VALUES ('row $it')") }
            writer.exec("COMMIT")
            writer.exec("BEGIN IMMEDIATE")
            writer.exec("INSERT INTO t(x) VALUES ('uncommitted at snapshot time')")
            assertEquals(201L, writer.scalarLong("SELECT count(*) FROM t"))
            // A separate, genuinely read-only connection cannot see the writer's staged row.
            BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY).use { reader ->
                assertEquals(200L, reader.scalarLong("SELECT count(*) FROM t"))
                reader.prepare("VACUUM INTO ?").use { statement ->
                    statement.bindText(1, snapshot)
                    statement.step()
                }
            }
            BundledSQLiteDriver().open(snapshot, SQLITE_OPEN_READONLY).use { reader ->
                assertEquals("ok", reader.scalarText("PRAGMA integrity_check"))
                assertEquals(200L, reader.scalarLong("SELECT count(*) FROM t"))
            }
            // Roll back only after inspecting the snapshot, not before exporting it.
            writer.exec("ROLLBACK")
            assertEquals("delete", writer.scalarText("PRAGMA journal_mode = DELETE"))
        }
        assertEquals(listOf("snapshot's.db", "store.db"), dir.listNames())
    }

    @Test
    fun activeWalReaderPreventsPortableJournalModeSwitch() {
        val path = dir.file("store.db")
        SpikeDb.open(path).use { writer ->
            writer.exec("PRAGMA journal_mode = WAL")
            writer.exec("CREATE TABLE t(x TEXT)")
            writer.exec("INSERT INTO t(x) VALUES ('committed')")
            BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY).use { reader ->
                reader.exec("BEGIN")
                assertEquals(1L, reader.scalarLong("SELECT count(*) FROM t"))
                assertSqliteCode(5) { writer.scalarText("PRAGMA journal_mode = DELETE") }
                assertEquals("wal", writer.scalarText("PRAGMA journal_mode"))
                reader.exec("ROLLBACK")
            }
            assertEquals("delete", writer.scalarText("PRAGMA journal_mode = DELETE"))
        }
        assertEquals(listOf("store.db"), dir.listNames())
    }

    @Test
    fun secondWriterIsBusyAndReaderOnlySeesCommittedRows() {
        val path = dir.file("store.db")
        SpikeDb.open(path).use { first ->
            first.exec("CREATE TABLE t(x TEXT)")
            first.exec("INSERT INTO t(x) VALUES ('committed')")
            first.exec("BEGIN IMMEDIATE")
            first.exec("INSERT INTO t(x) VALUES ('staged')")
            SpikeDb.open(path).use { second ->
                second.exec("PRAGMA busy_timeout = 50")
                assertSqliteCode(5) { second.exec("BEGIN IMMEDIATE") }
                assertEquals(listOf("committed"), second.query("SELECT x FROM t") { getText(0) })
            }
            BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY).use { reader ->
                assertSqliteCode(8) { reader.exec("INSERT INTO t(x) VALUES ('not permitted')") }
                assertEquals(1L, reader.scalarLong("SELECT count(*) FROM t"))
            }
            first.exec("ROLLBACK")
        }
        SpikeDb.open(path).use { conn -> assertEquals(1L, conn.scalarLong("SELECT count(*) FROM t")) }
    }

    @Test
    fun rollbackPreservesOuterSavepointWorkAndCommittedIdsAreNotReused() {
        SpikeDb.open(dir.file("store.db")).use { conn ->
            conn.exec("CREATE TABLE t(id INTEGER PRIMARY KEY AUTOINCREMENT, x TEXT)")
            conn.exec("BEGIN")
            conn.exec("INSERT INTO t(x) VALUES ('outer')")
            conn.exec("SAVEPOINT inner_write")
            conn.exec("INSERT INTO t(x) VALUES ('inner')")
            conn.exec("ROLLBACK TO inner_write")
            conn.exec("RELEASE inner_write")
            assertEquals(listOf("outer"), conn.query("SELECT x FROM t") { getText(0) })
            conn.exec("COMMIT")
            val committedId = conn.scalarLong("SELECT id FROM t")
            conn.exec("DELETE FROM t")
            conn.exec("INSERT INTO t(x) VALUES ('later')")
            assertTrue(conn.scalarLong("SELECT id FROM t") > committedId)
        }
    }

    @Test
    fun corruptionIsDetectedNotSilentlyRead() {
        val path = dir.file("store.db")
        SpikeDb.open(path).use { conn ->
            conn.exec("CREATE TABLE t(x TEXT)")
            conn.exec("INSERT INTO t(x) VALUES ('hello')")
        }
        // Replace the file with 64 invalid bytes: this tests an invalid header, not arbitrary corruption.
        kotlinx.io.files.SystemFileSystem.sink(kotlinx.io.files.Path(path)).use { sink ->
            val buffer = kotlinx.io.Buffer()
            buffer.write(ByteArray(64) { 0x41 })
            sink.write(buffer, buffer.size)
        }
        assertSqliteCode(26) {
            SpikeDb.open(path).use { conn -> conn.scalarLong("SELECT count(*) FROM t") }
        }
    }

    @Test
    fun diskFullIsSurfacedAsAnErrorAndStateIsUnchanged() {
        val path = dir.file("store.db")
        SpikeDb.open(path).use { conn ->
            conn.exec("PRAGMA page_size = 4096")
            conn.exec("CREATE TABLE t(x BLOB)")
            conn.exec("INSERT INTO t(x) VALUES (zeroblob(1000))")
            conn.exec("PRAGMA max_page_count = 3")  // simulate a full disk
            conn.exec("BEGIN")
            conn.exec("INSERT INTO t(x) VALUES (zeroblob(1000))")
            assertEquals(2L, conn.scalarLong("SELECT count(*) FROM t"))
            assertSqliteCode(13) { conn.exec("INSERT INTO t(x) VALUES (zeroblob(200000))") }
            assertEquals(1L, conn.scalarLong("SELECT count(*) FROM t"), "SQLITE_FULL rolls back prior staged writes too")
            assertEquals("ok", conn.scalarText("PRAGMA integrity_check"))
        }
        SpikeDb.open(path).use { conn -> assertEquals(1L, conn.scalarLong("SELECT count(*) FROM t")) }
    }

    // AndroidX 2.7.1 exposes SQLiteException.message, not a structured resultCode.
    // Pin this observation in the spike; production must not mistake it for a stable API.
    private fun assertSqliteCode(code: Int, operation: () -> Unit) {
        val failure = assertFailsWith<SQLiteException> { operation() }
        val observed = Regex("Error code: ([0-9]+),").find(failure.message.orEmpty())
            ?.groupValues?.get(1)?.toInt()
        assertEquals(code, observed, "Expected SQLite code $code, got: ${failure.message}")
    }
}
