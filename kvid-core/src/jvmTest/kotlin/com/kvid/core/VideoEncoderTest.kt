package com.kvid.core

import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Video encoder integration tests (requires FFmpeg installed)
 * These tests require actual FFmpeg binary and may be skipped in CI environments
 */
class VideoEncoderTest {
    private var ffmpegAvailable = false

    @BeforeTest
    fun setUp() {
        ffmpegAvailable = isFFmpegAvailable()
    }

    @Test
    fun initializeCreatesValidEncoderState() {
        if (!ffmpegAvailable) {
            println("Skipping: FFmpeg not available")
            return
        }

        val encoder = JvmVideoEncoder()
        val params = VideoEncodingParams(
            width = 256,
            height = 256,
            fps = 30,
            codec = VideoCodec.H264,
            preset = EncodingPreset.FAST
        )

        val result = encoder.initialize(params)
        assertTrue(result.isSuccess, "Initialize should succeed")
    }

    @Test
    fun cannotAddFrameBeforeInitialization() {
        val encoder = JvmVideoEncoder()
        val frameData = createTestFrame(256, 256, 0)
        val result = encoder.addFrame(frameData, 0)
        assertTrue(result.isFailure, "Should fail before initialization")
    }

    @Test
    fun cannotFinalizeBeforeInitialization() {
        val encoder = JvmVideoEncoder()
        val result = encoder.finalize("/tmp/test.mp4")
        assertTrue(result.isFailure, "Should fail before initialization")
    }

    @Test
    fun rejectsNonRGBFrameFormat() {
        if (!ffmpegAvailable) {
            println("Skipping: FFmpeg not available")
            return
        }

        val encoder = JvmVideoEncoder()
        val params = VideoEncodingParams(width = 256, height = 256)
        encoder.initialize(params)

        val yuvFrame = FrameData(
            data = ByteArray(256 * 256 * 3 / 2),  // YUV420 size
            format = PixelFormat.YUV_420P,
            width = 256,
            height = 256
        )

        val result = encoder.addFrame(yuvFrame, 0)
        assertTrue(result.isFailure, "Should reject non-RGB format")
    }

    @Test
    fun cancelCleansUpResources() {
        val encoder = JvmVideoEncoder()
        val params = VideoEncodingParams(width = 256, height = 256)
        encoder.initialize(params)
        encoder.addFrame(createTestFrame(256, 256, 0), 0)

        encoder.cancel()

        val result = encoder.addFrame(createTestFrame(256, 256, 1), 1)
        assertTrue(result.isFailure, "Should fail after cancel")
    }

    /**
     * NOTE: These tests require FFmpeg to be installed and available in PATH
     * They are skipped if FFmpeg is not available
     */
    @Test
    fun simpleEncodeTest() {
        if (!ffmpegAvailable) {
            println("Skipping: FFmpeg not available. Install: brew install ffmpeg")
            return
        }

        val tempFile = File.createTempFile("kvid-test-", ".mp4")
        tempFile.deleteOnExit()

        val encoder = JvmVideoEncoder()
        val params = VideoEncodingParams(
            width = 256,
            height = 256,
            fps = 1,
            codec = VideoCodec.H264,
            preset = EncodingPreset.ULTRAFAST
        )

        encoder.initialize(params)
        encoder.addFrame(createTestFrame(256, 256, 0), 0)

        val result = encoder.finalize(tempFile.absolutePath)
        assertTrue(result.isSuccess, "Encoding should succeed. Error: ${result.exceptionOrNull()?.message}")

        if (result.isSuccess) {
            val stats = result.getOrNull()
            assertTrue(stats != null)
            stats?.let {
                assertEquals(1, it.totalFrames)
                assertEquals(VideoCodec.H264, it.codec)
                assertTrue(it.fileSize > 0, "Output file should have content")
            }
        }
    }

    private fun createTestFrame(width: Int, height: Int, seed: Int): FrameData {
        val pixels = ByteArray(width * height * 3)
        for (i in pixels.indices step 3) {
            pixels[i] = ((i + seed) % 256).toByte()
            pixels[i + 1] = ((i * 2 + seed) % 256).toByte()
            pixels[i + 2] = ((i * 3 + seed) % 256).toByte()
        }
        return FrameData(pixels, PixelFormat.RGB_888, width, height)
    }
}
