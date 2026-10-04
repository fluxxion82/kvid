package com.kvid.spike

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * Minimal helpers around the androidx.sqlite driver API used by the ADR 0001 verification tests.
 * Deliberately thin: the point is to exercise the driver, not to design kvid's store API here.
 */
object SpikeDb {
    private val driver = BundledSQLiteDriver()

    // Configure each connection explicitly; bundled defaults differ between DELETE and WAL.
    // EXTRA adds directory synchronization in DELETE mode and is equivalent to FULL in WAL.
    fun open(path: String): SQLiteConnection {
        val connection = driver.open(path)
        try {
            connection.execSQL("PRAGMA synchronous = EXTRA")
            connection.execSQL("PRAGMA foreign_keys = ON")
            return connection
        } catch (failure: Throwable) {
            try {
                connection.close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    fun openInMemory(): SQLiteConnection = driver.open(":memory:")

    /** Runs a statement that returns rows and maps each row. Bind indices are 1-based, columns 0-based. */
    fun <T> SQLiteConnection.query(
        sql: String,
        bind: SQLiteStatement.() -> Unit = {},
        map: SQLiteStatement.() -> T
    ): List<T> = prepare(sql).use { stmt ->
        stmt.bind()
        val out = ArrayList<T>()
        while (stmt.step()) out.add(stmt.map())
        out
    }

    fun SQLiteConnection.scalarText(sql: String): String = query(sql) { getText(0) }.single()

    fun SQLiteConnection.scalarLong(sql: String): Long = query(sql) { getLong(0) }.single()

    fun SQLiteConnection.exec(sql: String) = execSQL(sql)

    fun sqliteVersion(conn: SQLiteConnection): String = conn.scalarText("SELECT sqlite_version()")

    fun compileOptions(conn: SQLiteConnection): List<String> = conn.query("PRAGMA compile_options") { getText(0) }
}

/** A fresh temporary directory per test, with best-effort cleanup. */
class TempDir(prefix: String = "kvid-spike") {
    val path: Path = Path(SystemTemporaryDirectory, "$prefix-${Random.nextLong().toULong().toString(16)}")

    init {
        SystemFileSystem.createDirectories(path)
    }

    fun file(name: String): String = Path(path, name).toString()

    fun exists(name: String): Boolean = SystemFileSystem.exists(Path(path, name))

    fun listNames(): List<String> = SystemFileSystem.list(path).map { it.name }.sorted()

    fun cleanup() {
        runCatching {
            SystemFileSystem.list(path).forEach { SystemFileSystem.delete(it, mustExist = false) }
            SystemFileSystem.delete(path, mustExist = false)
        }
    }
}

/** Little-endian float32 encoding for storing vectors as BLOBs. */
object Float32Blob {
    fun encode(values: FloatArray): ByteArray {
        val out = ByteArray(values.size * 4)
        for ((i, v) in values.withIndex()) {
            val bits = v.toRawBits()
            out[i * 4] = (bits and 0xFF).toByte()
            out[i * 4 + 1] = ((bits ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((bits ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((bits ushr 24) and 0xFF).toByte()
        }
        return out
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0) { "blob length ${bytes.size} is not a multiple of 4" }
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) {
            val bits = (bytes[i * 4].toInt() and 0xFF) or
                ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((bytes[i * 4 + 3].toInt() and 0xFF) shl 24)
            out[i] = Float.fromBits(bits)
        }
        return out
    }
}
