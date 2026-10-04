package com.kvid.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trip test for IosQRCodeDecoder: generate a QR with IosQRCodeGenerator,
 * turn it into an RGB frame, decode it with the Vision-based decoder.
 */
class IosQRCodeDecoderTest {

    @Test
    @Ignore // Known failure: the decoder feeds raw pixels to CIImage.imageWithData (expects PNG/JPEG). See docs/ROADMAP.md Phase 1.
    fun testGenerateThenDecodeRoundTrip() = runTest {
        val generator = IosQRCodeGenerator()
        val decoder = IosQRCodeDecoder()
        val text = "Round trip on iOS"

        val qr = generator.generateQRCode(text, version = 10)
        val rgb = ByteArray(qr.width * qr.height * 3)
        for (i in qr.pixels.indices) {
            rgb[i * 3] = qr.pixels[i]
            rgb[i * 3 + 1] = qr.pixels[i]
            rgb[i * 3 + 2] = qr.pixels[i]
        }
        val frame = DecodedFrame(frameNumber = 0, data = rgb, width = qr.width, height = qr.height)

        val decoded = decoder.decodeQRCode(frame).getOrThrow()

        assertEquals(text, decoded)
    }
}
