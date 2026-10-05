package com.kvid.sample

import com.kvid.store.Kvid
import com.kvid.store.StoreOptions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Owns the app's single writable [Kvid] handle for one path, following the store's lifecycle rules:
 * one session per path per process, held at application scope.
 *
 * The store opens on first [use]. [closeWhenIdle] is meant for the app moving to the background: it
 * closes the store at once if nothing is running, or when the last running [use] finishes, so a
 * process killed in the background leaves a cleanly closed file. The next [use] reopens it, and a
 * [use] that starts before a deferred close happens cancels that close.
 */
class StoreSession(
    private val path: String,
    private val options: StoreOptions = StoreOptions(),
    /** Runs once, on the handle that created a new store file. */
    private val onCreate: suspend (Kvid) -> Unit = {}
) {
    private val mutex = Mutex()
    private var store: Kvid? = null
    private var leases = 0
    private var closeRequested = false

    /** Whether a handle is currently open. */
    suspend fun isOpen(): Boolean = mutex.withLock { store != null }

    suspend fun <T> use(block: suspend (Kvid) -> T): T {
        val current = mutex.withLock {
            closeRequested = false
            val opened = store ?: openOrCreate().also { store = it }
            leases++
            opened
        }
        try {
            return block(current)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    leases--
                    if (leases == 0 && closeRequested) closeNow()
                }
            }
        }
    }

    /** Closes the store when no [use] is running; returns after closing or after scheduling the close. */
    suspend fun closeWhenIdle() {
        withContext(NonCancellable) {
            mutex.withLock {
                closeRequested = leases > 0
                if (!closeRequested) closeNow()
            }
        }
    }

    /**
     * Writes a consistent, verified copy of the committed store into the `backups` directory next to it,
     * named by the current time, and returns its path. Copying the live file is not a backup.
     */
    @OptIn(ExperimentalTime::class)
    suspend fun backup(): String = use { store ->
        val directory = Path(Path(path).parent ?: Path("."), "backups")
        SystemFileSystem.createDirectories(directory)
        val destination = Path(directory, "notes-${Clock.System.now().toEpochMilliseconds()}.kvid").toString()
        store.snapshot(destination)
        destination
    }

    private suspend fun closeNow() {
        val open = store
        store = null
        closeRequested = false
        open?.close()
    }

    private suspend fun openOrCreate(): Kvid {
        if (SystemFileSystem.exists(Path(path))) return Kvid.open(path, options)
        Path(path).parent?.let { SystemFileSystem.createDirectories(it) }
        val created = Kvid.create(path, options)
        try {
            onCreate(created)
        } catch (t: Throwable) {
            created.close()
            throw t
        }
        return created
    }
}
