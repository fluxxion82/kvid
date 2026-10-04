package com.kvid.sample

import com.kvid.store.Document
import com.kvid.store.DocumentId
import com.kvid.store.FindOptions
import com.kvid.store.KvidException
import com.kvid.store.ListOptions
import com.kvid.store.PutOptions
import com.kvid.store.TagCount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A note as shown in the list. [preview] is the search snippet for search results, else the body start. */
data class NoteItem(
    val id: DocumentId,
    val title: String,
    val preview: String,
    val tags: List<String>
)

/** The editor's working copy. [id] is null for a new note. [tags] is the comma-separated text field. */
data class Draft(val id: DocumentId?, val title: String, val body: String, val tags: String)

/** One retained version of a note, oldest first. */
data class HistoryEntry(val versionId: Long, val seq: Long, val title: String?, val preview: String, val deleted: Boolean)

data class NotesState(
    val query: String = "",
    val selectedTags: Set<String> = emptySet(),
    val notes: List<NoteItem> = emptyList(),
    val tags: List<TagCount> = emptyList(),
    val draft: Draft? = null,
    val history: List<HistoryEntry>? = null,
    /** A store failure to show; cleared by [NotesModel.clearMessage]. */
    val message: String? = null,
    /** A completed action to confirm, such as where a backup was written; cleared by [NotesModel.clearMessage]. */
    val notice: String? = null,
    val loading: Boolean = true
)

/**
 * The notes screen's state and actions. Every action is a suspend function that finishes its store
 * work before returning, so the UI launches them and tests await them. Store failures become
 * [NotesState.message]; nothing is retried silently.
 */
class NotesModel(private val session: StoreSession) {
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow(NotesState())
    val state: StateFlow<NotesState> = mutableState.asStateFlow()

    /** Reloads the list (or search results) and the tag chips for the current query and tag selection. */
    suspend fun refresh() = refreshMutex.withLock {
        val current = state.value
        report(accept = { it.query == current.query && it.selectedTags == current.selectedTags }) {
            val (notes, tags) = session.use { store ->
                val selected = current.selectedTags.toList()
                val notes = if (current.query.isBlank()) {
                    store.list(ListOptions(limit = PAGE, tags = selected)).items.map { it.toItem(null) }
                } else {
                    store.find(current.query, FindOptions(limit = PAGE, tags = selected)).items.map { it.document.toItem(it.snippet) }
                }
                notes to store.tagCounts(TAG_CHIPS)
            }
            mutableState.update {
                if (it.query == current.query && it.selectedTags == current.selectedTags) {
                    it.copy(notes = notes, tags = tags, loading = false)
                } else it
            }
        }
    }

    /** Updates the query without searching; the UI calls [refresh] once typing settles. */
    fun setQuery(query: String) = mutableState.update { it.copy(query = query) }

    suspend fun search(query: String) {
        setQuery(query)
        refresh()
    }

    suspend fun toggleTag(tag: String) {
        mutableState.update { it.copy(selectedTags = if (tag in it.selectedTags) it.selectedTags - tag else it.selectedTags + tag) }
        refresh()
    }

    fun newNote() = mutableState.update { it.copy(draft = Draft(null, "", "", "")) }

    suspend fun open(id: DocumentId) = report {
        val document = session.use { it.get(id) } ?: throw KvidException.NotFound("note was deleted: $id")
        mutableState.update { it.copy(draft = Draft(id, document.title.orEmpty(), document.body, document.version.tags.joinToString(", "))) }
    }

    fun editDraft(draft: Draft) = mutableState.update { it.copy(draft = draft) }

    fun closeDraft() = mutableState.update { it.copy(draft = null) }

    /** Saves the draft as a new note or a new version of the existing note, then closes the editor. */
    suspend fun saveDraft() {
        val draft = state.value.draft ?: return
        report {
            val options = PutOptions(title = draft.title.trim().ifEmpty { null }, tags = parseTags(draft.tags))
            session.use { store -> if (draft.id == null) store.put(draft.body, options) else store.update(draft.id, draft.body, options) }
            mutableState.update { it.copy(draft = null) }
        }
        refresh()
    }

    suspend fun deleteDraft() {
        val id = state.value.draft?.id ?: return
        report {
            session.use { it.delete(id) }
            mutableState.update { it.copy(draft = null) }
        }
        refresh()
    }

    suspend fun showHistory(id: DocumentId) = report {
        val versions = session.use { it.history(id) }
        mutableState.update { state ->
            state.copy(history = versions.map { HistoryEntry(it.versionId, it.seq, it.title, preview(it.body), it.tombstone) })
        }
    }

    fun closeHistory() = mutableState.update { it.copy(history = null) }

    /** Writes a snapshot of the store; [NotesState.notice] names the file. */
    suspend fun backup() = report {
        val destination = session.backup()
        mutableState.update { it.copy(notice = "Backed up to $destination") }
    }

    fun clearMessage() = mutableState.update { it.copy(message = null, notice = null) }

    private inline fun report(accept: (NotesState) -> Boolean = { true }, block: () -> Unit) {
        try {
            block()
        } catch (e: KvidException) {
            mutableState.update { if (accept(it)) it.copy(message = "${e.code}: ${e.message}", loading = false) else it }
        }
    }

    private fun Document.toItem(snippet: String?) = NoteItem(
        id = id,
        title = title?.takeIf { it.isNotBlank() } ?: preview(body).substringBefore('\n').ifEmpty { "Untitled" },
        preview = snippet ?: preview(body),
        tags = version.tags
    )

    companion object {
        const val PAGE = 200
        const val TAG_CHIPS = 30

        /** Comma- or whitespace-separated tags, trimmed, lowercased, without duplicates or empties. */
        fun parseTags(text: String): List<String> =
            text.split(',', ' ', '\n', '\t').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

        fun preview(body: String): String = body.trim().take(160)
    }
}
