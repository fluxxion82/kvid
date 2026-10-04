package com.kvid.store

/**
 * Failures of the kvid document store. Every subclass carries a stable [code] string for logging and
 * for non-Kotlin consumers. Causes are preserved. `CancellationException` is never wrapped.
 */
sealed class KvidException(val code: String, message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** The underlying storage reported that the device is full. The transaction was rolled back. */
    class DiskFull(message: String, cause: Throwable? = null) : KvidException("KV_DISK_FULL", message, cause)
    /** An I/O or sync failure, or any storage failure kvid does not classify more precisely. */
    class Io(message: String, cause: Throwable? = null) : KvidException("KV_IO", message, cause)
    /** Another handle holds the write lock, or the same path is already open for writing in this process. */
    class Locked(message: String, cause: Throwable? = null) : KvidException("KV_LOCKED", message, cause)
    /** A write was attempted through a read-only handle. */
    class ReadOnly(message: String, cause: Throwable? = null) : KvidException("KV_READ_ONLY", message, cause)
    /** The file is not a kvid store (not SQLite, or SQLite without kvid's application id and metadata). */
    class NotAStore(message: String, cause: Throwable? = null) : KvidException("KV_NOT_A_STORE", message, cause)
    /** The store's format version is newer than this library supports. */
    class UnsupportedFormat(message: String, val fileMajor: Int, val fileMinor: Int) : KvidException("KV_UNSUPPORTED_FORMAT", message)
    /** A recognised store whose contents fail integrity or invariant checks. */
    class Corrupt(message: String, cause: Throwable? = null) : KvidException("KV_CORRUPT", message, cause)
    /** Input exceeds a configured bound. Raised before anything is written. */
    class LimitExceeded(message: String) : KvidException("KV_LIMIT_EXCEEDED", message)
    /** The requested as-of sequence is below the store's history floor. */
    class HistoryUnavailable(message: String) : KvidException("KV_HISTORY_UNAVAILABLE", message)
    /** The cursor was issued before a write or compaction and is no longer valid. */
    class CursorExpired(message: String) : KvidException("KV_CURSOR_EXPIRED", message)
    /** The document does not exist (or is deleted, for operations that need a live document). */
    class NotFound(message: String) : KvidException("KV_NOT_FOUND", message)
    /** A file or document that must not exist already does. */
    class AlreadyExists(message: String) : KvidException("KV_ALREADY_EXISTS", message)
    /** The store or session has been closed. */
    class Closed(message: String) : KvidException("KV_CLOSED", message)
    /** The API was used outside its contract (reentry, session escaping its scope, bad argument). */
    class Usage(message: String) : KvidException("KV_USAGE", message)
}
