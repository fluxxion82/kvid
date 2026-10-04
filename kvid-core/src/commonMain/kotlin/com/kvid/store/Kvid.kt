@file:OptIn(ExperimentalTime::class)

package com.kvid.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import com.kvid.store.Sql.bindLongOrNull
import com.kvid.store.Sql.bindTextOrNull
import com.kvid.store.Sql.exec
import com.kvid.store.Sql.longOrNull
import com.kvid.store.Sql.query
import com.kvid.store.Sql.queryLong
import com.kvid.store.Sql.queryOne
import com.kvid.store.Sql.queryText
import com.kvid.store.Sql.textOrNull
import com.kvid.store.Sql.update
import com.kvid.store.Sql.readSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readLine
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A kvid document store: one SQLite file holding documents, their retained versions, and a
 * full-text index over the current live versions. See docs/PERSISTENCE_CONTRACT.md on the
 * `planning` branch for the guarantees this class implements.
 *
 * Writes outside [transaction] commit before they return. Inside a [transaction] block, operations
 * go through the [Transaction] receiver, stage work, and commit together when the block completes.
 */
class Kvid private constructor(
    /** Canonical path of the store file. */
    val path: String,
    private val conn: SQLiteConnection,
    val readOnly: Boolean,
    private val options: StoreOptions,
    private val meta: Meta
) {
    private val dispatcher: CoroutineDispatcher = ioDispatcher.limitedParallelism(1)
    private val gate = Mutex()
    private var closed = false
    private var savepointCounter = 0

    internal data class Meta(val formatMajor: Int, val formatMinor: Int, val uniqueUri: Boolean)

    // ------------------------------------------------------------------ lifecycle

    companion object {
        private val driver = BundledSQLiteDriver()

        /** Creates a new store. Fails with [KvidException.AlreadyExists] if the path exists. */
        suspend fun create(path: String, options: StoreOptions = StoreOptions()): Kvid = acquireHandle {
            val canonical = canonicalize(path, mustExist = false)
            if (SystemFileSystem.exists(Path(canonical))) throw KvidException.AlreadyExists("store already exists: $canonical")
            OpenRegistry.acquire(canonical)
            try {
                FileSync.createExclusive(canonical)
                val conn = SqliteErrors.guard("open $canonical") { driver.open(canonical, SQLITE_OPEN_READWRITE) }
                try {
                    configureWritable(conn, options)
                    conn.exec("PRAGMA application_id = ${Schema.APPLICATION_ID}")
                    conn.exec("BEGIN IMMEDIATE")
                    Schema.createStatements.forEach { conn.exec(it) }
                    if (options.uniqueUri) conn.exec(Schema.UNIQUE_URI_INDEX)
                    conn.update(
                        "INSERT INTO kvid_meta(id, format_major, format_minor, created_by, created_at_ms, unique_uri, clean_close) VALUES (1, ?, ?, ?, ?, ?, 0)"
                    ) {
                        bindLong(1, Schema.FORMAT_MAJOR.toLong()); bindLong(2, Schema.FORMAT_MINOR.toLong())
                        bindText(3, Schema.CREATED_BY); bindLong(4, nowMs()); bindLong(5, if (options.uniqueUri) 1 else 0)
                    }
                    conn.exec("PRAGMA user_version = ${Schema.SCHEMA_VERSION}")
                    conn.exec("COMMIT")
                    Kvid(canonical, conn, readOnly = false, options, Meta(Schema.FORMAT_MAJOR, Schema.FORMAT_MINOR, options.uniqueUri))
                } catch (t: Throwable) {
                    closeQuietly(conn, t); throw t
                }
            } catch (t: Throwable) {
                withContext(NonCancellable) { OpenRegistry.release(canonical) }; throw t
            }
        }

        /** Opens an existing store for reading and writing. One writable handle per path per process. */
        suspend fun open(path: String, options: StoreOptions = StoreOptions()): Kvid = acquireHandle {
            val canonical = canonicalize(path, mustExist = true)
            OpenRegistry.acquire(canonical)
            try {
                val conn = SqliteErrors.guard("open $canonical") { driver.open(canonical, SQLITE_OPEN_READWRITE) }
                try {
                    conn.exec("PRAGMA busy_timeout = ${options.busyTimeoutMs}")
                    val meta = validate(conn, readOnly = false)
                    configureWritable(conn, options)
                    conn.exec("UPDATE kvid_meta SET clean_close = 0")
                    Kvid(canonical, conn, readOnly = false, options, meta)
                } catch (t: Throwable) {
                    closeQuietly(conn, t); throw t
                }
            } catch (t: Throwable) {
                withContext(NonCancellable) { OpenRegistry.release(canonical) }; throw t
            }
        }

        /** Opens a store read-only. Sees committed state only; writes raise [KvidException.ReadOnly]. */
        suspend fun openReadOnly(path: String, options: StoreOptions = StoreOptions()): Kvid = acquireHandle {
            val canonical = canonicalize(path, mustExist = true)
            val conn = SqliteErrors.guard("open $canonical") { driver.open(canonical, SQLITE_OPEN_READONLY) }
            try {
                conn.exec("PRAGMA busy_timeout = ${options.busyTimeoutMs}")
                val meta = validate(conn, readOnly = true)
                Kvid(canonical, conn, readOnly = true, options, meta)
            } catch (t: Throwable) {
                closeQuietly(conn, t); throw t
            }
        }

        private suspend fun acquireHandle(block: suspend () -> Kvid): Kvid {
            var acquired: Kvid? = null
            try {
                return withContext(ioDispatcher) { block().also { acquired = it } }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try { acquired?.close() } catch (cleanup: Throwable) {
                        if (cleanup !== failure) failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
        }

        internal fun configureWritable(conn: SQLiteConnection, options: StoreOptions) {
            conn.exec("PRAGMA foreign_keys = ON")
            conn.exec("PRAGMA journal_mode = DELETE")
            conn.exec("PRAGMA synchronous = EXTRA")
            conn.exec("PRAGMA busy_timeout = ${options.busyTimeoutMs}")
            val fk = conn.queryLong("PRAGMA foreign_keys")
            val journal = conn.queryText("PRAGMA journal_mode").lowercase()
            val sync = conn.queryLong("PRAGMA synchronous")
            if (fk != 1L || journal != "delete" || sync != 3L) {
                throw KvidException.Io("connection configuration not honoured: foreign_keys=$fk journal_mode=$journal synchronous=$sync")
            }
        }

        /** Identifies the file as a kvid store and checks its format and schema versions. */
        private fun validate(conn: SQLiteConnection, readOnly: Boolean): Meta {
            val appId = try {
                conn.queryLong("PRAGMA application_id")
            } catch (e: KvidException) {
                throw KvidException.NotAStore("not a readable SQLite database: ${e.message}", e)
            }
            if (appId != Schema.APPLICATION_ID) throw KvidException.NotAStore("application_id $appId is not kvid's")
            val hasMeta = conn.queryLong("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'kvid_meta'") == 1L
            if (!hasMeta) throw KvidException.NotAStore("kvid_meta table is missing")
            val row = conn.queryOne("SELECT format_major, format_minor, unique_uri, clean_close FROM kvid_meta WHERE id = 1") {
                listOf(getLong(0), getLong(1), getLong(2), getLong(3))
            } ?: throw KvidException.Corrupt("kvid_meta has no row")
            val (major, minor) = row[0].toInt() to row[1].toInt()
            if (major > Schema.FORMAT_MAJOR || (major == Schema.FORMAT_MAJOR && minor > Schema.FORMAT_MINOR)) {
                throw KvidException.UnsupportedFormat("store format $major.$minor is newer than supported ${Schema.FORMAT_MAJOR}.${Schema.FORMAT_MINOR}", major, minor)
            }
            val userVersion = conn.queryLong("PRAGMA user_version").toInt()
            if (userVersion > Schema.SCHEMA_VERSION) {
                throw KvidException.UnsupportedFormat("schema version $userVersion is newer than supported ${Schema.SCHEMA_VERSION}", major, minor)
            }
            if (userVersion < Schema.SCHEMA_VERSION) {
                if (readOnly) throw KvidException.UnsupportedFormat("schema version $userVersion needs migration; open writable first", major, minor)
                migrate(conn, userVersion)
            }
            if (row[3] == 0L) {
                val check = conn.queryText("PRAGMA quick_check")
                if (check != "ok") throw KvidException.Corrupt("quick_check after unclean close: $check")
            }
            return Meta(major, minor, row[2] == 1L)
        }

        private fun migrate(conn: SQLiteConnection, from: Int) {
            // Forward-only migrations, each in its own transaction with the version bump (contract, section 4).
            // There is no supported older schema yet. Never relabel unknown structures as v1.
            throw KvidException.UnsupportedFormat("no migration exists from schema $from to ${Schema.SCHEMA_VERSION}", Schema.FORMAT_MAJOR, Schema.FORMAT_MINOR)
        }

        private fun canonicalize(path: String, mustExist: Boolean): String {
            val p = Path(path)
            if (SystemFileSystem.exists(p)) return SystemFileSystem.resolve(p).toString()
            if (mustExist) throw KvidException.NotFound("no such file: $path")
            val parent = p.parent ?: Path(".")
            if (!SystemFileSystem.exists(parent)) throw KvidException.NotFound("parent directory does not exist: $parent")
            return Path(SystemFileSystem.resolve(parent), p.name).toString()
        }

        private fun closeQuietly(conn: SQLiteConnection, original: Throwable) {
            try { conn.close() } catch (t: Throwable) { if (t !== original) original.addSuppressed(t) }
        }

        internal fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
    }

    /** Serialises with running operations, waits for an active transaction, marks a clean close, and releases resources. */
    suspend fun close() {
        rejectInsideTransaction("close")
        gate.withLock {
            if (closed) return
            withContext(NonCancellable + dispatcher) {
                try {
                    if (!readOnly) runCatching { conn.exec("UPDATE kvid_meta SET clean_close = 1") }
                } finally {
                    closed = true
                    try { conn.close() } finally { if (!readOnly) OpenRegistry.release(path) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ auto-commit operations

    suspend fun put(text: String, options: PutOptions = PutOptions()): DocumentId = transaction { put(text, options) }
    /** Creates a new version with exactly the supplied fields; nothing is inherited from the previous version. */
    suspend fun update(id: DocumentId, text: String, options: PutOptions = PutOptions()): VersionId = transaction { update(id, text, options) }
    suspend fun delete(id: DocumentId): VersionId = transaction { delete(id) }

    suspend fun get(id: DocumentId, asOfSeq: Long? = null): Document? = read { Ops.get(conn, limits, id, asOfSeq) }
    suspend fun history(id: DocumentId): List<Version> = read { Ops.history(conn, limits, id) }
    suspend fun list(options: ListOptions = ListOptions()): Page<Document> = read { Ops.list(conn, limits, options) }
    suspend fun find(query: String, options: FindOptions = FindOptions()): Page<Hit> = read { Ops.find(conn, limits, query, options) }
    suspend fun stats(): StoreStats = read { Ops.stats(conn, meta) }

    private val limits get() = options.limits

    private suspend fun <T> read(block: () -> T): T {
        rejectInsideTransaction("store operation")
        return gate.withLock {
            ensureOpen()
            withContext(dispatcher) { conn.readSnapshot(block) }
        }
    }

    // ------------------------------------------------------------------ transactions

    /**
     * Runs [block] in one write transaction. Normal completion commits; any exception, including
     * cancellation, rolls back and propagates. The receiver must not escape the block or be shared
     * with other coroutines. Nested [Transaction.transaction] calls use savepoints.
     */
    suspend fun <T> transaction(block: suspend Transaction.() -> T): T {
        rejectInsideTransaction("transaction")
        if (readOnly) throw KvidException.ReadOnly("store opened read-only: $path")
        return gate.withLock {
            ensureOpen()
            var session: Session? = null
            var commitAttempted = false
            try {
                val txSeq = withContext(NonCancellable + dispatcher) {
                    conn.exec("BEGIN IMMEDIATE")
                    conn.queryLong("SELECT commit_seq FROM kvid_meta WHERE id = 1") + 1
                }
                currentCoroutineContext().ensureActive()
                val activeSession = Session(this, txSeq)
                session = activeSession
                val result = withContext(TxMarker(this, currentCoroutineContext()[TxMarker])) {
                    activeSession.ownerJob = currentCoroutineContext()[Job]
                    block(activeSession)
                }
                currentCoroutineContext().ensureActive()
                activeSession.rollbackFailure?.let { throw KvidException.Io("transaction is rollback-only after savepoint cleanup failure", it) }
                withContext(NonCancellable + dispatcher) {
                    commitAttempted = true
                    conn.update("UPDATE kvid_meta SET commit_seq = ? WHERE id = 1") { bindLong(1, txSeq) }
                    conn.exec("COMMIT")
                }
                result
            } catch (t: Throwable) {
                withContext(NonCancellable + dispatcher) {
                    var rollbackFailed = false
                    try { if (conn.inTransaction()) conn.exec("ROLLBACK") }
                    catch (cleanup: Throwable) {
                        rollbackFailed = true
                        if (cleanup !== t) t.addSuppressed(cleanup)
                    }
                    if (rollbackFailed || (commitAttempted && t is KvidException.Io)) {
                        closed = true
                        try { conn.close() } catch (cleanup: Throwable) { if (cleanup !== t) t.addSuppressed(cleanup) }
                        finally { OpenRegistry.release(path) }
                    }
                }
                throw t
            } finally {
                session?.ended = true
            }
        }
    }

    private class TxMarker(val store: Kvid, val parent: TxMarker?) : AbstractCoroutineContextElement(TxMarker) {
        companion object Key : CoroutineContext.Key<TxMarker>
    }

    private suspend fun rejectInsideTransaction(what: String) {
        if (generateSequence(currentCoroutineContext()[TxMarker]) { it.parent }.any { it.store === this }) {
            throw KvidException.Usage("$what must not be called inside transaction { }; use the Transaction receiver")
        }
    }

    private fun ensureOpen() {
        if (closed) throw KvidException.Closed("store is closed: $path")
    }

    /** Scoped write session handed to [Kvid.transaction] blocks. */
    private class Session(private val store: Kvid, private val txSeq: Long) : Transaction {
        var ownerJob: Job? = null
        var ended = false
        var rollbackFailure: Throwable? = null
        private var depth = 0

        private suspend fun <T> run(block: () -> T): T {
            if (ended) throw KvidException.Closed("transaction session has ended")
            rollbackFailure?.let { throw KvidException.Io("transaction is rollback-only after cleanup failure", it) }
            if (currentCoroutineContext()[Job] !== ownerJob) {
                throw KvidException.Usage("the Transaction receiver must not be shared with other coroutines")
            }
            return withContext(store.dispatcher) { block() }
        }

        override suspend fun put(text: String, options: PutOptions): DocumentId = transaction { run { Ops.put(store.conn, store.limits, txSeq, text, options) } }
        override suspend fun update(id: DocumentId, text: String, options: PutOptions): VersionId = transaction { run { Ops.update(store.conn, store.limits, txSeq, id, text, options) } }
        override suspend fun delete(id: DocumentId): VersionId = transaction { run { Ops.delete(store.conn, txSeq, id) } }
        override suspend fun get(id: DocumentId, asOfSeq: Long?): Document? = run { Ops.get(store.conn, store.limits, id, asOfSeq) }
        override suspend fun history(id: DocumentId): List<Version> = run { Ops.history(store.conn, store.limits, id) }
        override suspend fun list(options: ListOptions): Page<Document> = run { Ops.list(store.conn, store.limits, options) }
        override suspend fun find(query: String, options: FindOptions): Page<Hit> = run { Ops.find(store.conn, store.limits, query, options) }

        override suspend fun <T> transaction(block: suspend Transaction.() -> T): T {
            if (ended) throw KvidException.Closed("transaction session has ended")
            rollbackFailure?.let { throw KvidException.Io("transaction is rollback-only after cleanup failure", it) }
            if (currentCoroutineContext()[Job] !== ownerJob) throw KvidException.Usage("the Transaction receiver must not be shared with other coroutines")
            val name = "sp${++store.savepointCounter}"
            var started = false
            depth++
            try {
                withContext(NonCancellable + store.dispatcher) { store.conn.exec("SAVEPOINT $name"); started = true }
                currentCoroutineContext().ensureActive()
                val result = block(this)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable + store.dispatcher) { store.conn.exec("RELEASE SAVEPOINT $name") }
                return result
            } catch (t: Throwable) {
                withContext(NonCancellable + store.dispatcher) {
                    try {
                        if (started) store.conn.exec("ROLLBACK TO SAVEPOINT $name")
                        if (started) store.conn.exec("RELEASE SAVEPOINT $name")
                    } catch (cleanup: Throwable) {
                        rollbackFailure = cleanup
                        if (cleanup !== t) t.addSuppressed(cleanup)
                    }
                }
                throw t
            } finally {
                depth--
            }
        }
    }

    // ------------------------------------------------------------------ maintenance

    /** Full integrity and invariant check (contract, section 4). */
    suspend fun verify(): VerifyReport = read { Ops.verify(conn, readOnly) }

    /** Rebuilds the current-version projection and its full-text index from the authoritative versions. */
    suspend fun rebuildIndex() {
        rejectInsideTransaction("rebuildIndex")
        if (readOnly) throw KvidException.ReadOnly("store opened read-only: $path")
        gate.withLock {
            ensureOpen()
            withContext(NonCancellable + dispatcher) {
                conn.exec("BEGIN IMMEDIATE")
                try {
                    Ops.rebuildProjection(conn)
                    conn.exec("UPDATE kvid_meta SET commit_seq = commit_seq + 1 WHERE id = 1")
                    conn.exec("COMMIT")
                } catch (t: Throwable) {
                    runCatching { conn.exec("ROLLBACK") }; throw t
                }
            }
        }
    }

    /**
     * Applies [retention] transactionally, then shrinks the file with `VACUUM`. With [Retention.KEEP_LATEST]
     * superseded versions are removed, the history floor rises to the current commit sequence, and all
     * cursors expire. A failure during the physical shrink does not undo committed retention.
     */
    suspend fun vacuum(retention: Retention = Retention.KEEP_ALL) {
        rejectInsideTransaction("vacuum")
        if (readOnly) throw KvidException.ReadOnly("store opened read-only: $path")
        gate.withLock {
            ensureOpen()
            withContext(NonCancellable + dispatcher) {
                if (retention == Retention.KEEP_LATEST) {
                    conn.exec("BEGIN IMMEDIATE")
                    try {
                        val seq = conn.queryLong("SELECT commit_seq FROM kvid_meta WHERE id = 1")
                        conn.exec("DELETE FROM versions WHERE version_id NOT IN (SELECT current_version_id FROM documents)")
                        conn.update("UPDATE kvid_meta SET history_floor_seq = ?, commit_seq = ? WHERE id = 1") { bindLong(1, seq); bindLong(2, seq + 1) }
                        conn.exec("COMMIT")
                    } catch (t: Throwable) {
                        runCatching { conn.exec("ROLLBACK") }; throw t
                    }
                }
                if (retention == Retention.KEEP_ALL) conn.exec("UPDATE kvid_meta SET commit_seq = commit_seq + 1 WHERE id = 1")
                conn.exec("VACUUM")
            }
        }
    }

    /**
     * Writes a consistent single-file copy of the committed state to [destination], which must not exist.
     * The copy is produced through a fresh read-only connection into a temporary file next to the
     * destination, validated, synced, and then atomically renamed into place (contract, section 2).
     */
    suspend fun snapshot(destination: String) {
        rejectInsideTransaction("snapshot")
        gate.withLock {
            ensureOpen()
            withContext(NonCancellable + dispatcher) {
                val dest = Path(destination)
                if (SystemFileSystem.exists(dest)) throw KvidException.AlreadyExists("snapshot destination exists: $destination")
                val parent = dest.parent ?: throw KvidException.Usage("snapshot destination must have a parent directory")
                if (!SystemFileSystem.exists(parent)) throw KvidException.NotFound("snapshot directory does not exist: $parent")
                val temp = Path(parent, ".${dest.name}.kvid-tmp-${Random.nextLong().toULong().toString(16)}")
                try {
                    SqliteErrors.guard("snapshot export") {
                        driver.open(path, SQLITE_OPEN_READONLY).use { reader ->
                            reader.prepare("VACUUM INTO ?").use { st -> st.bindText(1, temp.toString()); st.step() }
                        }
                    }
                    // Validate the copy and make it durable: a write transaction under synchronous=EXTRA
                    // syncs the file and its directory entry through SQLite's own VFS.
                    SqliteErrors.guard("snapshot validation") {
                        driver.open(temp.toString()).use { copy ->
                            configureWritable(copy, options)
                            validate(copy, readOnly = false)
                            val check = copy.queryText("PRAGMA quick_check")
                            if (check != "ok") throw KvidException.Corrupt("snapshot failed quick_check: $check")
                            val report = Ops.verify(copy, readOnly = false)
                            if (!report.ok) throw KvidException.Corrupt("snapshot invariants: ${report.problems.joinToString()}")
                            copy.exec("UPDATE kvid_meta SET clean_close = 1")
                        }
                    }
                    FileSync.publishNoReplace(temp.toString(), dest.toString())
                    if (!FileSync.syncDirectory(parent.toString())) {
                        throw KvidException.Io("snapshot published at $destination, but directory sync failed; durability is uncertain; verify before retrying")
                    }
                } catch (t: Throwable) {
                    runCatching { SystemFileSystem.delete(temp, mustExist = false) }
                    throw t
                }
            }
        }
    }

    /** Exports every retained version as JSON Lines, oldest first, for inspection and recovery. */
    suspend fun exportJsonLines(destination: String) {
        rejectInsideTransaction("exportJsonLines")
        gate.withLock {
            ensureOpen()
            withContext(dispatcher) {
                val dest = Path(destination)
                if (SystemFileSystem.exists(dest)) throw KvidException.AlreadyExists("export destination exists: $destination")
                SystemFileSystem.sink(dest).buffered().use { sink ->
                    conn.readSnapshot { Ops.forEachVersion(conn, limits) { v ->
                        sink.writeString(Json.encodeToString(JsonlRecord.serializer(), JsonlRecord.from(v)))
                        sink.writeString("\n")
                    } }
                }
            }
        }
    }

    /**
     * Imports the live current versions from a JSON Lines export as new documents, keeping their ids,
     * event times, titles, metadata, uris and tags. Superseded and tombstoned versions are skipped.
     * Returns the number of documents created. Everything is imported in one transaction.
     */
    suspend fun importJsonLines(source: String): Int {
        val records = withContext(ioDispatcher) {
            val src = Path(source)
            if (!SystemFileSystem.exists(src)) throw KvidException.NotFound("no such file: $source")
            val latest = LinkedHashMap<String, JsonlRecord>()
            SystemFileSystem.source(src).buffered().use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isBlank()) continue
                    val r = try { Json.decodeFromString(JsonlRecord.serializer(), line) } catch (e: Exception) {
                        throw KvidException.Usage("malformed JSON Lines record: ${e.message}")
                    }
                    latest[r.documentId] = r   // records are oldest-first; the last one is the current version
                }
            }
            latest.values.filter { !it.tombstone }
        }
        return transaction {
            for (r in records) {
                put(r.body, PutOptions(
                    title = r.title,
                    metadata = r.metadata?.let { Json.parseToJsonElement(it).jsonObject },
                    uri = r.uri, tags = r.tags, eventTimeMs = r.eventTimeMs, documentId = r.documentId
                ))
            }
            records.size
        }
    }

    @Serializable
    internal data class JsonlRecord(
        val documentId: String,
        val versionId: Long,
        val seq: Long,
        val eventTimeMs: Long,
        val commitTimeMs: Long,
        val title: String? = null,
        val body: String,
        val metadata: String? = null,
        val uri: String? = null,
        val tags: List<String> = emptyList(),
        val tombstone: Boolean = false,
        val supersedesVersionId: Long? = null
    ) {
        companion object {
            fun from(v: Version) = JsonlRecord(
                v.documentId, v.versionId, v.seq, v.eventTimeMs, v.commitTimeMs, v.title, v.body,
                v.metadata?.toString(), v.uri, v.tags, v.tombstone, v.supersedesVersionId
            )
        }
    }
}

/** Operations available inside a [Kvid.transaction] block. Reads see the block's staged writes. */
interface Transaction {
    suspend fun put(text: String, options: PutOptions = PutOptions()): DocumentId
    suspend fun update(id: DocumentId, text: String, options: PutOptions = PutOptions()): VersionId
    suspend fun delete(id: DocumentId): VersionId
    suspend fun get(id: DocumentId, asOfSeq: Long? = null): Document?
    suspend fun history(id: DocumentId): List<Version>
    suspend fun list(options: ListOptions = ListOptions()): Page<Document>
    suspend fun find(query: String, options: FindOptions = FindOptions()): Page<Hit>
    /** Nested scope backed by a savepoint: its failure rolls back only its own work. */
    suspend fun <T> transaction(block: suspend Transaction.() -> T): T
}

/** One writable handle per canonical path per process (contract, section 5). */
internal object OpenRegistry {
    private val mutex = Mutex()
    private val writable = HashSet<String>()

    suspend fun acquire(path: String) = mutex.withLock {
        if (!writable.add(path)) throw KvidException.Locked("store is already open for writing in this process: $path")
    }

    suspend fun release(path: String) = mutex.withLock { writable.remove(path); Unit }
}

/** Pure SQL operations on an open connection. No locking, no dispatching: callers provide both. */
internal object Ops {
    private const val VERSION_COLUMNS = """v.version_id, v.doc_id, v.seq, v.event_time_ms, v.commit_time_ms,
        CASE WHEN octet_length(v.title) <= ${Limits.MAX_TITLE_BYTES} THEN v.title END,
        CASE WHEN octet_length(v.body) <= ? THEN v.body END, octet_length(v.body),
        CASE WHEN v.metadata IS NULL OR octet_length(v.metadata) <= ? THEN v.metadata END, coalesce(octet_length(v.metadata), 0),
        CASE WHEN octet_length(v.uri) <= ${Limits.MAX_URI_BYTES} THEN v.uri END, v.tombstone, v.supersedes_version_id,
        coalesce(octet_length(v.title), 0), coalesce(octet_length(v.uri), 0)"""

    // ---- writes

    fun put(conn: SQLiteConnection, limits: Limits, txSeq: Long, rawText: String, rawOptions: PutOptions): DocumentId {
        val (text, options) = normalized(rawText, rawOptions)
        validate(limits, text, options)
        val now = Kvid.nowMs()
        val id = options.documentId?.also { Ids.validateDocumentId(it) } ?: Ids.uuidV7(now)
        if (conn.queryOne("SELECT 1 FROM documents WHERE doc_id = ?", { bindText(1, id) }) { 1 } != null) {
            throw KvidException.AlreadyExists("document already exists: $id")
        }
        conn.update("INSERT INTO documents(doc_id, created_seq, current_version_id) VALUES (?, ?, 0)") { bindText(1, id); bindLong(2, txSeq) }
        val versionId = insertVersion(conn, id, txSeq, now, text, options, tombstone = false, supersedes = null)
        conn.update("UPDATE documents SET current_version_id = ? WHERE doc_id = ?") { bindLong(1, versionId); bindText(2, id) }
        insertCurrent(conn, versionId, id, options.eventTimeMs ?: now, options.uri)
        return id
    }

    fun update(conn: SQLiteConnection, limits: Limits, txSeq: Long, id: DocumentId, rawText: String, rawOptions: PutOptions): VersionId {
        val (text, options) = normalized(rawText, rawOptions)
        validate(limits, text, options)
        val current = currentVersionOf(conn, id) ?: throw KvidException.NotFound("no such document: $id")
        if (current.second) throw KvidException.NotFound("document is deleted: $id")
        val now = Kvid.nowMs()
        val versionId = insertVersion(conn, id, txSeq, now, text, options, tombstone = false, supersedes = current.first)
        conn.update("UPDATE documents SET current_version_id = ? WHERE doc_id = ?") { bindLong(1, versionId); bindText(2, id) }
        conn.update("DELETE FROM current WHERE doc_id = ?") { bindText(1, id) }
        insertCurrent(conn, versionId, id, options.eventTimeMs ?: now, options.uri)
        return versionId
    }

    fun delete(conn: SQLiteConnection, txSeq: Long, id: DocumentId): VersionId {
        val current = currentVersionOf(conn, id) ?: throw KvidException.NotFound("no such document: $id")
        if (current.second) throw KvidException.NotFound("document is already deleted: $id")
        val now = Kvid.nowMs()
        val versionId = insertVersion(conn, id, txSeq, now, "", PutOptions(eventTimeMs = now), tombstone = true, supersedes = current.first)
        conn.update("UPDATE documents SET current_version_id = ? WHERE doc_id = ?") { bindLong(1, versionId); bindText(2, id) }
        conn.update("DELETE FROM current WHERE doc_id = ?") { bindText(1, id) }
        return versionId
    }

    /** (current_version_id, isTombstone) or null. */
    private fun currentVersionOf(conn: SQLiteConnection, id: DocumentId): Pair<Long, Boolean>? = conn.queryOne(
        "SELECT d.current_version_id, v.tombstone FROM documents d JOIN versions v ON v.version_id = d.current_version_id WHERE d.doc_id = ?",
        { bindText(1, id) }
    ) { getLong(0) to (getLong(1) == 1L) }

    private fun insertVersion(
        conn: SQLiteConnection, id: DocumentId, txSeq: Long, now: Long, text: String, options: PutOptions,
        tombstone: Boolean, supersedes: Long?
    ): VersionId {
        conn.update(
            "INSERT INTO versions(doc_id, seq, event_time_ms, commit_time_ms, title, body, metadata, uri, tombstone, supersedes_version_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ) {
            bindText(1, id); bindLong(2, txSeq); bindLong(3, options.eventTimeMs ?: now); bindLong(4, now)
            bindTextOrNull(5, options.title); bindText(6, text); bindTextOrNull(7, options.metadata?.toString())
            bindTextOrNull(8, options.uri); bindLong(9, if (tombstone) 1 else 0); bindLongOrNull(10, supersedes)
        }
        val versionId = conn.queryLong("SELECT last_insert_rowid()")
        for (tag in options.tags.distinct()) {
            conn.update("INSERT INTO version_tags(version_id, tag) VALUES (?, ?)") { bindLong(1, versionId); bindText(2, tag) }
        }
        return versionId
    }

    /** The version row must already exist: the insert trigger indexes its title and body. */
    private fun insertCurrent(conn: SQLiteConnection, versionId: Long, id: DocumentId, eventTimeMs: Long, uri: String?) {
        try {
            conn.update("INSERT INTO current(version_id, doc_id, event_time_ms, uri) VALUES (?, ?, ?, ?)") {
                bindLong(1, versionId); bindText(2, id); bindLong(3, eventTimeMs); bindTextOrNull(4, uri)
            }
        } catch (e: KvidException.Io) {
            if (e.cause is SQLiteException && (e.cause as SQLiteException).message.orEmpty().contains("current.uri")) {
                throw KvidException.AlreadyExists("a live document already uses uri '$uri'")
            }
            throw e
        }
    }

    /** Titles, bodies and tags are stored in Unicode NFC (contract, section 7); uris and metadata are stored as given. */
    private fun normalized(text: String, options: PutOptions): Pair<String, PutOptions> =
        normalizeNfc(text) to options.copy(title = options.title?.let { normalizeNfc(it) }, tags = options.tags.map { normalizeNfc(it) })

    private fun validate(limits: Limits, text: String, options: PutOptions) {
        if (text.encodeToByteArray().size > limits.bodyBytes) throw KvidException.LimitExceeded("body exceeds ${limits.bodyBytes} bytes")
        options.title?.let { if (it.encodeToByteArray().size > limits.titleBytes) throw KvidException.LimitExceeded("title exceeds ${limits.titleBytes} bytes") }
        options.metadata?.let { if (it.toString().encodeToByteArray().size > limits.metadataBytes) throw KvidException.LimitExceeded("metadata exceeds ${limits.metadataBytes} bytes") }
        options.uri?.let { if (it.encodeToByteArray().size > limits.uriBytes) throw KvidException.LimitExceeded("uri exceeds ${limits.uriBytes} bytes") }
        if (options.tags.size > limits.tagsPerVersion) throw KvidException.LimitExceeded("more than ${limits.tagsPerVersion} tags")
        for (tag in options.tags) {
            if (tag.isEmpty()) throw KvidException.Usage("tags must not be empty")
            if (tag.count { !it.isLowSurrogate() } > limits.tagCodePoints) throw KvidException.LimitExceeded("tag exceeds ${limits.tagCodePoints} code points")
        }
    }

    // ---- reads

    fun get(conn: SQLiteConnection, limits: Limits, id: DocumentId, asOfSeq: Long?): Document? {
        val version = if (asOfSeq == null) {
            conn.queryOne(
                "SELECT $VERSION_COLUMNS FROM documents d JOIN versions v ON v.version_id = d.current_version_id WHERE d.doc_id = ?",
                { bindLong(1, Limits.MAX_BODY_BYTES.toLong()); bindLong(2, Limits.MAX_METADATA_BYTES.toLong()); bindText(3, id) }
            ) { readVersion(this) }
        } else {
            val floor = conn.queryLong("SELECT history_floor_seq FROM kvid_meta WHERE id = 1")
            if (asOfSeq < floor) throw KvidException.HistoryUnavailable("asOfSeq $asOfSeq is below the history floor $floor")
            conn.queryOne(
                "SELECT $VERSION_COLUMNS FROM versions v WHERE v.doc_id = ? AND v.seq <= ? ORDER BY v.seq DESC, v.version_id DESC LIMIT 1",
                { bindLong(1, Limits.MAX_BODY_BYTES.toLong()); bindLong(2, Limits.MAX_METADATA_BYTES.toLong()); bindText(3, id); bindLong(4, asOfSeq) }
            ) { readVersion(this) }
        } ?: return null
        if (version.tombstone) return null
        return Document(id, withTags(conn, version))
    }

    fun history(conn: SQLiteConnection, limits: Limits, id: DocumentId): List<Version> = conn.query(
        "SELECT $VERSION_COLUMNS FROM versions v WHERE v.doc_id = ? ORDER BY v.seq, v.version_id",
        { bindLong(1, Limits.MAX_BODY_BYTES.toLong()); bindLong(2, Limits.MAX_METADATA_BYTES.toLong()); bindText(3, id) }
    ) { readVersion(this) }.map { withTags(conn, it) }

    fun forEachVersion(conn: SQLiteConnection, limits: Limits, action: (Version) -> Unit) {
        val ids = conn.query("SELECT version_id FROM versions ORDER BY seq, version_id") { getLong(0) }
        for (vid in ids) {
            val v = conn.queryOne(
                "SELECT $VERSION_COLUMNS FROM versions v WHERE v.version_id = ?",
                { bindLong(1, Limits.MAX_BODY_BYTES.toLong()); bindLong(2, Limits.MAX_METADATA_BYTES.toLong()); bindLong(3, vid) }
            ) { readVersion(this) } ?: continue
            action(withTags(conn, v))
        }
    }

    /** Filters shared by list and find (contract, section 7): every supplied filter must hold. */
    private class Filters(limits: Limits, val since: Long?, val until: Long?, tags: List<String>, val uriPrefix: String?) {
        val tags: List<String> = tags.map { normalizeNfc(it) }.distinct()

        init {
            if (this.tags.size > limits.tagsPerVersion) throw KvidException.LimitExceeded("more than ${limits.tagsPerVersion} tag filters")
            if (this.tags.any { it.isEmpty() }) throw KvidException.Usage("tag filters must not be empty")
            uriPrefix?.let { if (it.encodeToByteArray().size > limits.uriBytes) throw KvidException.LimitExceeded("uriPrefix exceeds ${limits.uriBytes} bytes") }
        }

        fun appendSql(sb: StringBuilder) {
            if (since != null) sb.append(" AND c.event_time_ms >= ?")
            if (until != null) sb.append(" AND c.event_time_ms < ?")
            repeat(tags.size) { sb.append(" AND EXISTS (SELECT 1 FROM version_tags t WHERE t.version_id = c.version_id AND t.tag = ?)") }
            if (uriPrefix != null) sb.append(" AND c.uri GLOB ?")
        }

        /** Binds the filter parameters starting at index [first]; returns the next free index. */
        fun bind(st: androidx.sqlite.SQLiteStatement, first: Int): Int {
            var i = first
            since?.let { st.bindLong(i++, it) }
            until?.let { st.bindLong(i++, it) }
            tags.forEach { st.bindText(i++, it) }
            uriPrefix?.let { st.bindText(i++, globPrefix(it)) }
            return i
        }

        fun fingerprint(): List<String?> = listOf(since?.toString(), until?.toString(), Json.encodeToString(tags), uriPrefix)

        /** A GLOB pattern matching [prefix] literally: `*`, `?` and `[` are bracketed. */
        private fun globPrefix(prefix: String): String = buildString {
            for (ch in prefix) if (ch == '*' || ch == '?' || ch == '[') append('[').append(ch).append(']') else append(ch)
            append('*')
        }
    }

    fun list(conn: SQLiteConnection, limits: Limits, options: ListOptions): Page<Document> {
        val limit = options.limit.coerceIn(1, limits.pageSize)
        val filters = Filters(limits, options.sinceEventTimeMs, options.untilEventTimeMs, options.tags, options.uriPrefix)
        val fingerprint = Json.encodeToString(listOf("list", limit.toString()) + filters.fingerprint())
        val cursor = options.cursor?.let { Cursor.parse(conn, it, fingerprint) }
        val sql = buildString {
            append("SELECT c.version_id, c.doc_id FROM current c WHERE 1=1")
            filters.appendSql(this)
            if (cursor != null) append(" AND (c.event_time_ms < ? OR (c.event_time_ms = ? AND c.version_id < ?))")
            append(" ORDER BY c.event_time_ms DESC, c.version_id DESC LIMIT ?")
        }
        val rows = conn.query(sql, {
            var i = filters.bind(this, 1)
            cursor?.let { bindLong(i++, it.key1); bindLong(i++, it.key1); bindLong(i++, it.key2) }
            bindLong(i, (limit + 1).toLong())
        }) { getLong(0) to getText(1) }
        val page = rows.take(limit).map { (vid, docId) -> Document(docId, loadVersion(conn, vid)) }
        val next = if (rows.size > limit) {
            val last = page.last().version
            Cursor(conn, fingerprint, last.eventTimeMs, last.versionId).encode()
        } else null
        return Page(page, next)
    }

    fun find(conn: SQLiteConnection, limits: Limits, rawQuery: String, options: FindOptions): Page<Hit> {
        if (rawQuery.encodeToByteArray().size > limits.queryBytes) throw KvidException.LimitExceeded("query exceeds ${limits.queryBytes} bytes")
        val query = normalizeNfc(rawQuery)
        val limit = options.limit.coerceIn(1, limits.pageSize)
        val filters = Filters(limits, options.sinceEventTimeMs, options.untilEventTimeMs, options.tags, options.uriPrefix)
        val expression = when (options.syntax) {
            QuerySyntax.PLAIN -> PlainQuery.compile(query, options.match) ?: return Page(emptyList(), null)
            QuerySyntax.FTS5 -> {
                if (query.isBlank()) throw KvidException.InvalidQuery("empty FTS5 query")
                query
            }
        }
        val fingerprint = Json.encodeToString(
            listOf("find", options.syntax.name, options.match.name, expression, limit.toString()) + filters.fingerprint()
        )
        val cursor = options.cursor?.let { Cursor.parse(conn, it, fingerprint) }
        val offset = cursor?.key1 ?: 0L
        val sql = buildString {
            append("SELECT c.version_id, c.doc_id, bm25(current_fts), snippet(current_fts, 1, '[', ']', '…', 12) FROM current_fts JOIN current c ON c.version_id = current_fts.rowid WHERE current_fts MATCH ?")
            filters.appendSql(this)
            append(" ORDER BY bm25(current_fts), c.version_id DESC LIMIT ? OFFSET ?")
        }
        val rows = try {
            conn.query(sql, {
                bindText(1, expression)
                val i = filters.bind(this, 2)
                bindLong(i, (limit + 1).toLong()); bindLong(i + 1, offset)
            }) { Triple(getLong(0), getDouble(2), getText(3)) }
        } catch (e: KvidException.Io) {
            // FTS5 reports a rejected expression as SQLITE_ERROR from the statement carrying MATCH.
            val cause = e.cause
            if (options.syntax == QuerySyntax.FTS5 && cause is SQLiteException && SqliteErrors.primaryCode(cause) == SqliteErrors.SQLITE_ERROR) {
                throw KvidException.InvalidQuery("rejected FTS5 query: ${SqliteErrors.driverMessage(cause)}", cause)
            }
            throw e
        }
        val page = rows.take(limit).map { (vid, rank, snippet) ->
            val v = loadVersion(conn, vid)
            Hit(Document(v.documentId, v), -rank, snippet)
        }
        val next = if (rows.size > limit) Cursor(conn, fingerprint, offset + limit, 0).encode() else null
        return Page(page, next)
    }

    fun stats(conn: SQLiteConnection, meta: Kvid.Meta): StoreStats = StoreStats(
        documents = conn.queryLong("SELECT count(*) FROM documents"),
        liveDocuments = conn.queryLong("SELECT count(*) FROM current"),
        versions = conn.queryLong("SELECT count(*) FROM versions"),
        commitSeq = conn.queryLong("SELECT commit_seq FROM kvid_meta WHERE id = 1"),
        historyFloorSeq = conn.queryLong("SELECT history_floor_seq FROM kvid_meta WHERE id = 1"),
        fileBytes = conn.queryLong("SELECT page_count * page_size FROM pragma_page_count(), pragma_page_size()"),
        formatMajor = meta.formatMajor,
        formatMinor = meta.formatMinor
    )

    private fun loadVersion(conn: SQLiteConnection, versionId: Long): Version {
        val v = conn.queryOne(
            "SELECT $VERSION_COLUMNS FROM versions v WHERE v.version_id = ?",
            { bindLong(1, Limits.MAX_BODY_BYTES.toLong()); bindLong(2, Limits.MAX_METADATA_BYTES.toLong()); bindLong(3, versionId) }
        ) { readVersion(this) } ?: throw KvidException.Corrupt("current projection references missing version $versionId")
        return withTags(conn, v)
    }

    private fun readVersion(st: androidx.sqlite.SQLiteStatement): Version {
        if (st.getLong(13) > Limits.MAX_TITLE_BYTES || st.getLong(14) > Limits.MAX_URI_BYTES) {
            throw KvidException.Corrupt("stored title or uri exceeds the hard cap")
        }
        val bodyLen = st.getLong(7)
        if (bodyLen > Limits.MAX_BODY_BYTES) throw KvidException.Corrupt("stored body of version ${st.getLong(0)} is $bodyLen bytes, above the hard cap")
        val metaLen = st.getLong(9)
        if (metaLen > Limits.MAX_METADATA_BYTES) throw KvidException.Corrupt("stored metadata of version ${st.getLong(0)} is $metaLen bytes, above the hard cap")
        val metadata = st.textOrNull(8)?.let { raw ->
            try { Json.parseToJsonElement(raw).jsonObject } catch (e: Exception) {
                throw KvidException.Corrupt("metadata of version ${st.getLong(0)} is not a JSON object", e)
            }
        }
        return Version(
            documentId = st.getText(1), versionId = st.getLong(0), seq = st.getLong(2),
            eventTimeMs = st.getLong(3), commitTimeMs = st.getLong(4), title = st.textOrNull(5),
            body = st.textOrNull(6) ?: "", metadata = metadata, uri = st.textOrNull(10),
            tags = emptyList(), tombstone = st.getLong(11) == 1L, supersedesVersionId = st.longOrNull(12)
        )
    }

    private fun withTags(conn: SQLiteConnection, v: Version): Version {
        val tags = conn.query(
            "SELECT CASE WHEN length(tag) <= ${Limits.MAX_TAG_CODE_POINTS} AND octet_length(tag) <= ${4 * Limits.MAX_TAG_CODE_POINTS} THEN tag END, length(tag), octet_length(tag) FROM version_tags WHERE version_id = ? ORDER BY tag LIMIT ${Limits.MAX_TAGS + 1}",
            { bindLong(1, v.versionId) }
        ) {
            if (getLong(1) > Limits.MAX_TAG_CODE_POINTS || getLong(2) > 4 * Limits.MAX_TAG_CODE_POINTS) throw KvidException.Corrupt("stored tag exceeds hard cap")
            getText(0)
        }
        if (tags.size > Limits.MAX_TAGS) throw KvidException.Corrupt("stored tags exceed hard cap")
        return if (tags.isEmpty()) v else v.copy(tags = tags)
    }

    // ---- derived state and verification

    fun rebuildProjection(conn: SQLiteConnection) {
        conn.exec("DELETE FROM current")
        conn.exec(
            """INSERT INTO current(version_id, doc_id, event_time_ms, uri)
               SELECT v.version_id, v.doc_id, v.event_time_ms, v.uri
               FROM documents d JOIN versions v ON v.version_id = d.current_version_id WHERE v.tombstone = 0"""
        )
        conn.exec("INSERT INTO current_fts(current_fts) VALUES ('rebuild')")
    }

    fun verify(conn: SQLiteConnection, readOnly: Boolean): VerifyReport {
        val problems = ArrayList<String>()
        val integrity = conn.query("PRAGMA integrity_check") { getText(0) }
        if (integrity != listOf("ok")) problems += integrity.map { "integrity_check: $it" }
        conn.query("PRAGMA foreign_key_check") { "foreign_key_check: ${getText(0)} rowid=${getLong(1)} -> ${getText(2)}" }.let { problems += it }
        problems += conn.query(
            "SELECT d.doc_id FROM documents d LEFT JOIN versions v ON v.version_id = d.current_version_id AND v.doc_id = d.doc_id WHERE v.version_id IS NULL"
        ) { "document ${getText(0)}: current_version_id does not reference one of its versions" }
        problems += conn.query(
            """SELECT d.doc_id FROM documents d WHERE d.current_version_id <>
               (SELECT v.version_id FROM versions v WHERE v.doc_id = d.doc_id ORDER BY v.seq DESC, v.version_id DESC LIMIT 1)"""
        ) { "document ${getText(0)}: current_version_id is not the newest version" }
        problems += conn.query(
            """SELECT d.doc_id FROM documents d JOIN versions v ON v.version_id = d.current_version_id
               LEFT JOIN current c ON c.doc_id = d.doc_id
               WHERE (v.tombstone = 0 AND (c.version_id IS NULL OR c.version_id <> v.version_id)) OR (v.tombstone = 1 AND c.version_id IS NOT NULL)"""
        ) { "document ${getText(0)}: current projection disagrees with the newest version" }
        problems += conn.query("SELECT c.doc_id FROM current c LEFT JOIN documents d ON d.doc_id = c.doc_id WHERE d.doc_id IS NULL") {
            "current projection row ${getText(0)} has no document"
        }
        problems += conn.query(
            """SELECT c.doc_id FROM current c JOIN versions v ON v.version_id = c.version_id
               WHERE c.doc_id IS NOT v.doc_id OR c.event_time_ms IS NOT v.event_time_ms OR c.uri IS NOT v.uri"""
        ) { "current projection content differs from authoritative version for ${getText(0)}" }
        val unchecked = if (readOnly) listOf("FTS integrity requires a writable handle") else emptyList()
        val commitSeq = conn.queryLong("SELECT commit_seq FROM kvid_meta WHERE id = 1")
        problems += conn.query("SELECT version_id FROM versions WHERE seq > ?", { bindLong(1, commitSeq) }) { "version ${getLong(0)} has seq above commit_seq $commitSeq" }
        val floor = conn.queryLong("SELECT history_floor_seq FROM kvid_meta WHERE id = 1")
        if (floor == 0L) {
            problems += conn.query(
                "SELECT v.version_id FROM versions v WHERE v.supersedes_version_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM versions p WHERE p.version_id = v.supersedes_version_id)"
            ) { "version ${getLong(0)} supersedes a missing version although no history was pruned" }
        }
        // FTS5's integrity-check is issued as an INSERT and therefore needs a writable connection.
        if (!readOnly) {
            try {
                conn.exec("INSERT INTO current_fts(current_fts, rank) VALUES ('integrity-check', 1)")
            } catch (e: KvidException) {
                problems += "fts integrity-check: ${e.message}"
            }
        }
        return VerifyReport(problems.isEmpty(), problems, unchecked)
    }
}

/**
 * Opaque pagination cursor: expires after any committed write or compaction (contract, section 7).
 * Format: `v2:<commitSeq>:<floorSeq>:<exactFingerprintHex>:<key1>:<key2>`.
 */
internal class Cursor(private val seq: Long, private val floor: Long, private val fp: String, val key1: Long, val key2: Long) {
    constructor(conn: SQLiteConnection, fingerprint: String, key1: Long, key2: Long) : this(
        conn.queryLong("SELECT commit_seq FROM kvid_meta WHERE id = 1"),
        conn.queryLong("SELECT history_floor_seq FROM kvid_meta WHERE id = 1"),
        fingerprintKey(fingerprint), key1, key2
    )

    fun encode(): String = "v2:$seq:$floor:$fp:$key1:$key2"

    companion object {
        // Exact bounded query identity avoids collisions in String.hashCode().
        private fun fingerprintKey(value: String): String = value.encodeToByteArray().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }

        fun parse(conn: SQLiteConnection, encoded: String, fingerprint: String): Cursor {
            if (encoded.length > 4 * Limits.MAX_QUERY_BYTES) throw KvidException.Usage("cursor exceeds hard cap")
            val parts = encoded.split(':')
            if (parts.size != 6 || parts[0] != "v2") throw KvidException.Usage("malformed cursor")
            fun number(index: Int) = parts[index].toLongOrNull() ?: throw KvidException.Usage("malformed cursor")
            val cursor = Cursor(number(1), number(2), parts[3], number(4), number(5))
            if (cursor.fp != fingerprintKey(fingerprint)) throw KvidException.Usage("cursor does not belong to this query")
            val seq = conn.queryLong("SELECT commit_seq FROM kvid_meta WHERE id = 1")
            val floor = conn.queryLong("SELECT history_floor_seq FROM kvid_meta WHERE id = 1")
            if (seq != cursor.seq || floor != cursor.floor) throw KvidException.CursorExpired("the store changed since this cursor was issued")
            return cursor
        }
    }
}
