package com.kvid.sample

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/** Runs [block] with a fresh store path in a temporary directory, removed afterwards. */
fun sampleTest(block: suspend TestScope.(path: String) -> Unit) = runTest(timeout = 2.minutes) {
    val dir = Path(SystemTemporaryDirectory, "kvid-sample-${Random.nextLong().toULong().toString(16)}")
    SystemFileSystem.createDirectories(dir)
    try {
        block(Path(dir, "notes.kvid").toString())
    } finally {
        runCatching {
            SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
            SystemFileSystem.delete(dir, mustExist = false)
        }
    }
}
