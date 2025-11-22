package com.kvid.core

import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryStoreTest {
    private lateinit var embedding: KeywordEmbedding
    private lateinit var store: MemoryStore

    @BeforeTest
    fun setUp() {
        embedding = KeywordEmbedding()
        store = MemoryStore(
            chunkSize = 100,
            embedModel = embedding,
            vectorIndex = InMemoryVectorIndex(embedding)
        )
    }

    @Test
    fun addMessageUpdatesStatsForAllChunks() = runTest {
        val message = Message(
            id = 42,
            content = buildString {
                repeat(5) {
                    append("Kotlin coroutines make Kotlin development pleasant. ")
                }
            },
            timestamp = 1234L,
            source = "unit-test"
        )

        val expectedChunks = TextChunker(chunkSize = 100).chunk(message.content).size

        val result = store.addMessage(message)
        assertTrue(result.isSuccess, "addMessage should succeed")

        val stats = store.getStats()
        assertEquals(expectedChunks, stats.totalChunks)
        assertEquals(1, stats.totalMessages)
        assertEquals(expectedChunks, stats.vectorIndexSize)
        assertTrue(stats.averageChunkSize > 0)
    }

    @Test
    fun searchReturnsUniqueMessagesSortedByRelevance() = runTest {
        val verboseKotlin = buildString {
            repeat(6) {
                append("Kotlin enables productive Kotlin multiplatform workflows. ")
            }
        }

        store.addMessages(
            listOf(
                Message(id = 1, content = verboseKotlin),
                Message(id = 2, content = "Compose for Android and desktop feels Compose-first."),
                Message(id = 3, content = "Kotlin coroutines keep Kotlin applications snappy.")
            )
        ).getOrThrow()

        val results = store.search("Kotlin multiplatform", topK = 5).getOrThrow()

        assertTrue(results.size >= 2, "Expected results for Kotlin-heavy messages")
        assertEquals(
            results.map { it.messageId }.toSet().size,
            results.size,
            "Search results should contain at most one entry per message"
        )
        assertEquals(1, results.first().messageId, "Most Kotlin-heavy message should rank first")
        assertTrue(
            results.zipWithNext().all { (a, b) -> a.relevance >= b.relevance },
            "Results should be sorted by relevance descending"
        )
    }

    @Test
    fun clearEmptiesStoreAndIndex() = runTest {
        store.addMessage(Message(id = 7, content = "Another Kotlin snippet.")).getOrThrow()
        assertTrue(store.getStats().totalChunks > 0)

        val cleared = store.clear()
        assertTrue(cleared.isSuccess)

        val stats = store.getStats()
        assertEquals(0, stats.totalChunks)
        assertEquals(0, stats.totalMessages)
        assertEquals(0, stats.vectorIndexSize)
    }

    private class KeywordEmbedding : SemanticEmbedding {
        override suspend fun embed(text: String): FloatArray = encode(text)

        override suspend fun embedBatch(texts: List<String>): List<FloatArray> =
            texts.map { encode(it) }

        override fun getDimension(): Int = DIMENSION

        override fun similarity(embedding1: FloatArray, embedding2: FloatArray): Float {
            var dot = 0f
            var norm1 = 0f
            var norm2 = 0f
            for (i in 0 until DIMENSION) {
                dot += embedding1[i] * embedding2[i]
                norm1 += embedding1[i] * embedding1[i]
                norm2 += embedding2[i] * embedding2[i]
            }
            if (norm1 == 0f || norm2 == 0f) return 0f
            return (dot / (sqrt(norm1) * sqrt(norm2))).coerceIn(-1f, 1f)
        }

        override fun distance(embedding1: FloatArray, embedding2: FloatArray): Float {
            return 1f - similarity(embedding1, embedding2)
        }

        private fun encode(text: String): FloatArray {
            val normalized = text.lowercase()
            return floatArrayOf(
                countOccurrences(normalized, "kotlin").toFloat(),
                countOccurrences(normalized, "compose").toFloat(),
                countOccurrences(normalized, "coroutines").toFloat(),
                normalized.length / 100f
            )
        }

        private fun countOccurrences(text: String, keyword: String): Int {
            if (keyword.isEmpty()) return 0
            var count = 0
            var index = text.indexOf(keyword)
            while (index >= 0) {
                count++
                index = text.indexOf(keyword, index + keyword.length)
            }
            return count
        }

        companion object {
            private const val DIMENSION = 4
        }
    }
}
