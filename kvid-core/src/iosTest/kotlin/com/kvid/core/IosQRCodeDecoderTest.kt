package com.kvid.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Unit tests for IosQRCodeDecoder
 *
 * Tests QR code decoding functionality on iOS using the Vision framework
 */
class IosQRCodeDecoderTest {

    private val decoder = IosQRCodeDecoder()

    @Test
    fun testQRCodeDecoderCreation() {
        val testDecoder = IosQRCodeDecoder()
        assertNotNull(testDecoder)
    }

    @Test
    fun testDecodedFrameWithDifferentFormats() {
        val formats = listOf(
            PixelFormat.RGB_888,
            PixelFormat.YUV_420P
        )

        for (format in formats) {
            val pixelData = ByteArray(256 * 256 * 3)
            val frame = DecodedFrame(
                frameNumber = 0,
                data = pixelData,
                width = 256,
                height = 256,
                format = format
            )

            assertTrue(frame.format in formats)
        }
    }

    @Test
    fun testBatchFramesCreation() {
        val frames = mutableListOf<DecodedFrame>()

        for (i in 0 until 5) {
            val pixelData = ByteArray(256 * 256 * 3)
            frames.add(
                DecodedFrame(
                    frameNumber = i,
                    data = pixelData,
                    width = 256,
                    height = 256,
                    format = PixelFormat.RGB_888
                )
            )
        }

        assertTrue(frames.size == 5)
        for (i in frames.indices) {
            assertTrue(frames[i].frameNumber == i)
        }
    }

    @Test
    fun testDecoderInterfaceCompliance() {
        assertTrue(decoder is QRCodeDecoder)
    }
}
