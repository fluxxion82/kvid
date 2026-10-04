package com.kvid.store

import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

internal actual object FileSync {
    actual fun syncDirectory(path: String): Boolean = try {
        FileChannel.open(Paths.get(path), StandardOpenOption.READ).use { it.force(true) }
        true
    } catch (e: Exception) {
        false   // directories cannot be opened for fsync on every filesystem (e.g. Windows)
    }
}

internal actual object PlatformInfo {
    actual val name: String = "jvm"
    actual val storeSupported: Boolean = true
}
