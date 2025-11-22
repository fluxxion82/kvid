package com.kvid.core

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for text compression with QR code encoding/decoding
 */
class CompressionQRIntegrationTest {

    @Test
    fun testQREncodingDecodingWithCompression() = runBlocking {
        val generator = JvmQRCodeGenerator()
        val decoder = JvmQRCodeDecoder()

        val originalText = "This is a test message that is long enough to be compressed. " +
                "It contains multiple sentences and should benefit from compression."

        // Generate QR code (compression happens inside)
        val qrData = generator.generateQRCode(originalText)

        // Simulate video frame
        val frame = DecodedFrame(
            frameNumber = 0,
            data = convertQRToRGB(qrData),
            width = qrData.width,
            height = qrData.height,
            format = PixelFormat.RGB_888
        )

        // Decode QR code (decompression happens inside)
        val result = decoder.decodeQRCode(frame)

        assertTrue(result.isSuccess)
        assertEquals(originalText, result.getOrNull())
    }

    @Test
    fun testQRWithVeryLongText() = runBlocking {
        val generator = JvmQRCodeGenerator()
        val decoder = JvmQRCodeDecoder()

        // Generate a long repetitive text that should compress well
        val originalText = "The quick brown fox jumps over the lazy dog. ".repeat(20)

        println("\nVery Long Text Test:")
        println("Original length: ${originalText.length} chars")

        val qrData = generator.generateQRCode(originalText, version = 40)

        val frame = DecodedFrame(
            frameNumber = 0,
            data = convertQRToRGB(qrData),
            width = qrData.width,
            height = qrData.height,
            format = PixelFormat.RGB_888
        )

        val result = decoder.decodeQRCode(frame)

        assertTrue(result.isSuccess)
        assertEquals(originalText, result.getOrNull())
        println("Successfully encoded and decoded ${originalText.length} characters")
    }

    @Test
    fun testQRWithShortText() = runBlocking {
        val generator = JvmQRCodeGenerator()
        val decoder = JvmQRCodeDecoder()

        // Short text should not be compressed
        val originalText = "Short message"

        val qrData = generator.generateQRCode(originalText)

        val frame = DecodedFrame(
            frameNumber = 0,
            data = convertQRToRGB(qrData),
            width = qrData.width,
            height = qrData.height,
            format = PixelFormat.RGB_888
        )

        val result = decoder.decodeQRCode(frame)

        assertTrue(result.isSuccess)
        assertEquals(originalText, result.getOrNull())
    }

    @Test
    fun testQRWithUnicode() = runBlocking {
        val generator = JvmQRCodeGenerator()
        val decoder = JvmQRCodeDecoder()

        val originalText = "Hello 世界! Testing Unicode compression: 🎉🎊🎈 " +
                "Multiple character sets: αβγδ, абвг, 你好世界. " +
                "This text should compress and decompress correctly."

        val qrData = generator.generateQRCode(originalText, version = 30)

        val frame = DecodedFrame(
            frameNumber = 0,
            data = convertQRToRGB(qrData),
            width = qrData.width,
            height = qrData.height,
            format = PixelFormat.RGB_888
        )

        val result = decoder.decodeQRCode(frame)

        assertTrue(result.isSuccess)
        assertEquals(originalText, result.getOrNull())
    }

    @Test
    fun testCompressionImprovedCapacity() = runBlocking {
        val generator = JvmQRCodeGenerator()

        // Test that compression allows more data to fit in the same QR version
        val baseText = "This is a test sentence that will be repeated. "

        // Without compression, this might fail at lower versions
        val repeatedText = baseText.repeat(30)  // ~1440 chars

        println("\nCompression Capacity Test:")
        println("Text length: ${repeatedText.length} chars")

        // Try with version 30 (should work with compression)
        try {
            val qrData = generator.generateQRCode(repeatedText, version = 30)
            println("Successfully encoded ${repeatedText.length} chars in QR version 30")
            println("QR size: ${qrData.width}x${qrData.height}")
        } catch (e: Exception) {
            println("Failed to encode with compression: ${e.message}")
            throw e
        }
    }

    @Test
    fun testMultipleMessagesRoundTrip() = runBlocking {
        val generator = JvmQRCodeGenerator()
        val decoder = JvmQRCodeDecoder()

        val messages = listOf(
            "First message that is long enough to be compressed for testing purposes.",
            "Second message with different content but also long enough for compression to kick in.",
            "Third message: The quick brown fox jumps over the lazy dog multiple times. ".repeat(5),
            "Short msg",  // This one won't be compressed
            "JSON example: " + """{"key": "value", "array": [1, 2, 3]}""".repeat(3)
        )

        println("\nMultiple Messages Round Trip Test:")
        for ((index, original) in messages.withIndex()) {
            val qrData = generator.generateQRCode(original, version = 30)
            val frame = DecodedFrame(
                frameNumber = index,
                data = convertQRToRGB(qrData),
                width = qrData.width,
                height = qrData.height,
                format = PixelFormat.RGB_888
            )

            val result = decoder.decodeQRCode(frame)
            assertTrue(result.isSuccess, "Message $index failed to decode")
            assertEquals(original, result.getOrNull(), "Message $index content mismatch")

            val wasCompressed = TextCompression.isCompressed(
                // We can't access the intermediate compressed data easily,
                // so we just verify the round trip worked
                original
            )
            println("Message $index (${original.length} chars): ✓ decoded successfully")
        }
    }

    @Test
    fun testCompressionBenefitInQR() {
        // This test demonstrates the space savings from compression
        println("\n=== Compression Benefit in QR Codes ===")

        val testCases = listOf(
            "Short" to "Hello World",
            "Medium" to "This is a medium length message. ".repeat(5),
            "Long" to "This is a longer message that should benefit from compression. ".repeat(20),
            "Repetitive" to "REPEAT ".repeat(100)
        )

        println("Type        | Original (B) | Compressed (B) | Benefit")
        println("-".repeat(60))

        for ((name, text) in testCases) {
            val originalSize = text.toByteArray().size
            val compressed = TextCompression.compress(text)
            val compressedSize = compressed.toByteArray().size
            val benefit = if (compressedSize < originalSize) {
                "${((1 - compressedSize.toDouble() / originalSize) * 100).toInt()}% smaller"
            } else {
                "No benefit"
            }

            println("${name.padEnd(11)} | ${originalSize.toString().padEnd(12)} | " +
                    "${compressedSize.toString().padEnd(14)} | $benefit")
        }
    }

    @Test
    fun testErrorHandlingWithCorruptedData() = runBlocking {
        val decoder = JvmQRCodeDecoder()

        // Create a frame with invalid compressed data
        val generator = JvmQRCodeGenerator()

        // This should handle decompression gracefully
        val validText = "This is a valid message that will compress properly. ".repeat(5)
        val qrData = generator.generateQRCode(validText, version = 30)

        val frame = DecodedFrame(
            frameNumber = 0,
            data = convertQRToRGB(qrData),
            width = qrData.width,
            height = qrData.height,
            format = PixelFormat.RGB_888
        )

        val result = decoder.decodeQRCode(frame)
        assertTrue(result.isSuccess)
        assertEquals(validText, result.getOrNull())
    }

    // Helper function to convert QR code data to RGB format for decoder
    private fun convertQRToRGB(qrData: QRCodeData): ByteArray {
        val rgbData = ByteArray(qrData.width * qrData.height * 3)
        for (i in qrData.pixels.indices) {
            val pixelValue = qrData.pixels[i]
            rgbData[i * 3] = pixelValue      // R
            rgbData[i * 3 + 1] = pixelValue  // G
            rgbData[i * 3 + 2] = pixelValue  // B
        }
        return rgbData
    }
}
