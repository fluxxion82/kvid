package com.kvid.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * QR Code integration tests (JVM-specific)
 */
class QRCodeIntegrationTest {
    private lateinit var qrGenerator: JvmQRCodeGenerator
    private lateinit var qrDecoder: JvmQRCodeDecoder

    @BeforeTest
    fun setUp() {
        qrGenerator = JvmQRCodeGenerator()
        qrDecoder = JvmQRCodeDecoder()
    }

    @Test
    fun testQRRoundTrip() = runTest {
        val originalText = "Hello, QR Code!"

        val qrData = qrGenerator.generateQRCode(originalText, version = 5, errorCorrection = "M")
        assertNotNull(qrData, "QR data should not be null")

        val rgbData = ByteArray(qrData.width * qrData.height * 3)
        for (i in qrData.pixels.indices) {
            val grayValue = qrData.pixels[i]
            rgbData[i * 3] = grayValue      // R
            rgbData[i * 3 + 1] = grayValue  // G
            rgbData[i * 3 + 2] = grayValue  // B
        }

        val frameData = DecodedFrame(
            frameNumber = 0,
            data = rgbData,
            width = qrData.width,
            height = qrData.height,
            format = PixelFormat.RGB_888
        )

        val decoded = qrDecoder.decodeQRCode(frameData)
        assertTrue(decoded.isSuccess, "Should decode QR code")
        assertEquals(originalText, decoded.getOrNull(), "Decoded text should match original")
    }

    @Test
    fun testQRWithLargeData() = runTest {
        val largeText = "A".repeat(1000)  // Large data

        val qrData = qrGenerator.generateQRCode(largeText, version = 40, errorCorrection = "L")
        assertNotNull(qrData, "QR data should not be null")
        assertTrue(qrData.width > 0, "Should generate QR code with valid dimensions")
        assertTrue(qrData.height > 0, "Should generate QR code with valid dimensions")
    }

    @Test
    fun testQRWithSpecialCharacters() = runTest {
        val specialText = "Special chars: @#$%^&*()"

        val qrData = qrGenerator.generateQRCode(specialText, version = 5, errorCorrection = "M")
        assertNotNull(qrData, "QR data should not be null")

        val rgbData = ByteArray(qrData.width * qrData.height * 3)
        for (i in qrData.pixels.indices) {
            val grayValue = qrData.pixels[i]
            rgbData[i * 3] = grayValue      // R
            rgbData[i * 3 + 1] = grayValue  // G
            rgbData[i * 3 + 2] = grayValue  // B
        }

        val frameData = DecodedFrame(
            frameNumber = 0,
            data = rgbData,
            width = qrData.width,
            height = qrData.height,
            format = PixelFormat.RGB_888
        )

        val decoded = qrDecoder.decodeQRCode(frameData)
        assertTrue(decoded.isSuccess, "Should decode special characters")
        assertEquals(specialText, decoded.getOrNull(), "Decoded text should match original")
    }
}
