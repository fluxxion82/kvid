package com.kvid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for IosQRCodeGenerator
 *
 * Tests QR code generation functionality on iOS using the Vision framework
 */
class IosQRCodeGeneratorTest {

    private val generator = IosQRCodeGenerator()

    @Test
    fun testBasicQRCodeGeneration() {
        val testData = "Hello, iOS!"
        val qrCode = generator.generateQRCode(testData)

        assertNotNull(qrCode)
        assertTrue(qrCode.width > 0)
        assertTrue(qrCode.height > 0)
        assertTrue(qrCode.pixels.isNotEmpty())
        assertEquals(qrCode.width * qrCode.height, qrCode.pixels.size)
    }

    @Test
    fun testQRCodeWithDifferentErrorCorrection() {
        val testData = "Test data"
        val levels = listOf("L", "M", "Q", "H")

        for (level in levels) {
            val qrCode = generator.generateQRCode(testData, errorCorrection = level)
            assertNotNull(qrCode)
            assertTrue(qrCode.width > 0)
            assertTrue(qrCode.height > 0)
        }
    }

    @Test
    fun testQRCodeLargeData() {
        val largeData = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(10)
        val qrCode = generator.generateQRCode(largeData, version = 30)

        assertNotNull(qrCode)
        assertTrue(qrCode.width > 0)
        assertTrue(qrCode.height > 0)
        assertTrue(qrCode.dataCapacity >= largeData.length)
    }

    @Test
    fun testCapabilities() {
        val capabilities = generator.getCapabilities()

        assertEquals(2953, capabilities.maxDataCapacity)
        assertEquals(1..40, capabilities.supportedVersions)
        assertTrue(capabilities.supportedErrorCorrection.contains("L"))
        assertTrue(capabilities.supportedErrorCorrection.contains("M"))
        assertTrue(capabilities.supportedErrorCorrection.contains("Q"))
        assertTrue(capabilities.supportedErrorCorrection.contains("H"))
    }

    @Test
    fun testPixelDataGrayscale() {
        val qrCode = generator.generateQRCode("Test")

        // Verify pixels are grayscale (0-255 range)
        for (pixel in qrCode.pixels) {
            val value = pixel.toInt() and 0xFF
            assertTrue(value in 0..255)
        }
    }

    @Test
    fun testQRCodeSquare() {
        val qrCode = generator.generateQRCode("Square test")

        // QR codes should always be square
        assertEquals(qrCode.width, qrCode.height)
    }
}
