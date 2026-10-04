package com.kvid.store

import androidx.sqlite.SQLiteException

/**
 * Translation of driver failures into [KvidException]s.
 *
 * androidx.sqlite 2.7.1 does not expose a structured result code; its [SQLiteException] message is
 * formatted by the driver as `Error code: <n>, message: <text>`. This translation is pinned to that
 * driver version and covered by [SqliteErrorsTest]; anything it cannot classify maps to [KvidException.Io].
 */
internal object SqliteErrors {
    private val codePattern = Regex("""Error code: (\d+)""")

    // Primary result codes, https://www.sqlite.org/rescode.html
    const val SQLITE_ERROR = 1
    const val SQLITE_BUSY = 5
    const val SQLITE_LOCKED = 6
    const val SQLITE_READONLY = 8
    const val SQLITE_IOERR = 10
    const val SQLITE_CORRUPT = 11
    const val SQLITE_FULL = 13
    const val SQLITE_CANTOPEN = 14
    const val SQLITE_NOTADB = 26

    fun primaryCode(e: SQLiteException): Int? =
        codePattern.find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.let { it and 0xFF }

    /** The driver message without its `Error code: n, message: ` prefix. */
    fun driverMessage(e: SQLiteException): String = e.message.orEmpty().let { it.substringAfter("message: ", it) }

    fun translate(e: SQLiteException, context: String): KvidException {
        val msg = "$context: ${e.message}"
        return when (primaryCode(e)) {
            SQLITE_BUSY, SQLITE_LOCKED -> KvidException.Locked(msg, e)
            SQLITE_READONLY -> KvidException.ReadOnly(msg, e)
            SQLITE_FULL -> KvidException.DiskFull(msg, e)
            SQLITE_CORRUPT -> KvidException.Corrupt(msg, e)
            SQLITE_NOTADB -> KvidException.NotAStore(msg, e)
            SQLITE_IOERR, SQLITE_CANTOPEN -> KvidException.Io(msg, e)
            else -> KvidException.Io(msg, e)
        }
    }

    /** Runs [block], converting driver exceptions. Cancellation and kvid exceptions pass through unchanged. */
    inline fun <T> guard(context: String, block: () -> T): T = try {
        block()
    } catch (e: SQLiteException) {
        throw translate(e, context)
    }
}
