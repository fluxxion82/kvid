package com.kvid.sample

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesModelTest {

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
