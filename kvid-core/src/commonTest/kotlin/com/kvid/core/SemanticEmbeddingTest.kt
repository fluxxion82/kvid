package com.kvid.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SimpleEmbeddingTest {
    private lateinit var embedding: SimpleEmbedding

    @BeforeTest
    fun setUp() {
        embedding = SimpleEmbedding()
    }

    @Test
    fun testEmbedDimension() = runTest {
        val vector = embedding.embed("test text")
        assertEquals(384, vector.size)
    }

    @Test
    fun testEmbedConsistency() = runTest {
        val text = "consistent text"
        val vec1 = embedding.embed(text)
        val vec2 = embedding.embed(text)

        assertTrue(vec1.contentEquals(vec2), "Same text should produce same embedding")
    }

    @Test
    fun testSimilarityBounds() = runTest {
        val vec1 = embedding.embed("test")
        val vec2 = embedding.embed("test")

        val similarity = embedding.similarity(vec1, vec2)
        assertTrue(similarity >= 0f && similarity <= 1f)
    }

    @Test
    fun testDistanceBounds() = runTest {
        val vec1 = embedding.embed("test")
        val vec2 = embedding.embed("different")

        val distance = embedding.distance(vec1, vec2)
        assertTrue(distance >= 0f && distance <= 1f)
    }

    @Test
    fun testSimilaritySymmetry() = runTest {
        val vec1 = embedding.embed("text one")
        val vec2 = embedding.embed("text two")

        val sim12 = embedding.similarity(vec1, vec2)
        val sim21 = embedding.similarity(vec2, vec1)

        assertEquals(sim12, sim21, 0.0001f, "Similarity should be symmetric")
    }
}

class InMemoryVectorIndexTest {
    private lateinit var index: InMemoryVectorIndex
    private lateinit var embedding: SimpleEmbedding

    @BeforeTest
    fun setUp() {
        embedding = SimpleEmbedding()
        index = InMemoryVectorIndex(embedding)
    }

    @Test
    fun testAddAndRetrieve() = runTest {
        val vector = FloatArray(384) { 1f / 384f }
        index.add(0, vector).getOrThrow()

        assertEquals(1, index.size())
        assertNotNull(index.getVector(0))
    }

    @Test
    fun testSearch() = runTest {
        val texts = listOf("apple", "orange", "banana", "carrot")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector).getOrThrow()
        }

        val queryVector = embedding.embed("fruit")
        val results = index.search(queryVector, topK = 2)

        assertEquals(2, results.size)
        assertTrue(results[0].id >= 0)
    }

    @Test
    fun testSearchEmpty() = runTest {
        val queryVector = FloatArray(384)
        val results = index.search(queryVector, topK = 5)

        assertEquals(0, results.size)
    }

    @Test
    fun testClear() = runTest {
        val vector = FloatArray(384)
        index.add(0, vector).getOrThrow()
        assertEquals(1, index.size())

        index.clear().getOrThrow()
        assertEquals(0, index.size())
    }
}
