package com.kvid.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * Actual process termination mid-transaction (contract, section 11), as opposed to an orderly close:
 * a child JVM is SIGKILLed while holding an open write transaction; the parent reopens the store.
 */
class ProcessKillRecoveryTest {
    @Test fun killedMidTransactionLeavesLastCommittedState() = runTest(timeout = 3.minutes) {
        val dir = TempDir("kvid-kill")
        try {
            val storePath = dir.file("kill.kvid")
            val marker = dir.file("marker")
            val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
            val classpath = System.getProperty("java.class.path")
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(java, "-cp", classpath, "com.kvid.store.KillTestChildKt", storePath, marker)
                    .redirectErrorStream(true).start()
            }
            withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 90_000
                while (!File(marker).exists()) {
                    if (!process.isAlive) fail("child exited before staging: ${process.inputStream.bufferedReader().readText()}")
                    if (System.currentTimeMillis() > deadline) { process.destroyForcibly(); fail("child did not reach the staged state in time") }
                    Thread.sleep(50)
                }
                process.destroyForcibly()
                process.waitFor()
            }
            val reopened = Kvid.open(storePath)
            assertEquals(listOf("committed 2", "committed 1"), reopened.list().items.map { it.body })
            assertEquals(2L, reopened.stats().commitSeq)
            assertTrue(reopened.verify().ok, reopened.verify().problems.joinToString())
            reopened.close()
            assertEquals(listOf("kill.kvid", "marker"), dir.listNames(), "the hot journal was rolled back and removed")
        } finally {
            dir.cleanup()
        }
    }
}
