package com.kvid.embed

import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

// The Android side of the helpers jvmAndAndroidTest uses; jvmTest has its own with the same names.

private val copies = HashMap<String, File>()

/**
 * The fixture file at [path] (pushed under /data/local/tmp), or null when there is none. Where the app
 * may not read /data/local/tmp, the file is copied into the test's cache directory with a shell `cat`,
 * which runs as the shell user. Pushing into the app's own storage does not work: the connected test
 * task installs and uninstalls the APK around the run.
 */
@Synchronized
internal fun fixtureFile(path: String): File? {
    val file = File(path)
    if (runCatching { file.inputStream().use { it.read() } }.isSuccess) return file
    copies[path]?.let { return it }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
    if (shell("ls $path").use { it.readBytes().decodeToString().trim() } != path) return null
    val copy = File(instrumentation.targetContext.cacheDir, "kvid-fixture/" + path.trimStart('/').replace('/', '_'))
    copy.parentFile!!.mkdirs()
    shell("cat $path").use { input -> copy.outputStream().use { input.copyTo(it, 1 shl 20) } }
    println("[kvid] copied $path (${copy.length()} bytes) into the test cache: the app cannot read it directly")
    copies[path] = copy
    return copy
}

/** Names the platform in `[kvid-embedding]` lines. */
internal val testPlatform: String =
    "android-${Build.VERSION.SDK_INT}/${Build.MODEL}/${Build.SUPPORTED_ABIS.firstOrNull()}".replace(' ', '_')

/** Proportional set size of this process in KiB. */
internal fun pssKb(): Long? = Debug.getPss()
