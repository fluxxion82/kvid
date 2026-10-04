package com.kvid.spike

import com.kvid.spike.SpikeDb.exec
import com.kvid.spike.SpikeDb.query
import com.kvid.spike.SpikeDb.scalarLong
import com.kvid.spike.SpikeDb.scalarText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR 0001 verification of the persistence contract's single-file and durability rules (sections 2 and 3). */
class DurabilityTest {
    private lateinit var dir: TempDir

    @BeforeTest fun setUp() { dir = TempDir() }
    @AfterTest fun tearDown() { dir.cleanup() }

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
    fun vacuumIntoProducesConsistentSnapshotWhileOpen() {
        val path = dir.file("store.db")
        val snapshot = dir.file("snapshot.db")
        SpikeDb.open(path).use { conn ->
            conn.exec("CREATE TABLE t(x TEXT)")
            conn.exec("BEGIN")
            repeat(200) { conn.exec("INSERT INTO t(x) VALUES ('row $it')") }
            conn.exec("COMMIT")
            conn.exec("BEGIN")
            conn.exec("INSERT INTO t(x) VALUES ('uncommitted at snapshot time')")
            conn.exec("ROLLBACK")
            conn.exec("VACUUM INTO '${snapshot.replace("'", "''")}'")
            assertEquals(200L, conn.scalarLong("SELECT count(*) FROM t"))
        }
        SpikeDb.open(snapshot).use { conn ->
            assertEquals("ok", conn.scalarText("PRAGMA integrity_check"))
            assertEquals(200L, conn.scalarLong("SELECT count(*) FROM t"), "snapshot holds the committed rows only")
        }
        assertEquals(listOf("snapshot.db", "store.db"), dir.listNames())
    }

    @Test
    fun corruptionIsDetectedNotSilentlyRead() {
        val path = dir.file("store.db")
        SpikeDb.open(path).use { conn ->
            conn.exec("CREATE TABLE t(x TEXT)")
            conn.exec("INSERT INTO t(x) VALUES ('hello')")
        }
        // Overwrite the file header with garbage: a kvid store must raise, never return partial data.
        kotlinx.io.files.SystemFileSystem.sink(kotlinx.io.files.Path(path)).use { sink ->
            val buffer = kotlinx.io.Buffer()
            buffer.write(ByteArray(64) { 0x41 })
            sink.write(buffer, buffer.size)
        }
        val failed = runCatching {
            SpikeDb.open(path).use { conn -> conn.scalarLong("SELECT count(*) FROM t") }
        }
        assertTrue(failed.isFailure, "reading a store whose header is not SQLite must fail")
        println("[spike] corruption surfaced as: ${failed.exceptionOrNull()?.message}")
    }

    @Test
    fun diskFullIsSurfacedAsAnErrorAndStateIsUnchanged() {
        val path = dir.file("store.db")
        SpikeDb.open(path).use { conn ->
            conn.exec("PRAGMA page_size = 4096")
            conn.exec("CREATE TABLE t(x BLOB)")
            conn.exec("INSERT INTO t(x) VALUES (zeroblob(1000))")
            conn.exec("PRAGMA max_page_count = 3")  // simulate a full disk
            val failed = runCatching {
                conn.exec("INSERT INTO t(x) VALUES (zeroblob(200000))")
            }
            assertTrue(failed.isFailure, "insert beyond max_page_count must fail")
            println("[spike] disk-full surfaced as: ${failed.exceptionOrNull()?.message}")
            assertEquals(1L, conn.scalarLong("SELECT count(*) FROM t"), "state unchanged after the failed write")
        }
    }
}
