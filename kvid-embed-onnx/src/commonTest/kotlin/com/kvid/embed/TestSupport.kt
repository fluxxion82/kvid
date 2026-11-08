package com.kvid.embed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.test.fail

// Platform helpers of the embedding tests. JVM: src/jvmTest; Android: src/androidDeviceTest; iOS: src/iosTest.

/** The fixture file at [path] as a readable local path, or null when there is none. */
internal expect fun fixtureFile(path: String): Path?

/** Names the platform in `[kvid-embedding*]` lines. */
internal expect val testPlatform: String

/** What [memoryKb] measures, for `mem_kind=`: `pss` on Android, `footprint` (phys_footprint) on iOS. */
internal expect val memoryKind: String

/** This process's memory in KiB as [memoryKind] describes it, or null where it is not measured (the JVM). */
internal expect fun memoryKb(): Long?

/**
 * A blocking count-down latch for code that runs on a native thread and cannot suspend, such as
 * [BackendHooks.insideRun]: `CountDownLatch` on the JVM and Android, `NSCondition` on iOS.
 */
internal expect class TestLatch(count: Int) {
    fun countDown()

    /** Waits at most [timeout] for the count to reach zero; false when it did not. */
    fun await(timeout: Duration): Boolean
}

internal fun Path.readText(): String = SystemFileSystem.source(this).buffered().use { it.readString() }

internal fun Path.writeText(text: String) = SystemFileSystem.sink(this).buffered().use { it.writeString(text) }

/** A new empty directory under the system temporary directory. */
internal fun createTempDirectory(prefix: String): Path {
    val dir = Path(SystemTemporaryDirectory, "$prefix-${Random.nextLong().toULong().toString(16)}")
    SystemFileSystem.createDirectories(dir, mustCreate = true)
    return dir
}

internal fun Path.deleteRecursively() {
    val metadata = SystemFileSystem.metadataOrNull(this) ?: return
    if (metadata.isDirectory) SystemFileSystem.list(this).forEach { it.deleteRecursively() }
    SystemFileSystem.delete(this, mustExist = false)
}

/** Milliseconds [block] took, on the monotonic clock. */
internal inline fun timedMs(block: () -> Unit): Double {
    val mark = TimeSource.Monotonic.markNow()
    block()
    return mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
}

/**
 * Runs [block] with a real-time bound, on [Dispatchers.Default]: inside `runTest`, a plain `withTimeout`
 * runs on virtual time and would expire at once while the test waits for another thread.
 */
internal suspend fun <T> withRealTimeout(timeout: Duration, what: String, block: suspend CoroutineScope.() -> T): T =
    withContext(Dispatchers.Default) {
        try {
            withTimeout(timeout) { block() }
        } catch (e: TimeoutCancellationException) {
            fail("$what did not finish within $timeout")
        }
    }

/**
 * Formats [value] like `String.format(Locale.ROOT, format, value)` for the two conversions the log lines
 * use, `%.Nf` and `%.Ne`, with a dot as the decimal separator on every platform and locale. Rounding is
 * half up on the double's value, which can differ from Java only in the last digit of an exact tie.
 */
internal fun fmt(format: String, value: Double): String {
    val match = Regex("%\\.(\\d+)([fe])").matchEntire(format) ?: error("unsupported format '$format'")
    val digits = match.groupValues[1].toInt()
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    return if (match.groupValues[2] == "f") fixed(value, digits) else scientific(value, digits)
}

private fun fixed(value: Double, digits: Int): String {
    val factor = 10.0.pow(digits)
    val scaled = floor(abs(value) * factor + 0.5)
    if (scaled >= 9.0e18) return value.toString()
    val units = scaled.toLong()
    val unit = 10.0.pow(digits).toLong()
    val sign = if (value < 0 && units != 0L) "-" else ""
    val whole = units / unit
    return if (digits == 0) "$sign$whole" else "$sign$whole." + (units % unit).toString().padStart(digits, '0')
}

private fun scientific(value: Double, digits: Int): String {
    val sign = if (value < 0) "-" else ""
    val magnitude = abs(value)
    if (magnitude == 0.0) return "${sign}0" + (if (digits > 0) "." + "0".repeat(digits) else "") + "e+00"
    var exponent = floor(log10(magnitude)).toInt()
    var mantissa = magnitude / 10.0.pow(exponent)
    if (mantissa < 1.0) { mantissa *= 10; exponent-- }
    if (mantissa >= 10.0) { mantissa /= 10; exponent++ }
    var scaled = floor(mantissa * 10.0.pow(digits) + 0.5).toLong()
    if (scaled >= 10.0.pow(digits + 1).toLong()) { scaled /= 10; exponent++ }
    val text = scaled.toString()
    val body = if (digits == 0) text else text[0] + "." + text.substring(1)
    val exp = (if (exponent < 0) "-" else "+") + abs(exponent).toString().padStart(2, '0')
    return "$sign${body}e$exp"
}
