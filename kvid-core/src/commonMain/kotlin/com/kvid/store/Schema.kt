package com.kvid.store

/**
 * Schema of a kvid store (persistence contract, sections 4, 6 and 7).
 *
 * - `kvid_meta` holds format and library versions, the commit sequence, the history floor, the
 *   advisory clean-close marker, and the reserved embedding/encryption fields.
 * - `documents` and `versions` are the authoritative, append-only history. `documents.current_version_id`
 *   is the newest version including tombstones.
 * - `current` is a projection of live current versions (tombstoned documents are absent): ids, event time
 *   and uri for filtering and ordering. It holds no text. The FTS5 index reads its external content
 *   through the `current_content` view, which joins the projection to the immutable `versions` rows, so
 *   current titles and bodies are stored once. Everything here is rebuildable from `versions`.
 */
internal object Schema {
    /** `PRAGMA application_id`: ASCII "KVID". */
    const val APPLICATION_ID = 0x4B564944L
    const val FORMAT_MAJOR = 0
    const val FORMAT_MINOR = 1
    /**
     * `PRAGMA user_version`; bump with every forward migration. Schema 2 removed the duplicated title and
     * body from `current`; schema 1 existed only before any release and has no migration.
     */
    const val SCHEMA_VERSION = 2
    const val CREATED_BY = "kvid-core 0.1.0"

    val createStatements: List<String> = listOf(
        """CREATE TABLE kvid_meta(
            id INTEGER PRIMARY KEY CHECK (id = 1),
            format_major INTEGER NOT NULL,
            format_minor INTEGER NOT NULL,
            created_by TEXT NOT NULL,
            created_at_ms INTEGER NOT NULL,
            commit_seq INTEGER NOT NULL DEFAULT 0,
            history_floor_seq INTEGER NOT NULL DEFAULT 0,
            clean_close INTEGER NOT NULL DEFAULT 1,
            unique_uri INTEGER NOT NULL DEFAULT 0,
            embedding_config TEXT,
            encryption TEXT NOT NULL DEFAULT 'none',
            key_id TEXT
        )""",
        """CREATE TABLE documents(
            doc_id TEXT PRIMARY KEY NOT NULL,
            created_seq INTEGER NOT NULL,
            current_version_id INTEGER NOT NULL
        )""",
        """CREATE TABLE versions(
            version_id INTEGER PRIMARY KEY AUTOINCREMENT,
            doc_id TEXT NOT NULL REFERENCES documents(doc_id),
            seq INTEGER NOT NULL,
            event_time_ms INTEGER NOT NULL,
            commit_time_ms INTEGER NOT NULL,
            title TEXT,
            body TEXT NOT NULL,
            metadata TEXT,
            uri TEXT,
            tombstone INTEGER NOT NULL DEFAULT 0,
            supersedes_version_id INTEGER,
            nonce BLOB
        )""",
        "CREATE INDEX versions_doc_order ON versions(doc_id, seq, version_id)",
        "CREATE INDEX versions_event_time ON versions(event_time_ms, version_id)",
        """CREATE TABLE version_tags(
            version_id INTEGER NOT NULL REFERENCES versions(version_id) ON DELETE CASCADE,
            tag TEXT NOT NULL,
            PRIMARY KEY (version_id, tag)
        ) WITHOUT ROWID""",
        "CREATE INDEX version_tags_tag ON version_tags(tag, version_id)",
        """CREATE TABLE current(
            version_id INTEGER PRIMARY KEY NOT NULL,
            doc_id TEXT NOT NULL UNIQUE REFERENCES documents(doc_id),
            event_time_ms INTEGER NOT NULL,
            uri TEXT
        )""",
        "CREATE INDEX current_event_time ON current(event_time_ms, version_id)",
        """CREATE VIEW current_content AS
            SELECT c.version_id AS version_id, v.title AS title, v.body AS body
            FROM current c JOIN versions v ON v.version_id = c.version_id""",
        """CREATE VIRTUAL TABLE current_fts USING fts5(
            title, body, content='current_content', content_rowid='version_id', tokenize = 'unicode61'
        )""",
        // Versions are immutable while they are current, so the text indexed on insert is exactly the
        // text the delete command must supply. Retention only removes versions that are not current.
        """CREATE TRIGGER current_ai AFTER INSERT ON current BEGIN
            INSERT INTO current_fts(rowid, title, body)
                SELECT version_id, title, body FROM versions WHERE version_id = new.version_id;
        END""",
        """CREATE TRIGGER current_ad AFTER DELETE ON current BEGIN
            INSERT INTO current_fts(current_fts, rowid, title, body)
                SELECT 'delete', version_id, title, body FROM versions WHERE version_id = old.version_id;
        END""",
        """CREATE TRIGGER current_au AFTER UPDATE ON current BEGIN
            INSERT INTO current_fts(current_fts, rowid, title, body)
                SELECT 'delete', version_id, title, body FROM versions WHERE version_id = old.version_id;
            INSERT INTO current_fts(rowid, title, body)
                SELECT version_id, title, body FROM versions WHERE version_id = new.version_id;
        END"""
    )

    const val UNIQUE_URI_INDEX = "CREATE UNIQUE INDEX current_unique_uri ON current(uri) WHERE uri IS NOT NULL"
}
