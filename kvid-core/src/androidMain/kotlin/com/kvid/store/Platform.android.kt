package com.kvid.store

import android.system.Os
import android.system.OsConstants

internal actual object FileSync {
    /** Uses android.system.Os (API 21+): java.nio.file is unavailable below API 26. */
    actual fun syncDirectory(path: String): Boolean = try {
        val fd = Os.open(path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd); true } finally { Os.close(fd) }
    } catch (e: Exception) {
        false
    }
}

internal actual object PlatformInfo {
    actual val name: String = "android"
    // Host-JVM unit tests (androidHostTest) run on a desktop VM without the bundled driver's Android natives.
    actual val storeSupported: Boolean = System.getProperty("java.vm.name")?.contains("Dalvik") == true
}
