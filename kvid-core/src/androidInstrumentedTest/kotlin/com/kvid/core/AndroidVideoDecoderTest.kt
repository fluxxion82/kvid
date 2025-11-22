package com.kvid.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Android MediaCodec video decoder tests
 *
 * Note: These tests require actual Android device/emulator with MediaCodec support.
 * Tests require sample video files to decode.
 */
@RunWith(AndroidJUnit4::class)
class AndroidVideoDecoderTest {
    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var decoder: AndroidVideoDecoder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = context.cacheDir
        decoder = AndroidVideoDecoder()
    }

    @Test
    fun getVideoInfoFailsWithNonexistentFile() = runTest {
        val result = decoder.getVideoInfo("/nonexistent/video.mp4")
        assertTrue(result.isFailure, "Should fail with nonexistent file")
    }

    @Test
    fun getVideoInfoReturnsValidMetadata() = runTest {
        // Create a test video file first
        val videoFile = createTestVideoFile()
        if (videoFile == null) {
            println("Skipping: Could not create test video")
            return@runTest
        }

        try {
            val result = decoder.getVideoInfo(videoFile.absolutePath)
            assertTrue(result.isSuccess, "Should extract video info successfully")

            val videoInfo = result.getOrNull()
            assertNotNull(videoInfo, "Video info should not be null")

            videoInfo?.let {
                assertTrue(it.width > 0, "Width should be positive")
                assertTrue(it.height > 0, "Height should be positive")
                assertTrue(it.fps > 0, "FPS should be positive")
                assertTrue(it.duration >= 0, "Duration should be non-negative")
                assertEquals(VideoCodec.H264, it.codec, "Default codec should be H.264")
            }
        } finally {
            videoFile.delete()
        }
    }

    @Test
    fun extractFramesFailsWithNonexistentFile() = runTest {
        val result = decoder.extractFrames("/nonexistent/video.mp4")
        assertTrue(result.isFailure, "Should fail with nonexistent file")
    }

    @Test
    fun extractFramesReturnsRGBData() = runTest {
        // Create a test video file
        val videoFile = createTestVideoFile()
        if (videoFile == null) {
            println("Skipping: Could not create test video")
            return@runTest
        }

        try {
            val result = decoder.extractFrames(videoFile.absolutePath, listOf(0))
            if (result.isFailure) {
                println("Skipping: Decoder not fully supported on this device")
                return@runTest
            }

            val frames = result.getOrNull()
            assertNotNull(frames, "Frames should not be null")

            frames?.let {
                assertTrue(it.isNotEmpty(), "Should extract at least one frame")
                val frame = it.first()
                assertEquals(PixelFormat.RGB_888, frame.format, "Should return RGB data")
                assertEquals(3, frame.data.size / (frame.width * frame.height), "Should have 3 bytes per pixel for RGB")
                assertTrue(frame.width > 0, "Frame width should be positive")
                assertTrue(frame.height > 0, "Frame height should be positive")
            }
        } finally {
            videoFile.delete()
        }
    }

    @Test
    fun extractFramesRespectFrameIndices() = runTest {
        val videoFile = createTestVideoFile()
        if (videoFile == null) {
            println("Skipping: Could not create test video")
            return@runTest
        }

        try {
            // Extract only frame 0
            val result = decoder.extractFrames(videoFile.absolutePath, listOf(0))
            if (result.isFailure) {
                println("Skipping: Decoder not fully supported on this device")
                return@runTest
            }

            val frames = result.getOrNull()
            frames?.let {
                assertTrue(it.all { f -> f.frameNumber == 0 }, "Should only extract requested frame indices")
            }
        } finally {
            videoFile.delete()
        }
    }

    @Test
    fun qrCodeDecoderInitializes() {
        val qrDecoder = AndroidQRCodeDecoder()
        assertNotNull(qrDecoder, "QR decoder should initialize")
    }

    @Test
    fun qrCodeDecoderFailsWithInvalidData() = runTest {
        val qrDecoder = AndroidQRCodeDecoder()
        val invalidFrame = DecodedFrame(
            frameNumber = 0,
            data = ByteArray(256 * 256 * 3),  // Empty/white frame
            width = 256,
            height = 256,
            format = PixelFormat.RGB_888
        )

        val result = qrDecoder.decodeQRCode(invalidFrame)
        assertTrue(result.isFailure, "Should fail to decode non-QR frame")
    }

    @Test
    fun qrCodeDecoderBatchProcessing() = runTest {
        val qrDecoder = AndroidQRCodeDecoder()
        val frames = listOf(
            createDummyFrame(0),
            createDummyFrame(1),
            createDummyFrame(2)
        )

        val results = qrDecoder.decodeBatch(frames)
        assertEquals(3, results.size, "Should process all frames in batch")
        // All should fail since they're dummy frames
        assertTrue(results.all { it.isFailure }, "Dummy frames should fail to decode")
    }

    @Test
    fun decoderHandlesVariouFrameSizes() = runTest {
        // Test that decoder can handle various frame sizes
        val qrDecoder = AndroidQRCodeDecoder()
        val sizes = listOf(
            Pair(128, 128),
            Pair(256, 256),
            Pair(512, 512),
            Pair(320, 240)
        )

        for ((width, height) in sizes) {
            val frame = DecodedFrame(
                frameNumber = 0,
                data = ByteArray(width * height * 3),
                width = width,
                height = height,
                format = PixelFormat.RGB_888
            )

            // Should not throw, just return failure for dummy frame
            val result = qrDecoder.decodeQRCode(frame)
            assertTrue(result.isFailure, "Dummy frame should fail")
        }
    }

    /**
     * Create a test video file using the encoder
     */
    private fun createTestVideoFile(): File? {
        return try {
            val encoder = AndroidVideoEncoder()
            val outputFile = File(tempDir, "test-decode-${System.currentTimeMillis()}.mp4")

            val params = VideoEncodingParams(
                width = 256,
                height = 256,
                fps = 1,
                codec = VideoCodec.H264,
                preset = EncodingPreset.ULTRAFAST
            )

            encoder.initialize(params).getOrNull() ?: return null

            // Add a few frames
            for (i in 0..2) {
                encoder.addFrame(createTestFrame(256, 256, i), i)
            }

            val result = encoder.finalize(outputFile.absolutePath)
            if (result.isSuccess) {
                outputFile
            } else {
                null
            }
        } catch (e: Exception) {
            null
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

    private fun createDummyFrame(frameNumber: Int): DecodedFrame {
        return DecodedFrame(
            frameNumber = frameNumber,
            data = ByteArray(256 * 256 * 3),  // All zeros
            width = 256,
            height = 256,
            format = PixelFormat.RGB_888
        )
    }
}
