package com.kvid.sample

import com.kvid.store.Kvid
import com.kvid.store.use
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesModelTest {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aRefreshDoesNotPublishResultsForAnObsoleteQuery() = sampleTest { path ->
        val session = StoreSession(path)
        session.use { it.put("stove"); it.put("tent") }
        val model = NotesModel(session)
        model.refresh()
        val initial = model.state.value.notes
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = launch {
            session.use { store -> store.transaction { entered.complete(Unit); release.await() } }
        }
        entered.await()
        model.setQuery("stove")
        val search = launch { model.refresh() }
        runCurrent() // the old refresh is now waiting behind the transaction
        model.setQuery("tent")
        release.complete(Unit)
        writer.join()
        search.join()
        assertEquals(initial, model.state.value.notes, "the obsolete stove response must not replace the list")
        model.refresh()
        assertEquals(listOf("tent"), model.state.value.notes.map { it.title })
        session.closeWhenIdle()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aRefreshDoesNotPublishErrorsForAnObsoleteQuery() = sampleTest { path ->
        val session = StoreSession(path)
        session.use { it.put("tent") }
        val model = NotesModel(session)
        model.refresh()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = launch {
            session.use { store -> store.transaction { entered.complete(Unit); release.await() } }
        }
        entered.await()
        model.setQuery("x".repeat(4097))
        val search = launch { model.refresh() }
        runCurrent()
        model.setQuery("tent")
        release.complete(Unit)
        writer.join()
        search.join()
        assertNull(model.state.value.message, "an obsolete request must not publish its size error")
        model.refresh()
        assertEquals(listOf("tent"), model.state.value.notes.map { it.title })
        session.closeWhenIdle()
    }

    @Test fun createSearchFilterEditHistoryAndDelete() = sampleTest { path ->
        val session = StoreSession(path)
        val model = NotesModel(session)
        model.refresh()
        assertTrue(model.state.value.notes.isEmpty())
        assertTrue(!model.state.value.loading)

        model.newNote()
        model.editDraft(model.state.value.draft!!.copy(title = "Trip", body = "Pack the tent and the stove.", tags = "Outdoor, travel"))
        model.saveDraft()
        model.newNote()
        model.editDraft(model.state.value.draft!!.copy(body = "Buy a new stove fuel canister.", tags = "errands"))
        model.saveDraft()

        val state = model.state.value
        assertNull(state.draft)
        assertNull(state.message)
        assertEquals(listOf("Buy a new stove fuel canister.", "Trip"), state.notes.map { it.title }, "newest first; untitled notes use their first line")
        assertEquals(listOf("errands", "outdoor", "travel"), state.tags.map { it.tag }, "tags are parsed and lowercased")

        model.search("stove")
        assertEquals(2, model.state.value.notes.size)
        assertTrue(model.state.value.notes.all { it.preview.contains("[stove]") }, "search results show snippets")

        model.toggleTag("travel")
        assertEquals(listOf("Trip"), model.state.value.notes.map { it.title }, "tag filters combine with the query")
        model.toggleTag("travel")
        model.search("")

        val trip = model.state.value.notes.single { it.title == "Trip" }
        model.open(trip.id)
        assertEquals("Pack the tent and the stove.", model.state.value.draft?.body)
        model.editDraft(model.state.value.draft!!.copy(body = "Pack the tent, the stove and a map."))
        model.saveDraft()
        model.showHistory(trip.id)
        val history = assertNotNull(model.state.value.history)
        assertEquals(listOf("Pack the tent and the stove.", "Pack the tent, the stove and a map."), history.map { it.preview })
        model.closeHistory()

        model.open(trip.id)
        model.deleteDraft()
        assertEquals(listOf("Buy a new stove fuel canister."), model.state.value.notes.map { it.title })
        model.showHistory(trip.id)
        assertTrue(model.state.value.history!!.last().deleted, "deletion is kept as a version")
        session.closeWhenIdle()
    }

    @Test fun backupWritesAVerifiedSnapshotAndTheStoreKeepsWorking() = sampleTest { path ->
        val session = StoreSession(path, onCreate = SampleNotes::seed)
        val model = NotesModel(session)
        model.refresh()
        val live = model.state.value.notes.size
        model.backup()
        val notice = assertNotNull(model.state.value.notice)
        val backupPath = notice.removePrefix("Backed up to ")
        Kvid.openReadOnly(backupPath).use { copy ->
            assertEquals(live.toLong(), copy.stats().liveDocuments)
            assertTrue(copy.verify().ok)
        }
        model.newNote()
        model.editDraft(model.state.value.draft!!.copy(body = "after the backup"))
        model.saveDraft()
        assertEquals(live + 1, model.state.value.notes.size, "the live store stays writable")
        Kvid.openReadOnly(backupPath).use { assertEquals(live.toLong(), it.stats().liveDocuments, "the backup is a fixed copy") }
        model.clearMessage()
        assertNull(model.state.value.notice)
        session.closeWhenIdle()
    }

    @Test fun storeErrorsBecomeMessages() = sampleTest { path ->
        val session = StoreSession(path)
        val model = NotesModel(session)
        model.open("no-such-note")
        assertTrue(model.state.value.message!!.startsWith("KV_NOT_FOUND"), model.state.value.message)
        model.clearMessage()
        assertNull(model.state.value.message)
        session.closeWhenIdle()
    }

    @Test fun parseTagsTrimsLowercasesAndDeduplicates() {
        assertEquals(listOf("work", "q4", "plan"), NotesModel.parseTags(" Work, q4,,plan work\nPLAN "))
    }
}
