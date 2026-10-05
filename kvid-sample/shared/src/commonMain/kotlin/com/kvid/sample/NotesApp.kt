package com.kvid.sample

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The notes app for every platform. The caller owns [session] at application scope. */
@Composable
fun NotesApp(session: StoreSession) {
    val model = remember(session) { NotesModel(session) }
    MaterialTheme {
        NotesScreen(model)
    }
}

@Composable
fun NotesScreen(model: NotesModel) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Search as the query settles rather than on every keystroke.
    LaunchedEffect(state.query) {
        delay(SEARCH_DEBOUNCE_MS)
        model.refresh()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = model::newNote) { Text("+") }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Notes", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { model.backup() } }) { Text("Back up") }
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = model::setQuery,
                placeholder = { Text("Search notes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (state.tags.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.tags, key = { it.tag }) { tag ->
                        FilterChip(
                            selected = tag.tag in state.selectedTags,
                            onClick = { scope.launch { model.toggleTag(tag.tag) } },
                            label = { Text("${tag.tag} ${tag.documents}") }
                        )
                    }
                }
            }
            (state.message ?: state.notice)?.let { text ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text,
                        color = if (state.message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = model::clearMessage) { Text("Dismiss") }
                }
            }
            if (!state.loading && state.notes.isEmpty()) {
                Text(
                    if (state.query.isBlank() && state.selectedTags.isEmpty()) "No notes yet." else "No matching notes.",
                    modifier = Modifier.padding(16.dp)
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.notes, key = { it.id }) { note ->
                    NoteRow(note) { scope.launch { model.open(note.id) } }
                    HorizontalDivider()
                }
            }
        }
    }

    state.draft?.let { draft -> EditorDialog(draft, model) }
    state.history?.let { history -> HistoryDialog(history, model::closeHistory) }
}

@Composable
private fun NoteRow(note: NoteItem, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(note.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (note.tags.isNotEmpty()) {
            Text(note.tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EditorDialog(draft: Draft, model: NotesModel) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = model::closeDraft,
        title = { Text(if (draft.id == null) "New note" else "Edit note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { model.editDraft(draft.copy(title = it)) },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.body,
                    onValueChange = { model.editDraft(draft.copy(body = it)) },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                )
                OutlinedTextField(
                    value = draft.tags,
                    onValueChange = { model.editDraft(draft.copy(tags = it)) },
                    label = { Text("Tags, comma separated") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                draft.id?.let { id ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch { model.showHistory(id) } }) { Text("History") }
                        TextButton(onClick = { scope.launch { model.deleteDraft() } }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { model.saveDraft() } }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = model::closeDraft) { Text("Cancel") }
        }
    )
}

@Composable
private fun HistoryDialog(history: List<HistoryEntry>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("History") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(history.asReversed(), key = { it.versionId }) { entry ->
                    Column {
                        Text(
                            "Commit ${entry.seq}" + if (entry.deleted) " (deleted)" else "",
                            style = MaterialTheme.typography.labelMedium
                        )
                        entry.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                        if (!entry.deleted) Text(entry.preview, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

private const val SEARCH_DEBOUNCE_MS = 150L
