package com.kvid.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.open
import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

internal actual object FileSync {
    @OptIn(ExperimentalForeignApi::class)
    actual fun createExclusive(path: String) {
        val fd = open(path, platform.posix.O_CREAT or platform.posix.O_EXCL or platform.posix.O_WRONLY, 384)
        if (fd < 0) {
            if (platform.posix.errno == platform.posix.EEXIST) throw KvidException.AlreadyExists("store already exists: $path")
            throw KvidException.Io("cannot reserve store: $path (errno=${platform.posix.errno})")
        }
        if (close(fd) != 0) throw KvidException.Io("cannot close reserved store: $path")
    }
    @OptIn(ExperimentalForeignApi::class)
    actual fun publishNoReplace(source: String, destination: String) {
        if (platform.posix.link(source, destination) != 0) {
            if (platform.posix.errno == platform.posix.EEXIST) throw KvidException.AlreadyExists("snapshot destination exists: $destination")
            throw KvidException.Io("cannot publish snapshot: $destination (errno=${platform.posix.errno})")
        }
        if (platform.posix.unlink(source) != 0) throw KvidException.Io("snapshot published but temporary link removal failed")
    }
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

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual fun normalizeNfc(text: String): String =
    (text as NSString).precomposedStringWithCanonicalMapping
