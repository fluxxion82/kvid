package com.kvid.embed

import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.io.files.Path
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

// The Android side of the helpers in commonTest's TestSupport.kt.

private val copies = HashMap<String, File>()

/**
 * The fixture file at [path] (pushed under /data/local/tmp), or null when there is none. Where the app
 * may not read /data/local/tmp, the file is copied into the test's cache directory with a shell `cat`,
 * which runs as the shell user. Pushing into the app's own storage does not work: the connected test
 * task installs and uninstalls the APK around the run.
 */
@Synchronized
internal actual fun fixtureFile(path: String): Path? {
    val file = File(path)
    if (runCatching { file.inputStream().use { it.read() } }.isSuccess) return Path(file.path)
    copies[path]?.let { return Path(it.path) }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
    if (shell("ls $path").use { it.readBytes().decodeToString().trim() } != path) return null
    val copy = File(instrumentation.targetContext.cacheDir, "kvid-fixture/" + path.trimStart('/').replace('/', '_'))
    copy.parentFile!!.mkdirs()
    shell("cat $path").use { input -> copy.outputStream().use { input.copyTo(it, 1 shl 20) } }
    println("[kvid] copied $path (${copy.length()} bytes) into the test cache: the app cannot read it directly")
    copies[path] = copy
    return Path(copy.path)
}

internal actual val testPlatform: String =
    "android-${Build.VERSION.SDK_INT}/${Build.MODEL}/${Build.SUPPORTED_ABIS.firstOrNull()}".replace(' ', '_')

internal actual val memoryKind: String = "pss"

/** Proportional set size of this process in KiB. */
internal actual fun memoryKb(): Long? = Debug.getPss()

internal actual class TestLatch actual constructor(count: Int) {
    private val latch = CountDownLatch(count)

    actual fun countDown() = latch.countDown()

    actual fun await(timeout: Duration): Boolean = latch.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
}
