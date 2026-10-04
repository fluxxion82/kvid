package com.kvid.store

import androidx.sqlite.SQLiteException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The driver's message format is pinned to androidx.sqlite 2.7.1 (contract, section 5 implementation gate).
 * On the Android host-JVM test runtime SQLiteException is the platform stub whose message is null, so the
 * translation can only be exercised where the real driver runs.
 */
class SqliteErrorsTest {
    private fun ex(code: Int, text: String = "x") = SQLiteException("Error code: $code, message: $text")
    private fun skip(): Boolean = (!PlatformInfo.storeSupported).also { if (it) println("[kvid] SqliteErrorsTest skipped on ${PlatformInfo.name}") }

    @Test fun primaryCodeIsExtractedAndMasked() {
        if (skip()) return
        assertEquals(13, SqliteErrors.primaryCode(ex(13)))
        assertEquals(11, SqliteErrors.primaryCode(SQLiteException("Error code: 267, message: database disk image is malformed"))) // SQLITE_CORRUPT_VTAB
        assertNull(SqliteErrors.primaryCode(SQLiteException("something else entirely")))
    }

    @Test fun codesMapToTypedExceptions() {
        if (skip()) return
        assertIs<KvidException.DiskFull>(SqliteErrors.translate(ex(13), "t"))
        assertIs<KvidException.Locked>(SqliteErrors.translate(ex(5), "t"))
        assertIs<KvidException.Locked>(SqliteErrors.translate(ex(6), "t"))
        assertIs<KvidException.ReadOnly>(SqliteErrors.translate(ex(8), "t"))
        assertIs<KvidException.Corrupt>(SqliteErrors.translate(ex(11), "t"))
        assertIs<KvidException.NotAStore>(SqliteErrors.translate(ex(26), "t"))
        assertIs<KvidException.Io>(SqliteErrors.translate(ex(10), "t"))
        assertIs<KvidException.Io>(SqliteErrors.translate(ex(19), "t"), "unclassified codes map to Io")
        assertIs<KvidException.Io>(SqliteErrors.translate(SQLiteException("no code here"), "t"))
    }

    @Test fun translationKeepsCauseAndContext() {
        if (skip()) return
        val cause = ex(13, "database or disk is full")
        val translated = SqliteErrors.translate(cause, "insert")
        assertEquals(cause, translated.cause)
        assertEquals("KV_DISK_FULL", translated.code)
        assertEquals(true, translated.message!!.startsWith("insert: "))
    }
}
