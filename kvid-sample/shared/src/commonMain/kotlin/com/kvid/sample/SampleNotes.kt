package com.kvid.sample

import com.kvid.store.Kvid
import com.kvid.store.PutOptions

/** Notes written into a newly created store so the sample has something to search. */
object SampleNotes {
    private val notes = listOf(
        Triple("Welcome to kvid", "This notes app keeps every note in one portable kvid file. Search is offline full-text search with BM25 ranking.", listOf("kvid", "help")),
        Triple("Searching", "Type words to find notes containing all of them. Accents and case do not matter: cafe matches Café.", listOf("help")),
        Triple("Tags", "Tap a tag chip to show only notes carrying every selected tag.", listOf("help")),
        Triple("History", "Every save keeps the previous version. Open a note and choose History to see them.", listOf("kvid", "help")),
        Triple("Groceries", "Milk, eggs, bread, coffee beans, and something for dinner.", listOf("home")),
        Triple("Q4 planning", "Budget review on Thursday; draft the plan for next quarter and list the risks.", listOf("work"))
    )

    suspend fun seed(store: Kvid) {
        store.transaction {
            for ((title, body, tags) in notes) put(body, PutOptions(title = title, tags = tags))
        }
    }
}
