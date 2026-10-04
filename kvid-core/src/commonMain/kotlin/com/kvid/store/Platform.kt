package com.kvid.store

import kotlinx.coroutines.CoroutineDispatcher

/** Blocking-I/O dispatcher; `Dispatchers.IO` is not visible from common code. */
internal expect val ioDispatcher: CoroutineDispatcher

/**
 * The only platform-specific pieces of the store (persistence contract, section 7).
 * Everything else, including journaling, locking and recovery, is SQLite's.
 */
internal expect object FileSync {
    /** Reserve a new database path atomically; never truncate an existing file. */
    fun createExclusive(path: String)
    /** Atomically publish without replacing an existing destination. Both paths are on one filesystem. Android requires an app-owned directory with no non-kvid writers. */
    fun publishNoReplace(source: String, destination: String)

    /** Flushes a directory's metadata so a completed rename is durable. Returns false where unsupported. */
    fun syncDirectory(path: String): Boolean
}

internal expect object PlatformInfo {
    val name: String
    /** False on the Android host-JVM unit-test runtime, where the bundled driver's native library is not loadable. */
    val storeSupported: Boolean
}

/**
 * Unicode NFC normalization. kvid stores titles, bodies and tags in NFC and normalizes queries and tag
 * filters the same way, so canonically equivalent text indexes, matches and filters identically
 * (persistence contract, section 7). Uris and metadata are stored as given.
 */
internal expect fun normalizeNfc(text: String): String
