# kvid persistence contract

_Status: **Proposed**, version 0.1, October 2026. This document is the contract that Milestone 2 implements and tests against. It is written for the storage decision in [ADR 0001](adr/0001-storage-engine.md) (SQLite through the androidx.sqlite bundled driver). Where a rule would change under the fallback engine (append-only log), the rule says so. Every "must" here is a test to write._

## 1. Terms

| Term | Meaning |
|---|---|
| **Store** | One kvid database. On disk it is one SQLite file, by convention `name.kvid`. |
| **Document** | A logical record identified by a stable `documentId`. A document has one or more versions. |
| **Version** | An immutable snapshot of a document's content and metadata, identified by a store-wide monotonic `versionId`. Updates create versions; they never modify one. |
| **Current version** | The newest non-tombstone version of a document. |
| **Tombstone** | A version that marks the document deleted. |
| **Chunk** | A derived slice of a version's text used by search or embeddings. Chunks have their own ids and are never authoritative. |
| **Commit sequence (`seq`)** | A store-wide counter incremented once per committed write transaction. Every version records the `seq` it was committed in. |
| **Event time** | The application-meaningful timestamp of a version (when the note was written, when the message was sent). Supplied by the caller; defaults to the wall clock at `put` time. |
| **Commit time** | Wall-clock time at commit. Informational only. |
| **Snapshot** | A single-file copy of the store's committed state, produced while the store is open or closed. |

## 2. What "single file" means

- **Portable artifact.** After `close()` returns, or after `snapshot()` returns, the store is exactly one file that can be copied, attached, backed up, or opened elsewhere. This is the guarantee the project is built around.
- **While open.** In the default journal mode (`DELETE`), SQLite creates a `-journal` file next to the store only for the duration of a write transaction and removes it at commit or rollback. In `WAL` mode (opt-in, for write-heavy workloads) `-wal` and `-shm` files exist while the store is open; kvid checkpoints and removes them in `close()` by switching the journal mode back to `DELETE`. No kvid API relies on sidecars surviving `close()`.
- **Temporary files.** `vacuum()` and `snapshot()` may create temporary files in the store's directory or the platform temporary directory. They are removed on completion or on the next `open()`.
- **Copying a live store** (for example by a sync client or a backup agent) while a write transaction is in flight is **unsupported** and may yield an unopenable copy. Apps that need a consistent copy call `snapshot()`, which is safe while open. This is documented behaviour, not a bug.

Under the fallback engine, the "while open" rule becomes: the store is always one file; the write-ahead region lives inside it.

## 3. Durability boundary

- `put`, `update`, `delete` **stage** work inside the current transaction. They promise nothing about durability.
- `commit()` returns only after SQLite has completed its atomic commit with `PRAGMA synchronous = FULL` (`NORMAL` under WAL, where the WAL is synced at commit). After `commit()` returns, the committed state survives process death and power loss to the extent the platform's `fsync` is honest. On iOS the store additionally issues `F_FULLFSYNC`-equivalent behaviour through SQLite's default VFS; this is documented as SQLite's guarantee, not kvid's.
- **Auto-commit mode (default).** When no explicit transaction is open, each write call is its own transaction and is durable when the call returns.
- **Explicit transactions.** `transaction { }` opens a write transaction, runs the block, commits on normal exit and rolls back on any exception, including `CancellationException`, which is rethrown. Nested calls use SQLite savepoints: an inner failure rolls back to its savepoint and the exception propagates.
- **Read-your-writes.** Inside a transaction, reads observe the transaction's own staged writes.
- **Close with uncommitted work.** `close()` rolls back any open transaction and returns normally. It never commits implicitly. A debug flag may make this throw.

## 4. Recovery and corruption

- Torn writes and interrupted transactions are detected and rolled back by SQLite's journal on the next `open()`. kvid adds nothing here and does not claim to.
- `open()` runs `PRAGMA quick_check` when the file's `kvid_meta.last_clean_close` flag is not set (unclean shutdown), and `verify()` runs `PRAGMA integrity_check` plus kvid's own consistency checks (every document's `current_version_id` exists; every FTS row maps to a version).
- A file whose header is not SQLite, or whose `integrity_check` fails, raises `KvidException.Corrupt`. Reads never silently succeed on a corrupt store.
- A file whose `kvid_meta.format_major` is newer than the library raises `KvidException.UnsupportedFormat`. Older minor versions are migrated forward on open; migrations are forward-only and run inside one transaction.
- Fault injection in tests is deterministic (truncate the file, flip bytes, kill between statements). These tests establish the behaviours above; they do not prove every power-loss scenario and the docs must not say they do.

## 5. Concurrency

- **One writer per store, per process.** A `Kvid` instance owns one SQLite connection. All public operations are `suspend` functions serialised through a `Mutex`; blocking SQLite calls run on `Dispatchers.IO` (JVM, Android) or a dedicated single-thread dispatcher (native).
- **Cross-process.** SQLite's file locking applies. A second process opening the same file for writing receives `KvidException.Locked` after `busy_timeout` (default 5 s) rather than blocking indefinitely.
- **Readers.** `openReadOnly()` opens with `SQLITE_OPEN_READONLY` and observes committed state only. Readers in the same process see a write the moment `commit()` returns; a reader mid-query sees a consistent snapshot of the transaction it started in.
- Under WAL mode, readers do not block the writer and the writer does not block readers.

## 6. Failure semantics

| Condition | Behaviour |
|---|---|
| Disk full (`SQLITE_FULL`) | Transaction rolled back; `KvidException.DiskFull`; store remains at last committed state |
| I/O error or failed sync (`SQLITE_IOERR`) | Transaction rolled back; `KvidException.Io` with the SQLite code; store remains at last committed state |
| Cancellation during a transaction | Rolled back; `CancellationException` rethrown unchanged |
| Lock timeout (`SQLITE_BUSY`) | `KvidException.Locked`; nothing written |
| Not a kvid store / wrong magic | `KvidException.NotAStore` |
| Newer format major | `KvidException.UnsupportedFormat` with both versions |
| Corruption | `KvidException.Corrupt` |
| Input over a bound (section 11) | `KvidException.LimitExceeded` before any write |

All `KvidException`s carry a stable `code` string (for example `KV_DISK_FULL`) for logging and for non-Kotlin consumers.

## 7. Platform I/O interface

With SQLite as the engine, kvid does not implement positioned writes, journaling or locking itself. The platform surface it owns is small and is the only `expect`/`actual` in the store:

- `appDataDirectory()` and `temporaryDirectory()` resolution.
- `snapshot(destination)` through `VACUUM INTO`, which produces a complete single file atomically (SQLite writes to the destination and syncs it).
- `delete(path)` and `exists(path)` through kotlinx-io.

Under the fallback engine this interface would grow to positioned read/write, `fsync`, advisory locking and atomic rename, and the contract in sections 3 to 6 would have to be re-proven by kvid's own tests. That cost is the main reason the ADR prefers SQLite.

## 8. Identity, versions and history

- `documentId` is a kvid-generated UUIDv7 string: stable for the document's life, time-ordered, safe to use as a sync identifier. Callers may supply their own id on `put` provided it is unique in the store.
- `versionId` is a store-wide monotonic `Long` (SQLite `INTEGER PRIMARY KEY AUTOINCREMENT`). It never repeats, even after deletion.
- Chunks have their own `chunkId` scoped to a version. They are derived data: deleting and rebuilding them must not change any document or version.
- `uri` is metadata. It may carry a unique index when the app asks for one (`PutOptions(uniqueUri = true)`); it is never an implicit identity rule.

### Visibility

- `update(documentId, …)` inserts a new version with `supersedes = previousVersionId` and sets the document's current version. `delete(documentId)` inserts a tombstone version.
- The version visible at commit sequence `S` is the newest version of the document with `seq <= S`; if that version is a tombstone the document is invisible at `S`. "Now" is `S = latest committed seq`.
- `get(documentId)` returns the current version. `get(documentId, asOfSeq = S)` and `history(documentId)` read older versions when retention keeps them.

### Retention

| Mode | Guarantee |
|---|---|
| `KEEP_ALL` (default) | Every version is retained until `vacuum(retention)` is called with a different mode. `asOf` reads and `history` are complete. |
| `KEEP_LATEST` | Superseded and tombstoned versions may be removed by `vacuum()`. `asOf` reads older than the oldest retained version raise `KvidException.HistoryUnavailable`. |

Keeping history and reclaiming space are different operations: `vacuum()` first applies the retention policy inside a transaction, then runs SQLite `VACUUM` to shrink the file. Interrupting either step leaves the store at its last committed state.

### Time

- Date filters (`since`, `until`) use **event time**. `asOfSeq` uses **commit order**. A query may use both.
- Ordering ties on event time are broken by `versionId` descending. This makes every ordering total and every cursor deterministic.
- `commitTime` is stored for display and audit only; no query semantics depend on it, because device clocks move.

## 9. Search visibility

- Full-text search indexes **current versions only** in version 0.1. A search with `asOfSeq` filters the candidate set to versions visible at `S` but ranks with the current index statistics. This is "historical visibility, not historical ranking" and the API documentation says so. Historical ranking is deferred until a use case needs it.
- Deleted and superseded versions never appear in a search without `asOfSeq`.
- Chunk hits are aggregated to one result per document before ranking is applied; the best chunk's score and snippet represent the document.

## 10. Pagination

- Listing order: `(eventTime desc, versionId desc)`. Search order: `(score desc, versionId desc)`.
- A cursor encodes the last key of the page and the `seq` the page was computed at. Cursors stay valid across commits: the next page continues from the key, and rows committed after the cursor's `seq` may appear or not, which is documented. A cursor never repeats a row and never skips a row that existed at its `seq`.
- Cursors are opaque strings; their format is versioned and may change between kvid versions. Apps must not parse them.

## 11. Bounds

Checked before any write; violating input raises `KvidException.LimitExceeded`.

| Limit | Default | Configurable |
|---|---|---|
| Body size | 16 MiB | yes, up to SQLite's `max_length` |
| Metadata JSON | 64 KiB | yes |
| Tags per version | 256 | yes |
| Tag length | 128 chars | yes |
| Title length | 1 KiB | yes |
| Blob attachment | 64 MiB | yes |
| Chunks per version | 10 000 | yes |

Reading a store never allocates based on an unvalidated length from the file: SQLite enforces its own page and record bounds, and kvid's `kvid_meta` values are parsed with explicit maximum lengths.

## 12. Format evolution

- `PRAGMA user_version` holds the schema version. `kvid_meta` (a one-row table) holds `format_major`, `format_minor`, `created_by`, `created_at`, `last_clean_close`, and the embedding configuration (section 14).
- Unknown tables and columns are ignored by older readers within the same major version; new minor versions only add. A major bump means an older library must refuse the file.
- Migrations are forward-only, idempotent, and run inside one transaction on `open()`. A failed migration rolls back and the file remains openable by the previous library version.

## 13. Compression and encoding

- Document bodies are stored as UTF-8 `TEXT`, uncompressed, because full-text search needs the text and SQLite pages compress well under file-level backup anyway.
- Blob attachments may be compressed. The one portable envelope is **zlib-wrapped DEFLATE (RFC 1950)**, recorded in an `encoding` column (`0` raw, `1` deflate). Gzip and raw DEFLATE are not accepted. A fixture blob committed to the repository must decode to the same bytes on JVM, Android and iOS; this is the test that fixes the current gzip-versus-raw-DEFLATE mismatch in the video pipeline as well.

## 14. Embeddings as derived data

- The store records the full embedding configuration in `kvid_meta`: model id, model revision or hash, tokenizer revision, dimensions, pooling, normalisation and distance metric. A store with no configuration has no vectors.
- Vectors live in a `vectors(chunk_id, embedding BLOB)` table as little-endian `float32`. They are derived: `rebuildVectors()` regenerates them from text; `clearVectors()` drops them.
- Opening a store whose configuration differs from the embedder provided by the app raises `KvidException.ModelMismatch`. The app chooses to re-embed or to open without vectors.

## 15. Encryption reservation

Version 0.1 stores are plaintext. The following is reserved so that adding encryption is a minor version, not a new format:

- `kvid_meta.encryption` (`none` today), `key_id`, and a `nonce BLOB` column on `versions` and `blobs`, unused until encryption is enabled.
- Planned first mode: **whole-snapshot encryption**, where `snapshot(destination, key)` produces an AEAD-encrypted single file and `open` of such a file requires the key. It keeps full-text search working on the open store and protects the file at rest and in transit.
- Planned second mode: per-row AEAD of body and metadata with a unique 96-bit nonce per row and the `key_id` in the header. Full-text search is unavailable in this mode unless the index is also encrypted, which is out of scope. What remains visible without the key: table layout, row counts, sizes, timestamps and ids.
- Checksums (`integrity_check`, content hashes) detect accidental corruption only. Authentication comes from the AEAD tag, not from checksums.

## 16. API surface (version 0.1)

```kotlin
class Kvid private constructor(...) : AutoCloseable {
    companion object {
        suspend fun create(path: String, options: StoreOptions = StoreOptions()): Kvid
        suspend fun open(path: String, options: StoreOptions = StoreOptions()): Kvid
        suspend fun openReadOnly(path: String): Kvid
    }

    suspend fun put(text: String, options: PutOptions = PutOptions()): DocumentId
    suspend fun update(id: DocumentId, text: String, options: PutOptions = PutOptions()): VersionId
    suspend fun delete(id: DocumentId): VersionId
    suspend fun get(id: DocumentId, asOfSeq: Long? = null): Document?
    suspend fun history(id: DocumentId): List<Version>
    suspend fun find(query: String, options: FindOptions = FindOptions()): Page<Hit>
    suspend fun list(options: ListOptions = ListOptions()): Page<Document>
    suspend fun <T> transaction(block: suspend Kvid.() -> T): T
    suspend fun commit()                       // no-op in auto-commit mode
    suspend fun snapshot(destination: String)
    suspend fun verify(): VerifyReport
    suspend fun vacuum(retention: Retention = Retention.KEEP_ALL)
    suspend fun stats(): StoreStats
    override fun close()
}
```

`Result` is not used in this API; failures are `KvidException` subclasses with stable codes (open decision 5 in the roadmap is thereby proposed as "typed exceptions"). `CancellationException` always propagates.

## 17. Test matrix this contract implies

| Rule | Test |
|---|---|
| 2 portable artifact | write, close, list directory: exactly one file; copy, open copy, read |
| 2 WAL sidecars removed on close | open in WAL, write, assert `-wal` exists, close, assert gone |
| 2 snapshot while open | write, `snapshot()`, open snapshot read-only, `integrity_check` ok, counts match |
| 3 staged vs committed | insert without commit, kill connection, reopen: absent; commit: present |
| 3 transaction rollback on exception and on cancellation | block throws / job cancelled: no rows |
| 4 corruption detected | truncate file, flip bytes: `Corrupt`, never a partial read |
| 4 unsupported format | bump `format_major`: `UnsupportedFormat` |
| 5 locked | second connection with a write lock held: `Locked` within timeout |
| 6 disk full | SQLite `max_page_count` set tiny: `DiskFull`, state unchanged |
| 8 visibility | update twice, delete, `asOfSeq` at each seq returns the expected version |
| 8 retention | `KEEP_LATEST` + `vacuum()` removes superseded; `asOf` older raises `HistoryUnavailable` |
| 10 cursor | insert during pagination: no duplicates, no skips of pre-existing rows |
| 11 bounds | oversize body: `LimitExceeded`, no row written |
| 13 envelope | committed deflate fixture decodes identically on all three targets |
| 14 model mismatch | open with a different embedder config: `ModelMismatch` |
