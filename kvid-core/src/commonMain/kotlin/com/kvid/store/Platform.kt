package com.kvid.store

/**
 * The only platform-specific pieces of the store (persistence contract, section 7).
 * Everything else, including journaling, locking and recovery, is SQLite's.
 */
internal expect object FileSync {
    /** Flushes a directory's metadata so a completed rename is durable. Returns false where unsupported. */
    fun syncDirectory(path: String): Boolean
}

internal expect object PlatformInfo {
    val name: String
    /** False on the Android host-JVM unit-test runtime, where the bundled driver's native library is not loadable. */
    val storeSupported: Boolean
}
