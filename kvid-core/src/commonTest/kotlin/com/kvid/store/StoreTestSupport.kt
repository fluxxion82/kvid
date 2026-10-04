package com.kvid.store

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/** A fresh temporary directory per test, with best-effort cleanup. */
class TempDir(prefix: String = "kvid-store") {
    val path: Path = Path(SystemTemporaryDirectory, "$prefix-${Random.nextLong().toULong().toString(16)}")

    init { SystemFileSystem.createDirectories(path) }

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

/**
 * Runs a store test in a temp dir. On platforms where the bundled SQLite cannot load (the Android
 * host-JVM unit test runtime) the test is reported as skipped via a printed line rather than failing.
 */
fun storeTest(block: suspend TestScope.(TempDir) -> Unit) = runTest(timeout = 5.minutes) {
    if (!PlatformInfo.storeSupported) {
        println("[kvid] store tests skipped on ${PlatformInfo.name}: bundled SQLite is not loadable in this runtime")
        return@runTest
    }
    val dir = TempDir()
    try { block(dir) } finally { dir.cleanup() }
}

/** Overwrites [path] with [bytes]. Used for corruption injection. */
fun overwriteFile(path: String, bytes: ByteArray) {
    SystemFileSystem.sink(Path(path)).use { sink ->
        val buffer = kotlinx.io.Buffer()
        buffer.write(bytes)
        sink.write(buffer, buffer.size)
    }
}

fun readFile(path: String): ByteArray = SystemFileSystem.source(Path(path)).use { src ->
    val buffer = kotlinx.io.Buffer()
    while (src.readAtMostTo(buffer, 65536) >= 0) { /* drain */ }
    buffer.readByteArray()
}
