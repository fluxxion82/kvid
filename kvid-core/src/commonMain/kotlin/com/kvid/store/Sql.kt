package com.kvid.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/** Thin, exception-translating helpers over the driver API. Bind indices are 1-based, columns 0-based. */
internal object Sql {
    fun <T> SQLiteConnection.readSnapshot(block: () -> T): T {
        exec("BEGIN")
        try {
            val result = block()
            exec("COMMIT")
            return result
        } catch (failure: Throwable) {
            try { if (inTransaction()) exec("ROLLBACK") } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun SQLiteConnection.exec(sql: String) = SqliteErrors.guard(sql.take(60)) { execSQL(sql) }

    inline fun <T> SQLiteConnection.query(
        sql: String,
        crossinline bind: SQLiteStatement.() -> Unit = {},
        crossinline map: SQLiteStatement.() -> T
    ): List<T> = SqliteErrors.guard(sql.take(60)) {
        prepare(sql).use { stmt ->
            stmt.bind()
            val out = ArrayList<T>()
            while (stmt.step()) out.add(stmt.map())
            out
        }
    }

    inline fun <T> SQLiteConnection.queryOne(
        sql: String,
        crossinline bind: SQLiteStatement.() -> Unit = {},
        crossinline map: SQLiteStatement.() -> T
    ): T? = SqliteErrors.guard(sql.take(60)) {
        prepare(sql).use { stmt ->
            stmt.bind()
            if (stmt.step()) stmt.map() else null
        }
    }

    fun SQLiteConnection.queryLong(sql: String): Long =
        queryOne(sql) { getLong(0) } ?: throw KvidException.Io("no row returned for: $sql")

    fun SQLiteConnection.queryText(sql: String): String =
        queryOne(sql) { getText(0) } ?: throw KvidException.Io("no row returned for: $sql")

    fun SQLiteConnection.update(sql: String, bind: SQLiteStatement.() -> Unit) = SqliteErrors.guard(sql.take(60)) {
        prepare(sql).use { stmt -> stmt.bind(); stmt.step() }
    }

    fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)
    fun SQLiteStatement.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) = if (value == null) bindNull(index) else bindText(index, value)
    fun SQLiteStatement.bindLongOrNull(index: Int, value: Long?) = if (value == null) bindNull(index) else bindLong(index, value)
}
