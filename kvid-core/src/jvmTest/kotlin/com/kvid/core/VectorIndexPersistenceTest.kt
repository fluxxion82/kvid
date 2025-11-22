package com.kvid.core

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.*

/**
 * JVM-specific persistence tests for vector indexes
 * Tests save/load functionality with actual file I/O
 */
class JvmVectorIndexPersistenceTest {
    private lateinit var embedding: SimpleEmbedding
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        embedding = SimpleEmbedding()
        tempDir = Files.createTempDirectory("kvid-test-").toFile()
        tempDir.deleteOnExit()
    }

    @Test
    fun testFlatIndexSaveAndLoad() = runTest {
        val indexPath = File(tempDir, "flat-index.bin").absolutePath
        val index = JvmFlatVectorIndex(embedding)

        val vectors = listOf("apple", "banana", "carrot", "dog")
        for ((id, text) in vectors.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        assertEquals(4, index.size(), "Should have 4 vectors before save")
        index.save(indexPath)
        assertTrue(File(indexPath).exists(), "Index file should be created")

        val loadedIndex = JvmFlatVectorIndex(embedding)
        loadedIndex.load(indexPath)
        assertEquals(4, loadedIndex.size(), "Loaded index should have 4 vectors")
    }

    @Test
    fun testFlatIndexSearchAfterLoad() = runTest {
        val indexPath = File(tempDir, "flat-search-test.bin").absolutePath
        val index = JvmFlatVectorIndex(embedding)

        val texts = listOf("apple fruit", "banana fruit", "carrot vegetable")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        index.save(indexPath)

        val loadedIndex = JvmFlatVectorIndex(embedding)
        loadedIndex.load(indexPath)

        val queryVector = embedding.embed("fruit")
        val results = loadedIndex.search(queryVector, topK = 2)

        assertEquals(2, results.size, "Should find 2 similar results")
    }

    @Test
    fun testHnswIndexSaveAndLoad() = runTest {
        val indexPath = File(tempDir, "hnsw-index.bin").absolutePath
        val index = JvmHnswVectorIndex(embedding, maxM = 16, efConstruction = 200)

        val vectors = listOf("apple", "banana", "carrot", "dog", "elephant")
        for ((id, text) in vectors.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        assertEquals(5, index.size(), "Should have 5 vectors before save")

        index.save(indexPath)
        assertTrue(File(indexPath).exists(), "HNSW index file should be created")

        val loadedIndex = JvmHnswVectorIndex(embedding, maxM = 16, efConstruction = 200)
        loadedIndex.load(indexPath)
        assertEquals(5, loadedIndex.size(), "Loaded HNSW index should have 5 vectors")
    }

    @Test
    fun testHnswIndexSearchAfterLoad() = runTest {
        val indexPath = File(tempDir, "hnsw-search-test.bin").absolutePath
        val index = JvmHnswVectorIndex(embedding)

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

        index.save(indexPath)

        val loadedIndex = JvmHnswVectorIndex(embedding)
        loadedIndex.load(indexPath)

        val queryVector = embedding.embed("fruit")
        val results = loadedIndex.search(queryVector, topK = 3)

        assertTrue(results.isNotEmpty(), "Should find results after load")
        assertEquals(3, results.size, "Should return topK results")
    }

    @Test
    fun testLargeIndexPersistence() = runTest {
        val indexPath = File(tempDir, "large-index.bin").absolutePath
        val index = JvmFlatVectorIndex(embedding)

        val vectorCount = 500
        for (i in 0 until vectorCount) {
            val vector = embedding.embed("document $i with unique content")
            index.add(i, vector)
        }

        assertEquals(vectorCount, index.size())

        index.save(indexPath)
        assertTrue(File(indexPath).exists(), "Large index should be saved")

        val loadedIndex = JvmFlatVectorIndex(embedding)
        loadedIndex.load(indexPath)
        assertEquals(vectorCount, loadedIndex.size(), "Loaded index should have all vectors")

        val queryVector = embedding.embed("document search")
        val results = loadedIndex.search(queryVector, topK = 10)
        assertEquals(10, results.size, "Search should work on large loaded index")
    }

    @Test
    fun testMultipleIndexesInSameDirectory() = runTest {
        val flatPath = File(tempDir, "index1.bin").absolutePath
        val hnswPath = File(tempDir, "index2.bin").absolutePath

        val flatIndex = JvmFlatVectorIndex(embedding)
        val texts = listOf("apple", "banana", "carrot")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            flatIndex.add(id, vector)
        }
        flatIndex.save(flatPath)

        val hnswIndex = JvmHnswVectorIndex(embedding)
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            hnswIndex.add(id, vector)
        }
        hnswIndex.save(hnswPath)

        assertTrue(File(flatPath).exists(), "Flat index file should exist")
        assertTrue(File(hnswPath).exists(), "HNSW index file should exist")

        val loadedFlat = JvmFlatVectorIndex(embedding)
        loadedFlat.load(flatPath)

        val loadedHnsw = JvmHnswVectorIndex(embedding)
        loadedHnsw.load(hnswPath)

        assertEquals(loadedFlat.size(), loadedHnsw.size(), "Both indexes should have same size")
    }

    @Test
    fun testIndexPersistenceWithSpecialCharacters() = runTest {
        val indexPath = File(tempDir, "special-chars.bin").absolutePath
        val index = JvmFlatVectorIndex(embedding)

        val texts = listOf(
            "Hello @#$% World",
            "Test with émojis 😀",
            "Numbers 123 456 789"
        )
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        index.save(indexPath)

        val loadedIndex = JvmFlatVectorIndex(embedding)
        loadedIndex.load(indexPath)

        assertEquals(3, loadedIndex.size(), "Should handle special characters")
    }

    @Test
    fun testIndexVersioning() = runTest {
        val indexPath = File(tempDir, "versioned-index.bin").absolutePath
        val index = JvmHnswVectorIndex(embedding)

        val vector = embedding.embed("test")
        index.add(0, vector)
        index.save(indexPath)

        val fileContent = File(indexPath).readText()
        assertTrue(
            fileContent.contains("HNSW"),
            "HNSW index should contain version identifier"
        )
    }

    @Test
    fun testClearedIndexPersistence() = runTest {
        val indexPath = File(tempDir, "cleared-index.bin").absolutePath
        val index = JvmFlatVectorIndex(embedding)

        val vector = embedding.embed("test")
        index.add(0, vector)
        assertEquals(1, index.size())

        index.clear()
        assertEquals(0, index.size())

        index.save(indexPath)
        assertTrue(File(indexPath).exists(), "Cleared index should still be saved")

        val loadedIndex = JvmFlatVectorIndex(embedding)
        loadedIndex.load(indexPath)
        assertEquals(0, loadedIndex.size(), "Loaded cleared index should be empty")
    }

    @Test
    fun testIndexPersistenceRoundTrip() = runTest {
        val paths = listOf(
            File(tempDir, "round-trip-1.bin").absolutePath,
            File(tempDir, "round-trip-2.bin").absolutePath
        )

        val index = JvmFlatVectorIndex(embedding)

        val texts = listOf("alpha", "beta", "gamma", "delta")
        for ((id, text) in texts.withIndex()) {
            val vector = embedding.embed(text)
            index.add(id, vector)
        }

        index.save(paths[0])
        val loaded1 = JvmFlatVectorIndex(embedding)
        loaded1.load(paths[0])

        loaded1.save(paths[1])
        val loaded2 = JvmFlatVectorIndex(embedding)
        loaded2.load(paths[1])

        assertEquals(index.size(), loaded1.size())
        assertEquals(loaded1.size(), loaded2.size())
        assertEquals(4, loaded2.size())
    }

    @Test
    fun testIndexFileNotFound() = runTest {
        val nonexistentPath = File(tempDir, "nonexistent.bin").absolutePath
        val index = JvmFlatVectorIndex(embedding)

        index.load(nonexistentPath)
        assertNotNull(index)
    }

    @Test
    fun testIndexCreateDirectoriesIfMissing() = runTest {
        val nestedPath = File(tempDir, "nested/deep/index.bin").absolutePath
        val index = JvmFlatVectorIndex(embedding)

        val vector = embedding.embed("test")
        index.add(0, vector)

        index.save(nestedPath)
        assertTrue(File(nestedPath).exists(), "Save should create missing directories")
    }
}

/**
 * Factory function tests for JVM implementations
 */
class JvmVectorIndexFactoriesTest {
    private lateinit var embedding: SimpleEmbedding

    @BeforeTest
    fun setUp() {
        embedding = SimpleEmbedding()
    }

    @Test
    fun testCreateHnswIndexFactory() = runTest {
        val index = createHnswIndex(embedding)
        assertNotNull(index)
        assertTrue(index is VectorIndex)

        val vector = embedding.embed("test")
        index.add(0, vector)
        assertEquals(1, index.size())
    }

    @Test
    fun testCreateFlatIndexFactory() = runTest {
        val index = createFlatIndex(embedding)
        assertNotNull(index)
        assertTrue(index is VectorIndex)

        val vector = embedding.embed("test")
        index.add(0, vector)
        assertEquals(1, index.size())
    }

    @Test
    fun testHnswFactoryWithParameters() = runTest {
        val index = createHnswIndex(
            embedding,
            maxM = 32,
            efConstruction = 400,
            seed = 123
        )

        assertNotNull(index)

        val vectors = listOf("a", "b", "c").mapIndexed { id, text ->
            id to embedding.embed(text)
        }

        for ((id, vector) in vectors) {
            index.add(id, vector)
        }

        assertEquals(3, index.size())
    }
}
