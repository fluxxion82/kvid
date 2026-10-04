package com.kvid.store

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.open

internal actual object FileSync {
    @OptIn(ExperimentalForeignApi::class)
    actual fun syncDirectory(path: String): Boolean {
        val fd = open(path, O_RDONLY)
        if (fd < 0) return false
        val ok = fsync(fd) == 0
        close(fd)
        return ok
    }
}

internal actual object PlatformInfo {
    actual val name: String = "ios"
    actual val storeSupported: Boolean = true
}
