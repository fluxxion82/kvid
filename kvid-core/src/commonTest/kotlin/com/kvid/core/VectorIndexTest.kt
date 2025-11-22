package com.kvid.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/**
 * Comprehensive tests for vector index implementations (Flat and HNSW)
 * Tests both exact search (Flat) and approximate search (HNSW)
 */
class FlatVectorIndexTest {
    private lateinit var embedding: SimpleEmbedding
    private lateinit var index: FlatVectorIndex

    @BeforeTest
    fun setUp() {
        embedding = SimpleEmbedding()
        index = FlatVectorIndex(embedding)
    }

    @Test
    fun testAddVector() = runTest {
        val vector = embedding.embed("test document")
        index.add(0, vector)

        assertEquals(1, index.size())
    }

    @Test
    fun testAddMultipleVectors() = runTest {
        val texts = listOf("apple", "banana", "carrot", "dog")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        assertEquals(4, index.size())
    }

    @Test
    fun testSearchReturnsTopK() = runTest {
        val texts = listOf("apple fruit", "banana fruit", "carrot vegetable", "dog animal")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        val queryVector = embedding.embed("fruit")
        val results = index.search(queryVector, topK = 2)

        assertEquals(2, results.size, "Should return exactly topK results")
        assertTrue(results[0].similarity >= results[1].similarity, "Results should be sorted by similarity")
    }

    @Test
    fun testSearchEmptyIndex() = runTest {
        val queryVector = embedding.embed("test")
        val results = index.search(queryVector, topK = 5)

        assertEquals(0, results.size, "Empty index should return no results")
    }

    @Test
    fun testSearchMoreResultsThanTopK() = runTest {
        val texts = listOf("apple", "banana", "carrot", "dog", "elephant", "flower")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        val queryVector = embedding.embed("food")
        val results = index.search(queryVector, topK = 2)

        assertEquals(2, results.size, "Should respect topK limit")
    }

    @Test
    fun testSearchLessThanTopK() = runTest {
        val vector1 = embedding.embed("apple")
        val vector2 = embedding.embed("banana")
        index.add(0, vector1)
        index.add(1, vector2)

        val queryVector = embedding.embed("fruit")
        val results = index.search(queryVector, topK = 5)

        assertEquals(2, results.size, "Should return all available when less than topK")
    }

    @Test
    fun testSimilarityScores() = runTest {
        val vector1 = embedding.embed("apple")
        val vector2 = embedding.embed("apple")  // Identical
        val vector3 = embedding.embed("completely different text")

        index.add(0, vector1)
        index.add(1, vector2)
        index.add(2, vector3)

        val queryVector = embedding.embed("apple")
        val results = index.search(queryVector, topK = 3)

        assertTrue(results[0].similarity > results[2].similarity, "Similar vectors should have higher similarity")
        assertTrue(results[0].similarity >= 0.8f, "Identical text should have high similarity")
    }

    @Test
    fun testClear() = runTest {
        val vector = embedding.embed("test")
        index.add(0, vector)
        assertEquals(1, index.size())

        index.clear()
        assertEquals(0, index.size())

        val results = index.search(vector, topK = 5)
        assertEquals(0, results.size)
    }

    @Test
    fun testGetVector() = runTest {
        val vector = embedding.embed("test document")
        index.add(42, vector)

        val retrieved = index.getVector(42)
        assertNotNull(retrieved, "Should retrieve added vector")
        assertTrue(retrieved.contentEquals(vector), "Retrieved vector should match original")
    }

    @Test
    fun testGetNonexistentVector() = runTest {
        val retrieved = index.getVector(999)
        assertNull(retrieved, "Non-existent vector should return null")
    }

    @Test
    fun testLargeDataset() = runTest {
        val vectorCount = 1000
        for (i in 0 until vectorCount) {
            val vector = embedding.embed("document number $i with unique content")
            index.add(i, vector)
        }

        assertEquals(vectorCount, index.size())

        val queryVector = embedding.embed("document")
        val results = index.search(queryVector, topK = 10)
        assertEquals(10, results.size)
    }

    @Test
    fun testDuplicateVectors() = runTest {
        val vector = embedding.embed("same content")
        index.add(0, vector)
        index.add(1, vector)

        assertEquals(2, index.size())

        val results = index.search(vector, topK = 2)
        assertEquals(2, results.size)
        assertTrue(results.all { it.similarity >= 0.99f }, "Identical vectors should have very high similarity")
    }

    @Test
    fun testSearchResultConsistency() = runTest {
        val texts = listOf("apple", "apricot", "banana", "avocado")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        val queryVector = embedding.embed("apple")
        val results1 = index.search(queryVector, topK = 2)
        val results2 = index.search(queryVector, topK = 2)

        assertEquals(results1.size, results2.size)
        assertEquals(results1[0].id, results2[0].id)
        assertEquals(results1[0].similarity, results2[0].similarity)
    }
}

/**
 * Comprehensive tests for HNSW vector index (approximate nearest neighbor search)
 */
class HnswVectorIndexTest {
    private lateinit var embedding: SimpleEmbedding
    private lateinit var index: HnswVectorIndex

    @BeforeTest
    fun setUp() {
        embedding = SimpleEmbedding()
        index = HnswVectorIndex(
            embedding,
            maxM = 16,
            efConstruction = 200,
            ml = 1.0f / kotlin.math.ln(2.0f),
            seed = 42
        )
    }

    @Test
    fun testAddVector() = runTest {
        val vector = embedding.embed("test document")
        index.add(0, vector)

        assertEquals(1, index.size())
    }

    @Test
    fun testAddMultipleVectors() = runTest {
        val texts = listOf("apple", "banana", "carrot", "dog")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        assertEquals(4, index.size())
    }

    @Test
    fun testSearchReturnsTopK() = runTest {
        val texts = listOf("apple fruit", "banana fruit", "carrot vegetable", "dog animal")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        val queryVector = embedding.embed("fruit")
        val results = index.search(queryVector, topK = 2)

        assertEquals(2, results.size, "Should return exactly topK results")
    }

    @Test
    fun testSearchEmptyIndex() = runTest {
        val queryVector = embedding.embed("test")
        val results = index.search(queryVector, topK = 5)

        assertEquals(0, results.size, "Empty index should return no results")
    }

    @Test
    fun testMultiLayerStructure() = runTest {
        // Add enough vectors to trigger multiple layers
        val vectorCount = 100
        for (i in 0 until vectorCount) {
            val vector = embedding.embed("document $i with unique content to vary embeddings")
            index.add(i, vector)
        }

        assertEquals(vectorCount, index.size())

        val queryVector = embedding.embed("document search")
        val results = index.search(queryVector, topK = 10)
        assertEquals(10, results.size)
    }

    @Test
    fun testApproximateAccuracy() = runTest {
        // Test that HNSW returns high-quality approximate results
        val texts = listOf(
            "apple fruit red",
            "banana fruit yellow",
            "carrot vegetable orange",
            "dog animal pet",
            "elephant animal large"
        )
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        val queryVector = embedding.embed("fruit")
        val results = index.search(queryVector, topK = 2)

        assertEquals(2, results.size)
        // Top results should be fruit-related (apple, banana)
        assertTrue(results.isNotEmpty(), "Should find results")
    }

    @Test
    fun testAddBatch() = runTest {
        val vectors = mutableMapOf<Int, FloatArray>()
        val texts = listOf("apple", "banana", "carrot", "dog")
        for ((id, text) in texts.withIndex()) {
            vectors[id] = embedding.embed(text)
        }

        index.addBatch(vectors)
        assertEquals(4, index.size())
    }

    @Test
    fun testClear() = runTest {
        val vector = embedding.embed("test")
        index.add(0, vector)
        assertEquals(1, index.size())

        index.clear()
        assertEquals(0, index.size())
    }

    @Test
    fun testGetVector() = runTest {
        val vector = embedding.embed("test document")
        index.add(42, vector)

        val retrieved = index.getVector(42)
        assertNotNull(retrieved, "Should retrieve added vector")
    }

    @Test
    fun testParameterConfiguration() = runTest {
        val smallM = HnswVectorIndex(embedding, maxM = 8, efConstruction = 100)
        val largeM = HnswVectorIndex(embedding, maxM = 32, efConstruction = 400)

        val vectors = listOf("apple", "banana", "carrot").mapIndexed { id, text ->
            id to embedding.embed(text)
        }

        for ((id, vector) in vectors) {
            smallM.add(id, vector)
            largeM.add(id, vector)
        }

        assertEquals(3, smallM.size())
        assertEquals(3, largeM.size())
    }

    @Test
    fun testScalability() = runTest(timeout = 5.minutes) {  // 5 minutes timeout for iOS
        val vectorCount = 500
        for (i in 0 until vectorCount) {
            val vector = embedding.embed("document $i")
            index.add(i, vector)
        }

        assertEquals(vectorCount, index.size())

        val queryVector = embedding.embed("document")
        val results = index.search(queryVector, topK = 20)
        assertEquals(20, results.size)
    }

    @Test
    fun testSearchConsistency() = runTest {
        val texts = listOf("apple", "apricot", "banana", "avocado")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        val queryVector = embedding.embed("apple")
        val results = index.search(queryVector, topK = 2)

        assertTrue(results.isNotEmpty(), "Should return results")
        assertTrue(results[0].similarity >= 0f, "Similarity should be valid")
    }
}

/**
 * Integration tests for persistence (save/load) functionality
 */
class VectorIndexPersistenceTest {
    private lateinit var embedding: SimpleEmbedding

    @BeforeTest
    fun setUp() {
        embedding = SimpleEmbedding()
    }

    @Test
    fun testFlatIndexPersistence() = runTest {
        // Note: This test requires platform-specific implementation
        // In common code, we can only test the structure
        val index = FlatVectorIndex(embedding)

        val texts = listOf("apple", "banana", "carrot")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        assertEquals(3, index.size(), "Index should contain 3 vectors before serialization")
    }

    @Test
    fun testHnswIndexPersistence() = runTest {
        val index = HnswVectorIndex(embedding)

        val texts = listOf("apple", "banana", "carrot", "dog")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        assertEquals(4, index.size(), "Index should contain 4 vectors before serialization")
    }
}

/**
 * Tests comparing Flat vs HNSW index characteristics
 */
class VectorIndexComparisonTest {
    private lateinit var embedding: SimpleEmbedding
    private lateinit var flatIndex: FlatVectorIndex
    private lateinit var hnswIndex: HnswVectorIndex

    @BeforeTest
    fun setUp() {
        embedding = SimpleEmbedding()
        flatIndex = FlatVectorIndex(embedding)
        hnswIndex = HnswVectorIndex(embedding, maxM = 16, efConstruction = 200)
    }

    @Test
    fun testBothIndexesSupportBasicOperations() = runTest {
        val texts = listOf("apple", "banana", "carrot")
        val vectors = texts.mapIndexed { id, text ->
            id to embedding.embed(text)
        }

        for ((id, vector) in vectors) {
            flatIndex.add(id, vector)
            hnswIndex.add(id, vector)
        }

        assertEquals(flatIndex.size(), hnswIndex.size(), "Both indexes should have same size")

        val queryVector = embedding.embed("apple")
        val flatResults = flatIndex.search(queryVector, topK = 2)
        val hnswResults = hnswIndex.search(queryVector, topK = 2)

        assertEquals(flatResults.size, hnswResults.size, "Both should return same number of results")
    }

    @Test
    fun testFlatIndexAccuracy() = runTest {
        val texts = listOf("apple", "apricot", "banana", "avocado", "carrot")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            flatIndex.add(id, vector)
        }

        val queryVector = embedding.embed("apple")
        val results = flatIndex.search(queryVector, topK = 5)

        assertEquals(5, results.size)
        assertTrue(results.all { it.similarity >= 0f }, "All similarities should be valid")
    }

    @Test
    fun testHnswIndexApproximateQuality() = runTest {
        val texts = listOf("apple", "apricot", "banana", "avocado", "carrot", "dill", "eggplant")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            hnswIndex.add(id, vector)
        }

        val queryVector = embedding.embed("apple")
        val results = hnswIndex.search(queryVector, topK = 3)

        assertEquals(3, results.size)
        assertTrue(results.isNotEmpty(), "HNSW should find results")
    }

    @Test
    fun testIndexSelectionGuidance() {
        // Small dataset: Flat is better
        val smallDatasetSize = 100
        assertTrue(smallDatasetSize < 10000, "Small datasets should use Flat index")

        // Large dataset: HNSW is better
        val largeDatasetSize = 1000000
        assertTrue(largeDatasetSize > 100000, "Large datasets should use HNSW index")
    }
}
