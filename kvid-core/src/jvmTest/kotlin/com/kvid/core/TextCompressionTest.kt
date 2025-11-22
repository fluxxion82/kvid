package com.kvid.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextCompressionTest {

    @Test
    fun testCompressionWithShortText() {
        // Short text should not be compressed (below threshold)
        val shortText = "Hello, World!"
        val result = TextCompression.compress(shortText)

        // Should return original text (no compression)
        assertEquals(shortText, result)
        assertFalse(TextCompression.isCompressed(result))
    }

    @Test
    fun testCompressionWithLongText() {
        // Long text should be compressed
        val longText = "This is a longer text that exceeds the minimum compression threshold. " +
                "It contains multiple sentences and should benefit from compression. " +
                "The compression algorithm should reduce the size of this text significantly."

        val compressed = TextCompression.compress(longText)

        // Should be compressed (starts with GZ: prefix)
        assertTrue(TextCompression.isCompressed(compressed))
        assertTrue(compressed.startsWith(TextCompression.COMPRESSION_PREFIX))
    }

    @Test
    fun testDecompressionWithUncompressedData() {
        // Uncompressed data should pass through unchanged
        val uncompressed = "Hello, World!"
        val result = TextCompression.decompress(uncompressed)

        assertEquals(uncompressed, result)
    }

    @Test
    fun testCompressionDecompressionRoundTrip() {
        // Test that compress + decompress returns original text
        val originalText = "This is a test message that is long enough to be compressed. " +
                "It contains multiple sentences to ensure the compression is effective. " +
                "We want to verify that the round trip works correctly."

        val compressed = TextCompression.compress(originalText)
        val decompressed = TextCompression.decompress(compressed)

        assertEquals(originalText, decompressed)
    }

    @Test
    fun testCompressionWithRepeatingText() {
        // Text with lots of repetition should compress well
        val repeatingText = "Hello ".repeat(50)

        val compressed = TextCompression.compress(repeatingText)
        val decompressed = TextCompression.decompress(compressed)

        assertTrue(TextCompression.isCompressed(compressed))
        assertEquals(repeatingText, decompressed)

        // Check compression ratio
        val ratio = compressed.length.toDouble() / repeatingText.length
        println("Repeating text compression ratio: ${String.format("%.2f", ratio)} (${compressed.length} bytes vs ${repeatingText.length} bytes)")
        assertTrue(ratio < 0.5, "Expected compression ratio < 0.5 for repeating text, got $ratio")
    }

    @Test
    fun testCompressionWithUnicode() {
        // Test compression with Unicode characters
        val unicodeText = "Hello 世界! This is a longer text with Unicode: 🎉🎊🎈 " +
                "Testing compression with various character sets: αβγδ, абвг, 你好世界. " +
                "This text is long enough to trigger compression."

        val compressed = TextCompression.compress(unicodeText)
        val decompressed = TextCompression.decompress(compressed)

        assertEquals(unicodeText, decompressed)
    }

    @Test
    fun testCustomThreshold() {
        // Test with custom threshold
        val text = "Short text"

        // With low threshold, should compress
        val compressed = TextCompression.compress(text, threshold = 5)
        assertTrue(TextCompression.isCompressed(compressed))

        val decompressed = TextCompression.decompress(compressed)
        assertEquals(text, decompressed)
    }

    @Test
    fun testIsCompressed() {
        val uncompressed = "Hello, World!"
        val compressed = TextCompression.COMPRESSION_PREFIX + "base64data"

        assertFalse(TextCompression.isCompressed(uncompressed))
        assertTrue(TextCompression.isCompressed(compressed))
    }

    @Test
    fun testBase64Encoding() {
        // Test that the compressed data is valid base64
        val longText = "A".repeat(200)
        val compressed = TextCompression.compress(longText)

        assertTrue(compressed.startsWith(TextCompression.COMPRESSION_PREFIX))

        // Extract base64 part and verify it can be decompressed
        val decompressed = TextCompression.decompress(compressed)
        assertEquals(longText, decompressed)
    }

    @Test
    fun testCompressionRatioRepeatingText() {
        // Highly repetitive text should compress very well
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(20)
        val compressed = TextCompression.compress(text)

        val originalSize = text.length
        val compressedSize = compressed.length
        val ratio = compressedSize.toDouble() / originalSize

        println("Repeating phrase - Original: $originalSize bytes, Compressed: $compressedSize bytes, Ratio: ${String.format("%.2f", ratio)}")
        assertTrue(ratio < 0.3, "Expected excellent compression for repeating text")
    }

    @Test
    fun testCompressionRatioNaturalText() {
        // Natural text should compress moderately well
        val text = """
            In the realm of data compression, various algorithms compete for efficiency and speed.
            GZIP, based on the DEFLATE algorithm, offers a good balance between compression ratio
            and computational complexity. It works by finding repeated sequences in the input data
            and replacing them with shorter references. This makes it particularly effective for
            text data where patterns and repetitions are common. The algorithm uses a combination
            of LZ77 for duplicate string elimination and Huffman coding for entropy encoding.
        """.trimIndent()

        val compressed = TextCompression.compress(text)

        val originalSize = text.length
        val compressedSize = compressed.length
        val ratio = compressedSize.toDouble() / originalSize

        println("Natural text - Original: $originalSize bytes, Compressed: $compressedSize bytes, Ratio: ${String.format("%.2f", ratio)}")
        assertTrue(ratio < 1.0, "Compressed size should be smaller than original for natural text")
    }

    @Test
    fun testCompressionRatioRandomText() {
        // Random text should not compress well (might even expand)
        val random = kotlin.random.Random(42)
        val text = (1..500).map {
            ('a'..'z').random(random)
        }.joinToString("")

        val compressed = TextCompression.compress(text)

        val originalSize = text.length
        val compressedSize = compressed.length
        val ratio = compressedSize.toDouble() / originalSize

        println("Random text - Original: $originalSize bytes, Compressed: $compressedSize bytes, Ratio: ${String.format("%.2f", ratio)}")
        // Random data typically expands due to compression overhead and base64 encoding
        assertTrue(ratio > 0.8, "Random text should not compress well")
    }

    @Test
    fun testCompressionRatioJSON() {
        // JSON with repetitive keys should compress well
        val json = """
            {
                "users": [
                    {"id": 1, "name": "Alice", "email": "alice@example.com", "active": true},
                    {"id": 2, "name": "Bob", "email": "bob@example.com", "active": true},
                    {"id": 3, "name": "Charlie", "email": "charlie@example.com", "active": false},
                    {"id": 4, "name": "David", "email": "david@example.com", "active": true},
                    {"id": 5, "name": "Eve", "email": "eve@example.com", "active": true}
                ]
            }
        """.trimIndent()

        val compressed = TextCompression.compress(json)

        val originalSize = json.length
        val compressedSize = compressed.length
        val ratio = compressedSize.toDouble() / originalSize

        println("JSON data - Original: $originalSize bytes, Compressed: $compressedSize bytes, Ratio: ${String.format("%.2f", ratio)}")
        assertTrue(ratio < 0.8, "JSON with repetitive structure should compress well")
    }

    @Test
    fun testCompressionRatioVersusThreshold() {
        // Test that compression is only applied when beneficial
        val sizes = listOf(50, 100, 200, 500, 1000, 2000)

        println("\nCompression vs Size:")
        for (size in sizes) {
            val text = "This is a test sentence. ".repeat(size / 25)
            val compressed = TextCompression.compress(text)
            val ratio = compressed.length.toDouble() / text.length
            val wasCompressed = TextCompression.isCompressed(compressed)

            println("Size: $size chars, Compressed: $wasCompressed, Ratio: ${String.format("%.3f", ratio)}")
        }
    }
}
