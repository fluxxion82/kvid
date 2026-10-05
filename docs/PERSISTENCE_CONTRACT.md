# kvid persistence contract

_Status: **Proposed**, version 0.1. Revised after Phase 1 review. This is the specification for Milestone 2, not an implemented API. The spike verifies selected SQLite primitives; the test matrix below remains required for the real store. See [ADR 0001](adr/0001-storage-engine.md)._

## 1. Terms

Creation must reserve a new file exclusively; opening writable must not recreate a missing file or modify an unsupported format before validation. A store is a SQLite application database, conventionally `name.kvid`. A document has a stable `documentId`; immutable versions have store-wide `versionId` values. The current version is the newest version **including tombstones**: if it is a tombstone, the document is absent. Derived chunks and vectors are rebuildable, not authoritative.

A commit sequence (`seq`) increments once per committed write transaction. Versions in the same transaction share a sequence; order them by `(seq, versionId)`. Event time is supplied by the application, defaulting to put time. Commit time is informational and does not define visibility.

## 2. Portable files and snapshots

Default journal mode is `DELETE`. A write may create a `-journal` file. After successful clean close of all coordinated handles, with no other process writing, the database is one portable file. Copying an open writable database is unsupported; use `snapshot()`.

WAL is deferred from the public 0.1 API. A checkpoint and switch to `DELETE` cannot guarantee sidecar removal with an active reader or another process using the file. The spike tests both successful cleanup and a reader preventing the switch.

A snapshot contains committed state only. Use a fresh read connection, rather than the writer connection with staged work, and bind the output pathname. `VACUUM INTO` creates a consistent database but does **not** atomically publish a destination: interruption can leave incomplete output. Export to an owned temporary path on the destination filesystem, validate the database and kvid invariants, sync it, atomically publish without replacement (a hard link followed by temporary-link removal is valid), and sync the parent directory where supported. Reject an existing destination in 0.1. Return only after publication succeeds. Platform adapters must establish their publication and sync guarantees. Android currently coordinates publishers with a per-destination directory reservation and atomic rename, because app SELinux rejects hard links; callers must own that destination directory against non-kvid writers. Stale reservations require inspection before manual removal. General Android no-replace publication without that ownership precondition requires a later native adapter.

Reject snapshot and close calls from inside a managed transaction. Clean up only temporary files owned by this operation; never broadly delete matching files on open. A failure before rename must leave no published destination and must preserve the source. A sync failure after rename can leave a published file with uncertain durability; report that outcome and verify the owned destination before retrying.

## 3. Transactions and durability

Every writable connection explicitly configures and verifies `foreign_keys = ON`, `journal_mode = DELETE`, and `synchronous = EXTRA` (3). SQLite's bundled defaults are insufficient for this contract: DELETE/FULL omits a directory sync after journal unlink. A future WAL mode must use FULL (2) or EXTRA (3); WAL/NORMAL can lose recent commits after power loss. These guarantees depend on the filesystem and storage honoring sync requests. SQLite's default Apple VFS does not imply `fullfsync = ON`.

Outside `transaction { }`, each write commits before returning. Inside it, writes stage work; normal block completion commits, and an exception rolls back. There is no separately callable `commit()` that could prematurely commit a managed block. IDs returned inside the block are provisional until commit succeeds.

The transaction receiver is an explicit scoped session. Hold the store's operation gate for the block and route session reads/writes directly to its connection; reacquiring a non-reentrant Mutex would deadlock. Reject reentry through the enclosing store or any active ancestor store, use after the session ends, and sharing the session with child coroutines. Nested session transactions use savepoints. Each write is individually savepoint-isolated so a caught write failure cannot leave partial changes; failed savepoint cleanup makes the outer session rollback-only. Reads through the session see staged writes.

Check cancellation before commit. On cancellation or failure, perform rollback and connection cleanup in a non-cancellable context on the connection dispatcher, then propagate the original exception; retain cleanup failures as suppressed causes. Cancellation after commit cannot undo it. Blocking SQLite calls need not stop immediately on cancellation; this bundled build omits the progress callback.

`suspend close()` serializes with operations, waits for an active managed transaction to finish, and closes resources. It does not implement synchronous `AutoCloseable`. Roll back residual uncommitted work during failure cleanup; never implicitly commit it.

## 4. Recovery, validation, and compatibility

SQLite owns transaction journal recovery. kvid validates application identity and metadata, uses `quick_check` after a detected unclean shutdown, and exposes `verify()` with `integrity_check`, foreign-key checks, current-version/tombstone invariants, and FTS external-content consistency (`integrity-check` with `rank = 1`). Read-only verification explicitly reports FTS coverage in `VerifyReport.unchecked`; `ok` describes performed checks, not completeness. Public multi-statement reads and exports use one read transaction, including page payloads and cursor metadata. A clean-close marker is advisory, particularly across processes.

SQLite does not inspect every page on every read. An invalid header is not evidence that arbitrary corruption can never return readable rows. Detected corruption must surface explicitly; unaccessed corruption requires verification. Refuse non-kvid databases as `NotAStore`, and damaged recognized stores as `Corrupt`; document ambiguous invalid-header classification.

`user_version` and a `kvid_meta` row identify schema and format versions. Refuse unsupported newer major **or minor** versions for writable opens. Read-only compatibility with newer formats requires an explicit supported capability set, not merely ignoring columns. Run each forward migration and its version update transactionally; a failed migration leaves the previous schema intact.

## 5. Concurrency and errors

Coordinate writable handles by canonical path within a process, with one serialized connection owner. Run blocking calls on an appropriate connection dispatcher; never use a connection concurrently. Read-only handles use `SQLITE_OPEN_READONLY` and see committed state at the start of their read transaction. An existing read transaction continues seeing its old snapshot after a write commits.

SQLite serializes write transactions across processes, not writable opens: a second process may open successfully and subsequently contend at BEGIN or a write. Configure a busy timeout (default 5 seconds); it is a contention policy, not a strict end-to-end elapsed-time bound. DELETE-mode readers can block writer commits; WAL has different reader/writer behavior and remains deferred.

Use typed `KvidException` subclasses with stable kvid code strings, preserving underlying causes. Proposed mappings: `DiskFull`, `Io`, `Locked`, `NotAStore`, `UnsupportedFormat`, `Corrupt`, `LimitExceeded`, `HistoryUnavailable`, `CursorExpired`, and `InvalidQuery` (an opt-in FTS5 expression the engine rejected). Cancellation propagates unchanged. Bounds fail before writes; lock failures roll back staged work. For FULL, explicitly clean up and verify rollback, as the spike does for its injected case.

An I/O or sync failure can leave the commit outcome uncertain. Mark the connection unusable, reopen/recover, and resolve through a durable operation identifier before retrying; do not promise that every failed commit leaves precisely the previous state. SQLite atomicity and certainty of the caller's observed outcome are different guarantees.

**Implementation gate:** androidx.sqlite 2.7.1 `SQLiteException` exposes a message, not a structured result code. Spike assertions inspect the pinned driver's message only. Milestone 2 must establish a supported error-code adapter or explicitly tested, version-pinned translation with unknown failures mapped to `Io`; parsing messages is not a stable driver API.

## 6. Documents and retained history

Generate UUIDv7 document identifiers, or accept a unique caller ID. Identifiers support identity but do not implement synchronization/conflict resolution. Use AUTOINCREMENT version IDs: committed IDs are never reused, while rolled-back provisional IDs may be. `uri` is metadata; uniqueness is a store-wide schema option, not a per-insert request that silently changes the index.

Update creates a version and records its predecessor; delete creates a tombstone. At sequence S, select the largest `(seq, versionId)` with `seq <= S`. A selected tombstone means absent. `get(id, asOfSeq)` and history support retained versions, including multiple updates in one transaction.

`KEEP_ALL` retains all versions. `KEEP_LATEST` compaction retains the latest live version or a deletion marker so old content cannot resurrect. Store an explicit global history floor; reject as-of requests below it, even for deleted documents. Pruned predecessor references must remain representable without dangling foreign keys. Apply retention transactionally before SQLite VACUUM. A failure during later physical shrinking does not undo already committed retention.

## 7. Search and pagination

Version 0.1 searches **current live versions only**. Historical full-text search is deferred: a current-only index cannot retrieve superseded text by filtering `asOfSeq`. Reject unsupported historical search options. Historical `get` and history remain supported.

Index a current-content projection distinct from immutable version history, updating it and its external-content FTS triggers in the same transaction. Rebuild only that projection. The projection holds ids, event time and uri; the FTS index reads title and body through a view over the immutable current versions, so live text is stored once (schema 2). A version never changes while it is current, which keeps the text supplied to FTS delete commands exact; drift between the index and authoritative text is detected by the FTS integrity check. Initially index entire versions; define chunk-to-document aggregation before adding chunked search. SQLite BM25 scores are lower-is-better; expose their negation if the public API uses descending score order.

**Queries.** `find` takes plain text by default (`QuerySyntax.PLAIN`). The query is split on Unicode whitespace; each piece must occur in the title or body as the adjacent tokens it contains (`state-of-the-art` is one piece). Operators, quotes, parentheses, `*` and column filters are ordinary text. Embedded NUL characters are token separators within their piece. Pieces without a letter or digit are ignored; a query without searchable pieces matches nothing and is not an error. `MatchMode.ALL` (default) requires every piece, `MatchMode.ANY` at least one. Pieces are deduplicated case-insensitively and bounded to 128. `prefixLastTerm` (off by default) makes the last searchable piece a prefix phrase, so its final token also matches indexed tokens that start with it, after the same folding; every other piece still matches whole tokens, and cursors bind to the option. Raw FTS5 syntax (phrases, prefixes, `AND`/`OR`/`NOT`, `NEAR`, column filters) is opt-in through `QuerySyntax.FTS5`; an empty or rejected expression raises `InvalidQuery`. Plain queries never raise it.

**Text normalization and tokenization.** Titles, bodies and tags are stored in Unicode NFC; queries and tag filters are normalized the same way. Uris and metadata are stored as given. The index uses FTS5 `unicode61` with its defaults: case folding, diacritic removal, and letters, digits and private-use characters as token characters. There is no stemming. Classifying supplementary-plane characters in plain queries approximates SQLite's tables; a piece made only of characters SQLite does not tokenize makes an `ALL` query match nothing.

The serialized query/filter identity has a combined UTF-8 budget of 256 KiB (including JSON escaping); exceeding it raises `LimitExceeded` before executing a nonempty search or list query. Pagination cursors contain its exact hexadecimal encoding and accept that entire budget plus their metadata. Individual input limits still apply.

**Filters.** `list` and `find` share one filter set, and every supplied filter must hold: event-time range `[since, until)`, every listed tag on the current version (normalized, deduplicated, bounded like write tags), and a uri prefix compared literally and case-sensitively (an empty prefix selects documents that have a uri).

List by `(eventTime desc, versionId desc)` and search by `(score desc, versionId desc)`. Scores are FTS5 `bm25()` negated: k1 1.2, b 0.75, unit column weights, document length summed over title and body, IDF floored at 1e-6. Event-time filters use event time. Cursors encode the last key, committed sequence, retention/schema epoch, and query/filter fingerprint (syntax, match mode, compiled query, page size and every filter). Invalidate them with `CursorExpired` after a committed write or compaction. Ranking and current visibility change after writes, so cross-commit no-skip/no-duplicate guarantees require a retained read snapshot and are deferred. Cursors are opaque and versioned.

## 8. Bounds and encoding

Default write limits: body 16 MiB UTF-8, metadata JSON 64 KiB UTF-8, title 1 KiB UTF-8, 256 tags of at most 128 Unicode code points, attachment 64 MiB, and 10,000 derived chunks. Bound identifiers, queries (4 KiB UTF-8 by default, 128 plain-query terms), filters, page sizes and stored metadata as well. Configuration has hard library caps and cannot trust limits read from an arbitrary database.

Validate stored byte lengths before materializing large TEXT/BLOB fields; SQLite's much larger internal limits do not enforce these application bounds. Bound decompressed output and expansion before allocation. Test malformed lengths and compression bombs.

Bodies are uncompressed UTF-8 TEXT. Optional blob compression uses zlib-wrapped DEFLATE (RFC 1950), with explicit raw/deflate encoding. Cross-platform fixture tests remain required. This does not repair the independent video's existing `GZ:` gzip/raw-DEFLATE mismatch.

Vectors use little-endian float32 blobs and include model/tokenizer revisions, dimensions, pooling, normalization and metric. Validate dimensions and reject non-finite values for retrieval; codec preservation of NaN payloads is a wire-format test, not permission to index NaNs. Model mismatch may disable vectors or require re-embedding; lexical access remains available.

## 9. Encryption reservation

0.1 databases are plaintext. Reserved encryption metadata does not guarantee that encryption can be introduced as a compatible minor change. Design authenticated coverage, nonce lifecycle, keys, rotation and migration separately.

Encrypted snapshot export protects the exported artifact; the open database, journals and decrypted working files remain plaintext unless an encrypted database implementation is selected. Per-row encryption also requires a policy for plaintext indexes and metadata leakage. Integrity checks and hashes do not authenticate content.

## 10. Proposed API

```kotlin
class Kvid private constructor(...) {
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
    suspend fun <T> transaction(block: suspend Transaction.() -> T): T
    suspend fun snapshot(destination: String)
    suspend fun verify(): VerifyReport
    suspend fun vacuum(retention: Retention = Retention.KEEP_ALL)
    suspend fun stats(): StoreStats
    suspend fun close()
}
```

`Transaction` exposes scoped writes, reads and nested transaction blocks only. Snapshot, vacuum and close are outside its surface. Types and defaults remain proposed; the real implementation must test ownership and lifecycle before publishing this API.

## 11. Required production test matrix

- Every target: close/reopen, copy closed artifact, Android device runtime/native loading, portable cross-platform fixtures.
- Managed transactions: staged reads, exception/cancellation rollback, nested savepoints, forbidden reentry and child sharing, close races, cancellation at commit boundary.
- Recovery: actual subprocess termination mid-transaction and around commit; distinguish this from orderly connection close. Inject truncation, page damage, FULL, failed sync and ambiguous commit outcomes.
- Snapshots: active staged writer, genuine read-only source, interrupted export, existing destination, atomic publication and sync failure; verify original and exported state.
- Identity/history: multiple updates in one transaction, tombstones, committed ID reuse prevention, rollback IDs, retention floor and predecessor pruning.
- Search: current projection after update/delete/recovery, external-content integrity, rebuild equivalence, score order against an independent BM25 calculation, tie order, plain-query literalness, opt-in FTS5 rejection, Unicode normalization and folding, filter combinations, cursor binding to query and filters, cursor expiration after writes/compaction.
- Bounds: oversized input and stored lengths, decompression bombs, malformed vectors; migrations and newer minor/major rejection.
- Platform durability and mobile performance: device tests, binary footprint, cold open, peak memory and representative corpus. Process tests and injected faults do not prove every power-loss scenario.

## References

- [SQLite synchronous modes](https://www.sqlite.org/pragma.html#pragma_synchronous) and [fullfsync](https://www.sqlite.org/pragma.html#pragma_fullfsync).
- [VACUUM INTO](https://www.sqlite.org/lang_vacuum.html): consistent output and interruption behavior.
- [Isolation](https://www.sqlite.org/isolation.html), [AUTOINCREMENT](https://www.sqlite.org/autoinc.html), and [FTS5 integrity checks](https://www.sqlite.org/fts5.html#the_integrity_check_command).
