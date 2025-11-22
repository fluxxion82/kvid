package com.kvid.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Integration tests for KVID - testing full pipelines and multi-component interactions
 */
class TextEmbeddingIntegrationTest {
    private lateinit var chunker: TextChunker
    private lateinit var embedding: SimpleEmbedding
    private lateinit var index: FlatVectorIndex

    @BeforeTest
    fun setUp() {
        chunker = TextChunker(chunkSize = 100, overlapSize = 10)
        embedding = SimpleEmbedding()
        index = FlatVectorIndex(embedding)
    }

    @Test
    fun testFullChunkingAndEmbeddingPipeline() = runTest {
        val document = """
            The quick brown fox jumps over the lazy dog.
            This is a test document with multiple sentences.
            It demonstrates how the system chunks and embeds text.
            Each chunk is processed independently for embedding.
        """.trimIndent()

        // Step 1: Chunk the document
        val chunks = chunker.chunk(document)
        assertTrue(chunks.isNotEmpty(), "Should create chunks")

        // Step 2: Embed each chunk
        val vectors = mutableMapOf<Int, FloatArray>()
        for ((idx, chunk) in chunks.withIndex()) {
            val vector = embedding.embed(chunk.content)
            vectors[idx] = vector
            index.add(idx, vector)
        }

        assertEquals(chunks.size, index.size(), "Should add all chunk vectors")

        // Step 3: Search for similar chunk
        val queryVector = embedding.embed("brown fox")
        val results = index.search(queryVector, topK = 2)

        assertTrue(results.isNotEmpty(), "Should find similar chunks")
    }

    @Test
    fun testLargeDocumentProcessing() = runTest {
        // Create a large document
        val largeDoc = (1..50).joinToString(" ") { "Sentence number $it about various topics." }

        val chunks = chunker.chunk(largeDoc)
        assertTrue(chunks.size > 1, "Large document should create multiple chunks")

        // Embed all chunks
        for ((idx, chunk) in chunks.withIndex()) {
            val vector = embedding.embed(chunk.content)
            index.add(idx, vector)
        }

        // Search should work on large index
        val queryVector = embedding.embed("Sentence")
        val results = index.search(queryVector, topK = 5)
        assertTrue(results.isNotEmpty(), "Should find results in large index")
    }

    @Test
    fun testChunkSequenceConsistency() = runTest {
        val text = "First. Second. Third. Fourth. Fifth."
        val chunks = chunker.chunk(text)

        // Verify chunks maintain sequence order
        for (i in chunks.indices) {
            val chunk = chunks[i]
            assertEquals(i, chunk.sequenceNumber, "Chunk $i should have correct sequence number")
        }

        // Embed and index in order
        for ((idx, chunk) in chunks.withIndex()) {
            val vector = embedding.embed(chunk.content)
            index.add(idx, vector)
        }

        assertEquals(chunks.size, index.size())
    }
}

/**
 * Memory store integration tests (high-level API)
 */
class MemoryStoreIntegrationTest {
    private lateinit var store: MemoryStore

    @BeforeTest
    fun setUp() {
        store = MemoryStore()
    }

    @Test
    fun testMemoryStoreBasicOperations() = runTest {
        val message = Message(id = 1, content = "This is a test document")

        // Store memory
        val storeResult = store.addMessage(message)
        assertTrue(storeResult.isSuccess, "Should store memory successfully")

        // Search and verify
        val searchResults = store.search("test document", topK = 1)
        assertTrue(searchResults.isSuccess, "Should search successfully")
        val results = searchResults.getOrNull()
        assertNotNull(results, "Should retrieve stored memory")
        assertTrue(results.isNotEmpty(), "Should find the stored message")
    }

    @Test
    fun testMemoryStoreSearch() = runTest {
        // Store multiple documents
        store.addMessage(Message(id = 1, content = "The quick brown fox jumps over the lazy dog"))
        store.addMessage(Message(id = 2, content = "A fast moving fox crosses the field"))
        store.addMessage(Message(id = 3, content = "Lazy animals sleep all day"))

        // Search for similar
        val searchResult = store.search("fox running", topK = 2)
        assertTrue(searchResult.isSuccess, "Should search successfully")
        val results = searchResult.getOrNull()
        assertNotNull(results, "Should get results")
        assertTrue(results.isNotEmpty(), "Should find similar documents")
    }

    @Test
    fun testMemoryStoreUpdate() = runTest {
        store.addMessage(Message(id = 1, content = "Original content"))

        // Update (add another message with same ID will create another chunk)
        val updateResult = store.addMessage(Message(id = 1, content = "Updated content"))
        assertTrue(updateResult.isSuccess, "Should add updated document")

        // Search should find the updated content
        val searchResult = store.search("Updated content", topK = 1)
        assertTrue(searchResult.isSuccess, "Should search successfully")
    }

    @Test
    fun testMemoryStoreMultipleDocuments() = runTest {
        val messages = listOf(
            Message(id = 1, content = "Document about technology and innovation"),
            Message(id = 2, content = "Information about artificial intelligence"),
            Message(id = 3, content = "Guide for machine learning applications"),
            Message(id = 4, content = "Tips for software development")
        )

        for (message in messages) {
            val result = store.addMessage(message)
            assertTrue(result.isSuccess, "Should store message ${message.id}")
        }

        // Search across all documents
        val searchResult = store.search("machine learning technology", topK = 3)
        assertTrue(searchResult.isSuccess, "Should search successfully")
        val results = searchResult.getOrNull()
        assertNotNull(results, "Should get results")
        assertTrue(results.isNotEmpty(), "Should search across multiple documents")
    }

    @Test
    fun testMemoryStoreClear() = runTest {
        store.addMessage(Message(id = 1, content = "Content 1"))
        store.addMessage(Message(id = 2, content = "Content 2"))

        val beforeClearResult = store.search("Content", topK = 10)
        assertTrue(beforeClearResult.isSuccess, "Should search successfully")
        val beforeClear = beforeClearResult.getOrNull()
        assertNotNull(beforeClear, "Should get results")
        assertTrue(beforeClear.isNotEmpty(), "Should have documents before clear")

        store.clear()

        val afterClearResult = store.search("Content", topK = 10)
        assertTrue(afterClearResult.isSuccess, "Should search successfully")
        val afterClear = afterClearResult.getOrNull()
        assertNotNull(afterClear, "Should get results")
        assertEquals(0, afterClear.size, "Should have no documents after clear")
    }
}

/**
 * End-to-end encoding/decoding pipeline tests
 */
class VideoEncodingDecodingIntegrationTest {

    @Test
    fun testVideoFrameDataValidation() {
        // Test that frame data is valid
        val width = 256
        val height = 256
        val pixelData = ByteArray(width * height * 3)

        val frameData = FrameData(
            data = pixelData,
            format = PixelFormat.RGB_888,
            width = width,
            height = height
        )

        assertEquals(width, frameData.width)
        assertEquals(height, frameData.height)
        assertEquals(pixelData.size, frameData.data.size)
    }

    @Test
    fun testVideoInfoComputations() {
        // Test video info calculations
        val totalFrames = 300
        val fps = 30
        val expectedDuration = totalFrames.toDouble() / fps

        val videoInfo = VideoInfo(
            totalFrames = totalFrames,
            width = 1920,
            height = 1080,
            fps = fps,
            duration = expectedDuration,
            codec = VideoCodec.H264
        )

        assertEquals(expectedDuration, videoInfo.duration, 0.01)
        assertTrue(videoInfo.totalFrames > 0)
        assertTrue(videoInfo.duration > 0)
    }

    @Test
    fun testMultipleVideoFormats() {
        val formats = listOf(
            PixelFormat.RGB_888,
            PixelFormat.YUV_420P
        )

        for (format in formats) {
            val frameData = FrameData(
                data = ByteArray(256 * 256 * 3),
                format = format,
                width = 256,
                height = 256
            )

            assertEquals(format, frameData.format)
        }
    }
}

/**
 * Cross-platform compatibility tests
 */
class CrossPlatformCompatibilityTest {

    @Test
    fun testEncodingParamsPortability() {
        val params = VideoEncodingParams(
            width = 1920,
            height = 1080,
            fps = 30,
            codec = VideoCodec.H264,
            preset = EncodingPreset.MEDIUM,
            qualityFactor = 25
        )

        // All fields should be accessible on any platform
        assertTrue(params.width > 0)
        assertTrue(params.height > 0)
        assertTrue(params.fps > 0)
        assertNotNull(params.codec)
        assertNotNull(params.preset)
        assertTrue(params.qualityFactor > 0)
    }

    @Test
    fun testVideoCodecEnumCoverage() {
        val codecs = listOf(
            VideoCodec.H264,
            VideoCodec.H265,
            VideoCodec.VP9,
            VideoCodec.AV1
        )

        for (codec in codecs) {
            assertNotNull(codec)
        }

        assertEquals(4, codecs.size)
    }

    @Test
    fun testEncodingPresetEnumCoverage() {
        val presets = listOf(
            EncodingPreset.ULTRAFAST,
            EncodingPreset.FAST,
            EncodingPreset.MEDIUM,
            EncodingPreset.SLOW,
            EncodingPreset.VERYSLOW
        )

        for (preset in presets) {
            assertNotNull(preset)
        }

        assertEquals(5, presets.size)
    }
}

/**
 * Performance and stress tests
 */
class PerformanceIntegrationTest {
    private lateinit var embedding: SimpleEmbedding

    @BeforeTest
    fun setUp() {
        embedding = SimpleEmbedding()
    }

    @Test
    fun testEmbeddingConsistency() = runTest {
        val text = "Test text for consistency"
        val vec1 = embedding.embed(text)
        val vec2 = embedding.embed(text)

        assertTrue(vec1.contentEquals(vec2), "Same text should produce identical embeddings")
    }

    @Test
    fun testBatchEmbedding() = runTest {
        val texts = listOf(
            "First document",
            "Second document",
            "Third document"
        )

        val vectors = texts.map { embedding.embed(it) }

        assertEquals(texts.size, vectors.size)
        vectors.forEach { vector ->
            assertEquals(384, vector.size, "Embedding dimension should be 384")
        }
    }

    @Test
    fun testLargeBatchProcessing() = runTest {
        val vectorCount = 1000
        val index = FlatVectorIndex(embedding)

        // Add many vectors
        for (i in 0 until vectorCount) {
            val vector = embedding.embed("Document $i")
            index.add(i, vector)
        }

        assertEquals(vectorCount, index.size())

        // Search should still work
        val queryVector = embedding.embed("Document")
        val results = index.search(queryVector, topK = 10)
        assertEquals(10, results.size)
    }
}
