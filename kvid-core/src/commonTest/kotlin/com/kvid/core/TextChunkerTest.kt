package com.kvid.core

import kotlin.test.*

class TextChunkerTest {
    private lateinit var chunker: TextChunker

    @BeforeTest
    fun setUp() {
        chunker = TextChunker(chunkSize = 100, overlapSize = 10)
    }

    @Test
    fun testEmptyText() {
        val result = chunker.chunk("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun testSingleChunk() {
        val text = "This is a short text."
        val result = chunker.chunk(text)

        assertEquals(1, result.size)
        assertEquals(text.trim(), result[0].content)
    }

    @Test
    fun testMultipleChunks() {
        val text = "a".repeat(250)  // 250 chars with chunkSize=100
        val result = chunker.chunk(text)

        assertTrue(result.size > 1, "Should create multiple chunks")
        result.forEach { chunk ->
            assertTrue(chunk.content.isNotEmpty())
        }
    }

    @Test
    fun testOverlapPreservation() {
        val text = "This is a test. This is a test. This is a test."
        val result = chunker.chunk(text)

        if (result.size > 1) {
            // Check that consecutive chunks have overlap
            for (i in 0 until result.size - 1) {
                val chunk1 = result[i].content
                val chunk2 = result[i + 1].content

                // There should be some text that appears in both chunks
                assertTrue(
                    chunk1.takeLast(20).contains(chunk2.take(10)) ||
                    chunk2.takeLast(20).contains(chunk1.take(10)) ||
                    result[i].endOffset > result[i + 1].startOffset,
                    "Chunks should have overlap"
                )
            }
        }
    }

    @Test
    fun testChunkMetadata() {
        val text = "Hello world. This is a test."
        val result = chunker.chunk(text)

        result.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.sequenceNumber)
            assertNotEquals(-1, chunk.startOffset)
            assertNotEquals(-1, chunk.endOffset)
            assertTrue(chunk.endOffset > chunk.startOffset)
        }
    }

    @Test
    fun testSentencePreservation() {
        // chunkSize 25 lands mid-sentence; with preserveSentences the chunk extends to the sentence end.
        val chunker = TextChunker(chunkSize = 25, overlapSize = 0, preserveSentences = true)
        val text = "First sentence. Second sentence. Third sentence. Fourth sentence."

        val result = chunker.chunk(text)

        assertEquals(
            listOf("First sentence. Second sentence.", "Third sentence. Fourth sentence."),
            result.map { it.content }
        )
        result.forEach { chunk ->
            assertTrue(chunk.content.last() in listOf('.', '!', '?'), "Chunk should end at a sentence boundary: '${chunk.content}'")
        }
    }

    @Test
    fun testSentencePreservationDisabled() {
        val chunker = TextChunker(chunkSize = 25, overlapSize = 0, preserveSentences = false)
        val text = "First sentence. Second sentence. Third sentence. Fourth sentence."

        val result = chunker.chunk(text)

        assertEquals("First sentence. Second se", result[0].content)
    }

    @Test
    fun testTokenEstimation() {
        val text = "This is a test"
        val tokens = TextChunker.estimateTokens(text)

        assertTrue(tokens > 0)
        assertTrue(tokens <= text.length / 2)
    }
}
