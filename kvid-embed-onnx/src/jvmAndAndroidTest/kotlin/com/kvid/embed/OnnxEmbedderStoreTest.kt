package com.kvid.embed

import com.kvid.store.FindOptions
import com.kvid.store.Kvid
import com.kvid.store.MatchMode
import com.kvid.store.PutOptions
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real model through the store: indexing records the embedder's spec, semantic search finds notes
 * that share no word with the query, and after a reopen a second embedder over the same files searches
 * the stored vectors without embedding them again. Uses the embedding fixture's model files with the
 * parity test's skip-or-fail rule.
 */
class OnnxEmbedderStoreTest {
    private val notes = listOf(
        "Quarterly budget" to "Expenses rose twelve percent; travel and software licences account for most of the increase.",
        "Sourdough" to "Feed the starter the night before, mix flour and water, fold every half hour, bake at 250 °C.",
        "Dentist" to "Cleaning appointment on Tuesday at 9:30; bring the insurance card.",
        "Marathon training" to "Long run of 28 km on Sunday, easy pace, two gels and plenty of water.",
        "Flight to Lisbon" to "Boarding pass is in the wallet app; terminal 2, gate closes 40 minutes before departure.",
        "Garden" to "Plant tomatoes after the last frost and water the seedlings every morning."
    )

    /** Each query and the title of the note it means. */
    private val queries = listOf(
        "money we spent last quarter" to "Quarterly budget",
        "baking bread in my kitchen" to "Sourdough",
        "teeth checkup" to "Dentist",
        "getting ready for a race" to "Marathon training",
        "airport plane trip" to "Flight to Lisbon",
        "growing vegetables" to "Garden"
    )

    @Test fun semanticSearchFindsNotesWithoutSharedWordsAndSurvivesAReopen() = runBlocking {
        val fixture = EmbeddingFixture.loadOrSkip("embedder store test") ?: return@runBlocking
        val dir = File.createTempFile("kvid-store", "").apply { delete(); mkdirs() }
        val path = File(dir, "notes.kvid").path
        try {
            val store = Kvid.create(path)
            try {
                store.transaction { notes.forEach { (title, body) -> put(body, PutOptions(title = title)) } }
                val lexical = queries.associate { (query, _) ->
                    query to store.find(query, FindOptions(match = MatchMode.ANY)).items.map { it.document.title }
                }
                for ((query, title) in queries) {
                    assertTrue(title !in lexical.getValue(query), "'$query' shares a word with '$title'; the test needs queries only vectors connect")
                }
                OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath).use { embedder ->
                    val report = store.indexVectors(embedder)
                    assertEquals(notes.size, report.embedded)
                    assertEquals(0L, report.pending)
                    assertEquals(embedder.spec, store.vectorStatus().spec)
                    assertTopHits(store, embedder)
                    for ((query, title) in queries) {
                        // Fusion ranks a note that matches a word of the query and is semantically close above
                        // one that is only semantically closest, so only queries without lexical hits must agree.
                        val hybrid = store.findHybrid(query, embedder).items.map { it.document.title }
                        if (lexical.getValue(query).isEmpty()) assertEquals(title, hybrid.firstOrNull(), "hybrid search for '$query': $hybrid")
                        else assertTrue(title in hybrid, "hybrid search for '$query' lost '$title': $hybrid")
                    }
                }
            } finally {
                store.close()
            }

            val reopened = Kvid.open(path)
            try {
                OnnxEmbedder.open(fixture.modelPath, fixture.vocabPath).use { embedder ->
                    val status = reopened.vectorStatus()
                    assertEquals(embedder.spec, status.spec, "the same files give the same spec in a new embedder")
                    assertEquals(notes.size.toLong(), status.embedded)
                    assertEquals(0, reopened.indexVectors(embedder).embedded, "stored vectors are not embedded again")
                    assertTopHits(reopened, embedder)
                }
            } finally {
                reopened.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private suspend fun assertTopHits(store: Kvid, embedder: OnnxEmbedder) {
        for ((query, title) in queries) {
            val hits = store.findSimilar(query, embedder).items
            assertEquals(notes.size, hits.size)
            println("[kvid] similar '$query': ${hits[0].document.title} ${fmt("%.3f", hits[0].score)}, next ${hits[1].document.title} ${fmt("%.3f", hits[1].score)}")
            assertEquals(title, hits.first().document.title, "semantic search for '$query': ${hits.map { it.document.title to it.score }}")
        }
    }
}
