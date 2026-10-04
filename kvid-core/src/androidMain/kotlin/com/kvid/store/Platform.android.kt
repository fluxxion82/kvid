package com.kvid.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import android.system.Os
import android.system.OsConstants

internal actual object FileSync {
    actual fun createExclusive(path: String) {
        try { Os.close(Os.open(path, OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_WRONLY, 384)) }
        catch (e: android.system.ErrnoException) {
            if (e.errno == OsConstants.EEXIST) throw KvidException.AlreadyExists("store already exists: $path")
            throw KvidException.Io("cannot reserve store: $path", e)
        }
    }
    actual fun publishNoReplace(source: String, destination: String) {
        // Android app SELinux policy rejects hard links. Serialize cooperating publishers across
        // processes with an exclusive directory, then check and rename within that reservation.
        // The caller must own the destination directory against non-kvid writers.
        val target = kotlinx.io.files.Path(destination)
        val reservation = kotlinx.io.files.Path(target.parent!!, ".${target.name}.kvid-publish-lock").toString()
        try { Os.mkdir(reservation, 448) }
        catch (e: android.system.ErrnoException) {
            if (e.errno == OsConstants.EEXIST) throw KvidException.Locked("snapshot publication reserved: $destination; inspect stale reservation before retrying")
            throw KvidException.Io("cannot reserve snapshot destination: $destination", e)
        }
        var failure: Throwable? = null
        var published = false
        try {
            if (kotlinx.io.files.SystemFileSystem.exists(target)) throw KvidException.AlreadyExists("snapshot destination exists: $destination")
            try { Os.rename(source, destination); published = true }
            catch (e: android.system.ErrnoException) { throw KvidException.Io("cannot publish snapshot: $destination", e) }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            try { kotlinx.io.files.SystemFileSystem.delete(kotlinx.io.files.Path(reservation)) }
            catch (cleanup: Throwable) {
                val original = failure
                if (original != null) original.addSuppressed(cleanup)
                else throw KvidException.Io("snapshot ${if (published) "published" else "not published"}, but reservation cleanup failed: $destination", cleanup)
            }
        }
    }
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

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual fun normalizeNfc(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
