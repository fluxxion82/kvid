package com.kvid.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

internal actual object FileSync {
    actual fun createExclusive(path: String) {
        try { java.nio.file.Files.newByteChannel(Paths.get(path), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { } }
        catch (e: java.nio.file.FileAlreadyExistsException) { throw KvidException.AlreadyExists("store already exists: $path") }
        catch (e: Exception) { throw KvidException.Io("cannot reserve store: $path", e) }
    }
    actual fun publishNoReplace(source: String, destination: String) {
        try { java.nio.file.Files.createLink(Paths.get(destination), Paths.get(source)) }
        catch (e: java.nio.file.FileAlreadyExistsException) { throw KvidException.AlreadyExists("snapshot destination exists: $destination") }
        catch (e: Exception) { throw KvidException.Io("cannot publish snapshot: $destination", e) }
        java.nio.file.Files.delete(Paths.get(source))
    }
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

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual fun normalizeNfc(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
