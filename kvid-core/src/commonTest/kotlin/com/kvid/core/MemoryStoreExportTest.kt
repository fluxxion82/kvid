package com.kvid.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryStoreExportTest {
    @Test
    fun exportIndexEscapesSpecialCharacters() = runTest {
        val store = MemoryStore(chunkSize = 64)
        val tricky = "He said \"hi\" \\ back\\slash\ttab and a real newline\nhere"
        store.addMessage(Message(id = 7, content = tricky)).getOrThrow()

        val json = store.exportIndex()
        val decoded = Json.decodeFromString(ExportedIndex.serializer(), json)

        assertEquals(1, decoded.chunks.size)
        assertEquals(tricky, decoded.chunks[0].content)
        assertEquals(7, decoded.chunks[0].messageId)
    }
}
