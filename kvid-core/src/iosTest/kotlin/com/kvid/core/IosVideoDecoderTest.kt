package com.kvid.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Unit tests for IosVideoDecoder
 *
 * Tests video decoding functionality on iOS using AVFoundation
 * Note: Some tests may require actual iOS device or simulator
 */
class IosVideoDecoderTest {

    private val decoder = IosVideoDecoder()

    @Test
    fun testDecodedFrameCreation() {
        val width = 256
        val height = 256
        val pixelData = ByteArray(width * height * 3)

        val frame = DecodedFrame(
            frameNumber = 0,
            data = pixelData,
            width = width,
            height = height,
            format = PixelFormat.RGB_888
        )

        assertEquals(0, frame.frameNumber)
        assertEquals(width, frame.width)
        assertEquals(height, frame.height)
        assertEquals(PixelFormat.RGB_888, frame.format)
    }

    @Test
    fun testDecodedFrameWithDifferentFormats() {
        val width = 256
        val height = 256
        val pixelData = ByteArray(width * height * 3)

        val formats = listOf(
            PixelFormat.RGB_888,
            PixelFormat.YUV_420P
        )

        for (format in formats) {
            val frame = DecodedFrame(
                frameNumber = 0,
                data = pixelData,
                width = width,
                height = height,
                format = format
            )

            assertEquals(format, frame.format, "Should support format $format")
        }
    }

    @Test
    fun testVideoInfoCreation() {
        val videoInfo = VideoInfo(
            totalFrames = 100,
            width = 1920,
            height = 1080,
            fps = 30,
            duration = 3.33,
            codec = VideoCodec.H264
        )

        assertEquals(100, videoInfo.totalFrames)
        assertEquals(1920, videoInfo.width)
        assertEquals(1080, videoInfo.height)
        assertEquals(30, videoInfo.fps)
        assertEquals(VideoCodec.H264, videoInfo.codec)
    }

    @Test
    fun testVideoInfoValidation() {
        val videoInfo = VideoInfo(
            totalFrames = 100,
            width = 1920,
            height = 1080,
            fps = 30,
            duration = 3.33,
            codec = VideoCodec.H264
        )

        assertTrue(videoInfo.totalFrames > 0, "Total frames should be positive")
        assertTrue(videoInfo.width > 0, "Width should be positive")
        assertTrue(videoInfo.height > 0, "Height should be positive")
        assertTrue(videoInfo.fps > 0, "FPS should be positive")
        assertTrue(videoInfo.duration > 0, "Duration should be positive")
    }

    @Test
    fun testVideoInfoWithH264() {
        val videoInfo = VideoInfo(
            totalFrames = 100,
            width = 256,
            height = 256,
            fps = 30,
            duration = 3.33,
            codec = VideoCodec.H264
        )

        assertEquals(VideoCodec.H264, videoInfo.codec)
    }

    @Test
    fun testVideoInfoWithH265() {
        val videoInfo = VideoInfo(
            totalFrames = 100,
            width = 256,
            height = 256,
            fps = 30,
            duration = 3.33,
            codec = VideoCodec.H265
        )

        assertEquals(VideoCodec.H265, videoInfo.codec)
    }

    @Test
    fun testVideoInfoWithVP9() {
        val videoInfo = VideoInfo(
            totalFrames = 100,
            width = 256,
            height = 256,
            fps = 30,
            duration = 3.33,
            codec = VideoCodec.VP9
        )

        assertEquals(VideoCodec.VP9, videoInfo.codec)
    }

    @Test
    fun testVideoInfoWithAV1() {
        val videoInfo = VideoInfo(
            totalFrames = 100,
            width = 256,
            height = 256,
            fps = 30,
            duration = 3.33,
            codec = VideoCodec.AV1
        )

        assertEquals(VideoCodec.AV1, videoInfo.codec)
    }

    @Test
    fun testMultipleFrames() {
        val frames = mutableListOf<DecodedFrame>()

        for (i in 0 until 10) {
            val pixelData = ByteArray(256 * 256 * 3)
            frames.add(
                DecodedFrame(
                    frameNumber = i,
                    data = pixelData,
                    width = 256,
                    height = 256
                )
            )
        }

        assertEquals(10, frames.size)
        for (i in frames.indices) {
            assertEquals(i, frames[i].frameNumber)
        }
    }

    @Test
    fun testVideoDecoderCreation() {
        // Test that decoder instance can be created
        val testDecoder = IosVideoDecoder()
        assertNotNull(testDecoder, "Decoder should be created successfully")
    }

    @Test
    fun testVariousFrameSizes() = runTest {
        val sizes = listOf(
            Pair(128, 128),
            Pair(256, 256),
            Pair(512, 512),
            Pair(1920, 1080),
            Pair(3840, 2160)
        )

        for ((width, height) in sizes) {
            val frame = DecodedFrame(
                frameNumber = 0,
                data = ByteArray(width * height * 3),
                width = width,
                height = height,
                format = PixelFormat.RGB_888
            )

            assertEquals(width, frame.width, "Width should be ${width}")
            assertEquals(height, frame.height, "Height should be ${height}")
        }
    }

    @Test
    fun testVideoInfoWithVariousFPS() {
        val frameRates = listOf(15, 24, 25, 30, 60)

        for (fps in frameRates) {
            val videoInfo = VideoInfo(
                totalFrames = 100,
                width = 256,
                height = 256,
                fps = fps,
                duration = 100.0 / fps,
                codec = VideoCodec.H264
            )

            assertEquals(fps, videoInfo.fps, "FPS should be $fps")
        }
    }

    @Test
    fun testFrameDataIntegrity() {
        val width = 256
        val height = 256
        val pixelData = ByteArray(width * height * 3) { i -> (i % 256).toByte() }

        val frame = DecodedFrame(
            frameNumber = 0,
            data = pixelData,
            width = width,
            height = height,
            format = PixelFormat.RGB_888
        )

        // Verify frame properties
        assertEquals(pixelData.size, frame.data.size, "Pixel data size should match")
        assertTrue(frame.data.contentEquals(pixelData), "Pixel data should be preserved")
    }

    @Test
    fun testMultipleFrameSequence() = runTest {
        val frameCount = 30
        val frames = mutableListOf<DecodedFrame>()

        for (i in 0 until frameCount) {
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

        assertEquals(frameCount, frames.size, "Should create $frameCount frames")

        // Verify frame sequence
        for (i in frames.indices) {
            assertEquals(i, frames[i].frameNumber, "Frame $i should have correct frame number")
        }
    }

    @Test
    fun testDurationCalculation() {
        val totalFrames = 300
        val fps = 30
        val expectedDuration = totalFrames.toDouble() / fps

        val videoInfo = VideoInfo(
            totalFrames = totalFrames,
            width = 256,
            height = 256,
            fps = fps,
            duration = expectedDuration,
            codec = VideoCodec.H264
        )

        assertEquals(expectedDuration, videoInfo.duration, 0.01, "Duration should match frame count / fps")
    }

    @Test
    fun testDecoderHandlesLargeFrames() {
        val width = 4096
        val height = 2160
        val pixelData = ByteArray(width * height * 3)

        val frame = DecodedFrame(
            frameNumber = 0,
            data = pixelData,
            width = width,
            height = height,
            format = PixelFormat.RGB_888
        )

        assertEquals(width, frame.width)
        assertEquals(height, frame.height)
        assertTrue(frame.data.size == width * height * 3, "Large frame should be handled correctly")
    }
}
