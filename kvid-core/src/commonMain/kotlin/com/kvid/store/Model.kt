package com.kvid.store

import kotlinx.serialization.json.JsonObject

typealias DocumentId = String
typealias VersionId = Long

/** One retained version of a document, as stored. */
data class Version(
    val documentId: DocumentId,
    val versionId: VersionId,
    /** Commit sequence of the transaction that committed this version. */
    val seq: Long,
    val eventTimeMs: Long,
    val commitTimeMs: Long,
    val title: String?,
    val body: String,
    val metadata: JsonObject?,
    val uri: String?,
    val tags: List<String>,
    val tombstone: Boolean,
    val supersedesVersionId: VersionId?
)

/** A document as seen at a point in commit order: its visible version. */
data class Document(
    val id: DocumentId,
    val version: Version
) {
    val body: String get() = version.body
    val title: String? get() = version.title
}

data class PutOptions(
    val title: String? = null,
    val metadata: JsonObject? = null,
    val uri: String? = null,
    val tags: List<String> = emptyList(),
    /** Application event time in epoch milliseconds; defaults to the wall clock at put time. */
    val eventTimeMs: Long? = null,
    /** Caller-supplied id; must be unique in the store. Only honoured by `put`. */
    val documentId: DocumentId? = null
)

data class ListOptions(
    val limit: Int = 50,
    val cursor: String? = null,
    val sinceEventTimeMs: Long? = null,
    val untilEventTimeMs: Long? = null,
    val tag: String? = null
)

data class FindOptions(
    val limit: Int = 20,
    val cursor: String? = null,
    val sinceEventTimeMs: Long? = null,
    val untilEventTimeMs: Long? = null,
    val tag: String? = null
)

data class Page<T>(val items: List<T>, val nextCursor: String?)

/** A full-text hit over current live versions. [score] is higher-is-better (negated SQLite bm25). */
data class Hit(val document: Document, val score: Double, val snippet: String?)

enum class Retention { KEEP_ALL, KEEP_LATEST }

/** [ok] covers performed checks only; inspect [unchecked] before treating verification as complete. */
data class VerifyReport(val ok: Boolean, val problems: List<String>, val unchecked: List<String> = emptyList())

data class StoreStats(
    val documents: Long,
    val liveDocuments: Long,
    val versions: Long,
    val commitSeq: Long,
    val historyFloorSeq: Long,
    val fileBytes: Long,
    val formatMajor: Int,
    val formatMinor: Int
)

/** Hard caps protect the library; configured limits may only tighten them. */
data class Limits(
    val bodyBytes: Int = 16 * 1024 * 1024,
    val metadataBytes: Int = 64 * 1024,
    val titleBytes: Int = 1024,
    val tagsPerVersion: Int = 256,
    val tagCodePoints: Int = 128,
    val uriBytes: Int = 2048,
    val queryBytes: Int = 4096,
    val pageSize: Int = 500
) {
    init {
        require(bodyBytes in 1..MAX_BODY_BYTES) { "bodyBytes must be within 1..$MAX_BODY_BYTES" }
        require(metadataBytes in 1..MAX_METADATA_BYTES) { "metadataBytes must be within 1..$MAX_METADATA_BYTES" }
        require(titleBytes in 1..MAX_TITLE_BYTES) { "titleBytes must be within 1..$MAX_TITLE_BYTES" }
        require(tagsPerVersion in 1..MAX_TAGS) { "tagsPerVersion must be within 1..$MAX_TAGS" }
        require(tagCodePoints in 1..MAX_TAG_CODE_POINTS) { "tagCodePoints must be within 1..$MAX_TAG_CODE_POINTS" }
        require(uriBytes in 1..MAX_URI_BYTES) { "uriBytes must be within 1..$MAX_URI_BYTES" }
        require(queryBytes in 1..MAX_QUERY_BYTES) { "queryBytes must be within 1..$MAX_QUERY_BYTES" }
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be within 1..$MAX_PAGE_SIZE" }
    }

    companion object {
        const val MAX_BODY_BYTES = 256 * 1024 * 1024
        const val MAX_METADATA_BYTES = 4 * 1024 * 1024
        const val MAX_TITLE_BYTES = 64 * 1024
        const val MAX_TAGS = 4096
        const val MAX_TAG_CODE_POINTS = 1024
        const val MAX_URI_BYTES = 64 * 1024
        const val MAX_QUERY_BYTES = 64 * 1024
        const val MAX_PAGE_SIZE = 5000
    }
}

data class StoreOptions(
    /** Store-wide schema option fixed at create time: `uri` values of live documents must be unique. */
    val uniqueUri: Boolean = false,
    val busyTimeoutMs: Int = 5_000,
    val limits: Limits = Limits()
)
