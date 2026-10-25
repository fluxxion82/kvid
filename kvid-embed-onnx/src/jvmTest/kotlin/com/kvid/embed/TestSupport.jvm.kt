package com.kvid.embed

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

// The JVM side of the helpers in commonTest's TestSupport.kt.

internal actual fun fixtureFile(path: String): Path? =
    Path(path).takeIf { SystemFileSystem.metadataOrNull(it)?.isRegularFile == true }

internal actual val testPlatform: String =
    "jvm/${System.getProperty("os.name")}/${System.getProperty("os.arch")}/java-${System.getProperty("java.version")}".replace(' ', '_')

internal actual val memoryKind: String = "none"

/** Not measured on the JVM. */
internal actual fun memoryKb(): Long? = null

internal actual class TestLatch actual constructor(count: Int) {
    private val latch = CountDownLatch(count)

    actual fun countDown() = latch.countDown()

    actual fun await(timeout: Duration): Boolean = latch.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
}
