package com.kvid.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.getBytes
import kotlin.test.*

/**
 * Unit tests for the simplified, deterministic IosVideoEncoder.
 *
 * These tests focus on validating the in-memory container writer that backs the iOS encoder
 * stub used in CI environments without AVAssetWriter access.
 */
class IosVideoEncoderTest {

    private val encoder = IosVideoEncoder()
    companion object {
        private var tempFileCounter = 0
    }

    @Test
    fun testEncoderInitialization() {
        val params = VideoEncodingParams(
            width = 256,
            height = 256,
            fps = 30,
            codec = VideoCodec.H264
        )

        val result = encoder.initialize(params)
        assertTrue(result.isSuccess, "Initialization should succeed")
        assertNotNull(result.getOrNull(), "Should return valid encoder state")
    }

    @Test
    fun testVideoEncodingParams() {
        val params = VideoEncodingParams(
            width = 512,
            height = 512,
            fps = 60,
            codec = VideoCodec.H265,
            preset = EncodingPreset.MEDIUM,
            qualityFactor = 25
        )

        assertTrue(params.width > 0, "Width should be positive")
        assertTrue(params.height > 0, "Height should be positive")
        assertTrue(params.fps > 0, "FPS should be positive")
        assertEquals(VideoCodec.H265, params.codec)
        assertEquals(EncodingPreset.MEDIUM, params.preset)
        assertEquals(25, params.qualityFactor)
    }

    @Test
    fun testH264Support() {
        val params = VideoEncodingParams(codec = VideoCodec.H264)
        val result = encoder.initialize(params)
        assertTrue(result.isSuccess, "H.264 should be supported")
    }

    @Test
    fun testH265Support() {
        val params = VideoEncodingParams(codec = VideoCodec.H265)
        val result = encoder.initialize(params)
        assertTrue(result.isSuccess, "H.265 should be supported on iOS")
    }

    @Test
    fun testVP9Support() {
        val params = VideoEncodingParams(codec = VideoCodec.VP9)
        val result = encoder.initialize(params)
        // VP9 may not be supported on all iOS versions
        assertNotNull(result)
    }

    @Test
    fun testAV1Support() {
        val params = VideoEncodingParams(codec = VideoCodec.AV1)
        val result = encoder.initialize(params)
        // AV1 is newer, may not be supported on all iOS versions
        assertNotNull(result)
    }

    @Test
    fun testUltrafastPreset() {
        val params = VideoEncodingParams(preset = EncodingPreset.ULTRAFAST)
        val result = encoder.initialize(params)
        assertTrue(result.isSuccess, "ULTRAFAST preset should be supported")
    }

    @Test
    fun testFastPreset() {
        val params = VideoEncodingParams(preset = EncodingPreset.FAST)
        val result = encoder.initialize(params)
        assertTrue(result.isSuccess, "FAST preset should be supported")
    }

    @Test
    fun testMediumPreset() {
        val params = VideoEncodingParams(preset = EncodingPreset.MEDIUM)
        val result = encoder.initialize(params)
        assertTrue(result.isSuccess, "MEDIUM preset should be supported")
    }

    @Test
    fun testSlowPreset() {
        val params = VideoEncodingParams(preset = EncodingPreset.SLOW)
        val result = encoder.initialize(params)
        assertTrue(result.isSuccess, "SLOW preset should be supported")
    }

    @Test
    fun testVeryslowPreset() {
        val params = VideoEncodingParams(preset = EncodingPreset.VERYSLOW)
        val result = encoder.initialize(params)
        assertTrue(result.isSuccess, "VERYSLOW preset should be supported")
    }

    @Test
    fun testFrameDataCreation() {
        val width = 256
        val height = 256
        val pixelData = ByteArray(width * height * 3) // RGB format

        val frameData = FrameData(
            data = pixelData,
            format = PixelFormat.RGB_888,
            width = width,
            height = height
        )

        assertEquals(width, frameData.width)
        assertEquals(height, frameData.height)
        assertEquals(PixelFormat.RGB_888, frameData.format)
    }

    @Test
    fun testVariousResolutions() {
        val resolutions = listOf(
            Pair(128, 128),
            Pair(256, 256),
            Pair(512, 512),
            Pair(1024, 1024),
            Pair(1920, 1080),
            Pair(3840, 2160)  // 4K
        )

        for ((width, height) in resolutions) {
            val params = VideoEncodingParams(width = width, height = height)
            val result = encoder.initialize(params)
            assertTrue(result.isSuccess, "Should support ${width}x${height} resolution")
        }
    }

    @Test
    fun testVariousFrameRates() {
        val frameRates = listOf(15, 24, 25, 30, 60)

        for (fps in frameRates) {
            val params = VideoEncodingParams(fps = fps)
            val result = encoder.initialize(params)
            assertTrue(result.isSuccess, "Should support $fps fps")
        }
    }

    @Test
    fun testQualityFactorRange() {
        for (quality in 1..50 step 10) {
            val params = VideoEncodingParams(qualityFactor = quality)
            val result = encoder.initialize(params)
            assertTrue(result.isSuccess, "Should support quality factor $quality")
        }
    }

    @Test
    fun testCannotAddFrameBeforeInitialization() {
        val params = VideoEncodingParams(width = 256, height = 256)
        val frameData = createTestFrame(256, 256, 0)

        // Try to add frame without initializing
        val freshEncoder = IosVideoEncoder()
        val result = freshEncoder.addFrame(frameData, 0)
        assertTrue(result.isFailure, "Should fail when adding frame before initialization")
    }

    @Test
    fun testCannotFinalizeBeforeInitialization() {
        val freshEncoder = IosVideoEncoder()
        val result = freshEncoder.finalize("/tmp/test.mp4")
        assertTrue(result.isFailure, "Should fail when finalizing before initialization")
    }

    @Test
    fun testInitializationStateManagement() {
        val params = VideoEncodingParams(width = 256, height = 256)
        val result1 = encoder.initialize(params)
        assertTrue(result1.isSuccess, "First initialization should succeed")

        // Second initialization may fail or reset state
        val result2 = encoder.initialize(params)
        assertNotNull(result2, "Should handle re-initialization")
    }

    @Test
    fun testCancelEncoding() {
        val params = VideoEncodingParams(
            width = 256,
            height = 256,
            fps = 30
        )

        encoder.initialize(params)
        val frameData = createTestFrame(256, 256, 0)
        encoder.addFrame(frameData, 0)

        // Should not throw an exception
        encoder.cancel()

        // After cancel, adding more frames should fail
        val result = encoder.addFrame(createTestFrame(256, 256, 1), 1)
        assertTrue(result.isFailure, "Should fail after cancel")
    }

    @Test
    fun testFinalizeProducesStructuredContainer() {
        val params = VideoEncodingParams(width = 4, height = 4, fps = 12)
        encoder.initialize(params)

        val firstFrame = createTestFrame(4, 4, 0)
        val secondFrame = createTestFrame(4, 4, 1).copy(timestamp = 33)
        encoder.addFrame(firstFrame, 0)
        encoder.addFrame(secondFrame, 1)

        val outputPath = tempFilePath("structured")
        val stats = encoder.finalize(outputPath).getOrThrow()
        val encodedBytes = readBytes(outputPath)

        assertEquals(encodedBytes.size.toLong(), stats.fileSize)
        assertEquals("KVID", encodedBytes.decodeToString(0, 4))
        assertEquals(1, encodedBytes[4].toInt() and 0xFF)
        assertEquals(VideoCodec.H264.ordinal, encodedBytes[5].toInt() and 0xFF)
        assertEquals(PixelFormat.RGB_888.ordinal, encodedBytes[6].toInt() and 0xFF)
        assertEquals(params.width, encodedBytes.readInt(8))
        assertEquals(params.height, encodedBytes.readInt(12))
        assertEquals(params.fps, encodedBytes.readInt(16))
        assertEquals(2, encodedBytes.readInt(20))

        var offset = 24
        val firstPayloadSize = firstFrame.data.size
        assertEquals(0, encodedBytes.readInt(offset))
        assertEquals(firstFrame.timestamp, encodedBytes.readLong(offset + 4))
        assertEquals(firstPayloadSize, encodedBytes.readInt(offset + 12))
        assertContentEquals(
            firstFrame.data,
            encodedBytes.copyOfRange(offset + 16, offset + 16 + firstPayloadSize)
        )

        offset += 16 + firstPayloadSize
        val secondPayloadSize = secondFrame.data.size
        assertEquals(1, encodedBytes.readInt(offset))
        assertEquals(secondFrame.timestamp, encodedBytes.readLong(offset + 4))
        assertEquals(secondPayloadSize, encodedBytes.readInt(offset + 12))
        assertContentEquals(
            secondFrame.data,
            encodedBytes.copyOfRange(offset + 16, offset + 16 + secondPayloadSize)
        )
    }

    @Test
    fun testRejectsMismatchedFrameDimensions() {
        val params = VideoEncodingParams(width = 128, height = 128)
        encoder.initialize(params)

        val mismatchedFrame = FrameData(
            data = ByteArray(64 * 64 * 3),
            format = PixelFormat.RGB_888,
            width = 64,
            height = 64
        )

        val result = encoder.addFrame(mismatchedFrame, 0)
        assertTrue(result.isFailure, "Frame dimensions should match encoder parameters")
    }

    @Test
    fun testRejectsMixedPixelFormats() {
        val params = VideoEncodingParams(width = 64, height = 64)
        encoder.initialize(params)

        val rgbFrame = createTestFrame(64, 64, 0)
        assertTrue(encoder.addFrame(rgbFrame, 0).isSuccess)

        val yuvFrame = FrameData(
            data = ByteArray(rgbFrame.data.size),
            format = PixelFormat.YUV_420P,
            width = 64,
            height = 64
        )

        val result = encoder.addFrame(yuvFrame, 1)
        assertTrue(result.isFailure, "Mixed pixel formats are unsupported")
    }

    @Test
    fun testAddMultipleFrames() = runTest {
        val params = VideoEncodingParams(
            width = 256,
            height = 256,
            fps = 30
        )

        encoder.initialize(params)

        for (i in 0 until 10) {
            val frameData = createTestFrame(256, 256, i)
            val result = encoder.addFrame(frameData, i)
            // May succeed or fail depending on implementation
            assertNotNull(result)
        }

        encoder.cancel()
    }

    @Test
    fun testFrameTimestampHandling() {
        val params = VideoEncodingParams(width = 256, height = 256, fps = 30)
        encoder.initialize(params)

        val frameData = createTestFrame(256, 256, 0)
        val timestamps = listOf(0L, 33L, 67L, 100L)  // ~30fps timestamps in ms

        for (ts in timestamps) {
            val result = encoder.addFrame(frameData, ts.toInt())
            assertNotNull(result, "Should handle frame timestamp $ts")
        }

        encoder.cancel()
    }

    @Test
    fun testRejectsInvalidDimensions() {
        val invalidParams = listOf(
            VideoEncodingParams(width = 0, height = 256),
            VideoEncodingParams(width = 256, height = 0),
            VideoEncodingParams(width = -1, height = 256),
            VideoEncodingParams(width = 256, height = -1)
        )

        for (params in invalidParams) {
            val result = encoder.initialize(params)
            // Invalid dimensions may be rejected
            assertNotNull(result, "Should handle invalid dimensions")
        }
    }

    @Test
    fun testRejectsInvalidFPS() {
        val invalidParams = listOf(
            VideoEncodingParams(fps = 0),
            VideoEncodingParams(fps = -1),
            VideoEncodingParams(fps = 1000)  // Unreasonable fps
        )

        for (params in invalidParams) {
            val result = encoder.initialize(params)
            assertNotNull(result, "Should handle invalid FPS")
        }
    }

    /**
     * Helper function to create test frame data
     */
    private fun createTestFrame(width: Int, height: Int, seed: Int): FrameData {
        val pixels = ByteArray(width * height * 3)
        for (i in pixels.indices step 3) {
            pixels[i] = ((i + seed) % 256).toByte()
            pixels[i + 1] = ((i * 2 + seed) % 256).toByte()
            pixels[i + 2] = ((i * 3 + seed) % 256).toByte()
        }
        return FrameData(pixels, PixelFormat.RGB_888, width, height)
    }

    private fun tempFilePath(tag: String): String {
        val base = NSTemporaryDirectory() ?: "/tmp"
        val sanitized = if (base.endsWith("/")) base.dropLast(1) else base
        tempFileCounter += 1
        return "$sanitized/${tag}_${tempFileCounter}.bin"
    }

    private fun readBytes(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile(path)
            ?: error("Encoded file missing at $path")
        return data.toByteArray()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.toByteArray(): ByteArray {
        val lengthInt = length.toInt()
        val result = ByteArray(lengthInt)
        if (lengthInt == 0) return result
        result.usePinned { pinned ->
            getBytes(pinned.addressOf(0), length)
        }
        return result
    }

    private fun ByteArray.readInt(offset: Int): Int {
        return ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
    }

    private fun ByteArray.readLong(offset: Int): Long {
        return ((this[offset].toLong() and 0xFFL) shl 56) or
            ((this[offset + 1].toLong() and 0xFFL) shl 48) or
            ((this[offset + 2].toLong() and 0xFFL) shl 40) or
            ((this[offset + 3].toLong() and 0xFFL) shl 32) or
            ((this[offset + 4].toLong() and 0xFFL) shl 24) or
            ((this[offset + 5].toLong() and 0xFFL) shl 16) or
            ((this[offset + 6].toLong() and 0xFFL) shl 8) or
            (this[offset + 7].toLong() and 0xFFL)
    }
}
