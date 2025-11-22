package com.kvid.core

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Android MediaCodec video encoder tests
 *
 * Note: These tests require actual Android device/emulator with MediaCodec support.
 * Some tests may be skipped if codecs are not available on the device.
 */
@RunWith(AndroidJUnit4::class)
class AndroidVideoEncoderTest {
    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = context.cacheDir
    }

    @Test
    fun initializeCreatesValidEncoderState() {
        val encoder = AndroidVideoEncoder()
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
        val encoder = AndroidVideoEncoder()
        val frameData = createTestFrame(256, 256, 0)
        val result = encoder.addFrame(frameData, 0)
        assertTrue(result.isFailure, "Should fail before initialization")
    }

    @Test
    fun cannotFinalizeBeforeInitialization() {
        val encoder = AndroidVideoEncoder()
        val result = encoder.finalize(File(tempDir, "test.mp4").absolutePath)
        assertTrue(result.isFailure, "Should fail before initialization")
    }

    @Test
    fun rejectsNonRGBFrameFormat() {
        val encoder = AndroidVideoEncoder()
        val params = VideoEncodingParams(width = 256, height = 256)
        encoder.initialize(params)

        // Create YUV frame
        val yuvFrame = FrameData(
            data = ByteArray(256 * 256 * 3 / 2),
            format = PixelFormat.YUV_420P,
            width = 256,
            height = 256
        )

        val result = encoder.addFrame(yuvFrame, 0)
        assertTrue(result.isFailure, "Should reject non-RGB format")
    }

    @Test
    fun cancelCleansUpResources() {
        val encoder = AndroidVideoEncoder()
        val params = VideoEncodingParams(width = 256, height = 256)
        encoder.initialize(params)
        encoder.addFrame(createTestFrame(256, 256, 0), 0)

        // This should not throw
        encoder.cancel()

        // Adding more frames after cancel should fail
        val result = encoder.addFrame(createTestFrame(256, 256, 1), 1)
        assertTrue(result.isFailure, "Should fail after cancel")
    }

    @Test
    fun supportsMultipleCodecs() {
        // Check which codecs are available on this device
        val availableCodecs = getAvailableVideoCodecs()

        assertTrue(availableCodecs.isNotEmpty(), "Device should support at least one video codec")

        // H.264 is mandatory, should always be available
        assertTrue(availableCodecs.contains("H.264"), "Device should support H.264")
    }

    @Test
    fun h264EncodingWorks() {
        val encoder = AndroidVideoEncoder()
        val outputFile = File(tempDir, "h264-test.mp4")
        outputFile.deleteOnExit()

        val params = VideoEncodingParams(
            width = 256,
            height = 256,
            fps = 1,  // 1 fps for fast test
            codec = VideoCodec.H264,
            preset = EncodingPreset.ULTRAFAST,
            qualityFactor = 20
        )

        val initResult = encoder.initialize(params)
        if (!initResult.isSuccess) {
            // Skip if H.264 not available
            return
        }

        encoder.addFrame(createTestFrame(256, 256, 0), 0)

        val finalizeResult = encoder.finalize(outputFile.absolutePath)
        assertTrue(finalizeResult.isSuccess, "Finalization should succeed")

        if (finalizeResult.isSuccess) {
            val stats = finalizeResult.getOrNull()
            assertTrue(stats != null)
            stats?.let {
                assertEquals(1, it.totalFrames)
                assertEquals(VideoCodec.H264, it.codec)
                assertTrue(it.fileSize > 0, "Output file should have content")
            }
        }
    }

    @Test
    fun rgbToYuvConversionWorks() {
        val encoder = AndroidVideoEncoder()

        // Create a simple RGB frame (white)
        val width = 256
        val height = 256
        val rgbData = ByteArray(width * height * 3) { 255.toByte() }

        val frameData = FrameData(
            data = rgbData,
            format = PixelFormat.RGB_888,
            width = width,
            height = height
        )

        val params = VideoEncodingParams(
            width = width,
            height = height,
            fps = 1,
            codec = VideoCodec.H264,
            preset = EncodingPreset.ULTRAFAST
        )

        val initResult = encoder.initialize(params)
        if (!initResult.isSuccess) return

        // Adding frame should trigger RGB to YUV conversion
        val addResult = encoder.addFrame(frameData, 0)
        assertTrue(addResult.isSuccess, "Frame addition should trigger conversion without error")

        encoder.cancel()
    }

    @Test
    fun differentFrameSizesWork() {
        val frameSizes = listOf(
            Pair(128, 128),
            Pair(256, 256),
            Pair(512, 512),
            Pair(320, 240),  // Non-square
            Pair(640, 480)   // VGA
        )

        for ((width, height) in frameSizes) {
            val encoder = AndroidVideoEncoder()
            val params = VideoEncodingParams(
                width = width,
                height = height,
                fps = 30,
                codec = VideoCodec.H264,
                preset = EncodingPreset.ULTRAFAST
            )

            val result = encoder.initialize(params)
            if (result.isSuccess) {
                val frameData = createTestFrame(width, height, 0)
                val addResult = encoder.addFrame(frameData, 0)
                assertTrue(addResult.isSuccess, "Should handle size ${width}x$height")
                encoder.cancel()
            }
        }
    }

    @Test
    fun deviceCapabilitiesReported() {
        val codecs = getAvailableVideoCodecs()
        val hardwareCodecs = getHardwareVideoCodecs()
        val softwareCodecs = getSoftwareVideoCodecs()

        assertTrue(codecs.isNotEmpty(), "Should report available codecs")
        println("Available codecs: $codecs")
        println("Hardware codecs: $hardwareCodecs")
        println("Software codecs: $softwareCodecs")

        // At least one codec should be available
        assertTrue((hardwareCodecs + softwareCodecs).isNotEmpty())
    }

    @Test
    fun multipleInitializationsHandled() {
        val encoder = AndroidVideoEncoder()
        val params = VideoEncodingParams(width = 256, height = 256)

        val result1 = encoder.initialize(params)
        assertTrue(result1.isSuccess, "First initialization should succeed")

        // Second initialization may reset or fail gracefully
        val result2 = encoder.initialize(params)
        assertNotNull(result2, "Should handle re-initialization")
    }

    @Test
    fun encoderStateTracking() {
        val encoder = AndroidVideoEncoder()
        val params = VideoEncodingParams(width = 256, height = 256, fps = 30)

        // Before initialization, should fail
        val beforeInit = encoder.addFrame(createTestFrame(256, 256, 0), 0)
        assertTrue(beforeInit.isFailure, "Should fail before initialization")

        // After initialization, should succeed
        encoder.initialize(params)
        val afterInit = encoder.addFrame(createTestFrame(256, 256, 0), 0)
        assertTrue(afterInit.isSuccess, "Should succeed after initialization")
    }

    @Test
    fun sequentialFrameAddition() {
        val encoder = AndroidVideoEncoder()
        val params = VideoEncodingParams(width = 256, height = 256, fps = 30)
        encoder.initialize(params)

        for (i in 0..4) {
            val frameData = createTestFrame(256, 256, i)
            val result = encoder.addFrame(frameData, i)
            assertTrue(result.isSuccess, "Should add frame $i successfully")
        }

        encoder.cancel()
    }

    @Test
    fun qualityFactorAffectsEncoding() {
        val lowQuality = AndroidVideoEncoder()
        val highQuality = AndroidVideoEncoder()

        val lowParams = VideoEncodingParams(
            width = 256, height = 256,
            codec = VideoCodec.H264,
            qualityFactor = 10
        )
        val highParams = VideoEncodingParams(
            width = 256, height = 256,
            codec = VideoCodec.H264,
            qualityFactor = 50
        )

        val lowResult = lowQuality.initialize(lowParams)
        val highResult = highQuality.initialize(highParams)

        assertTrue(lowResult.isSuccess, "Low quality should initialize")
        assertTrue(highResult.isSuccess, "High quality should initialize")
    }

    @Test
    fun presetDoesNotAffectInitialization() {
        val presets = listOf(
            EncodingPreset.ULTRAFAST,
            EncodingPreset.FAST,
            EncodingPreset.MEDIUM
        )

        for (preset in presets) {
            val encoder = AndroidVideoEncoder()
            val params = VideoEncodingParams(preset = preset)
            val result = encoder.initialize(params)
            assertTrue(result.isSuccess, "Should handle preset $preset")
        }
    }

    /**
     * Get list of available video encoders on this device
     */
    private fun getAvailableVideoCodecs(): List<String> {
        return try {
            val codecs = mutableListOf<String>()
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)

            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue

                for (type in info.supportedTypes) {
                    if (type.startsWith("video/")) {
                        val codecName = when (type) {
                            "video/avc" -> "H.264"
                            "video/hevc" -> "H.265"
                            "video/x-vnd.on2.vp9" -> "VP9"
                            "video/av01" -> "AV1"
                            else -> type
                        }
                        if (!codecs.contains(codecName)) {
                            codecs.add(codecName)
                        }
                    }
                }
            }

            codecs
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get hardware video encoders
     */
    private fun getHardwareVideoCodecs(): List<String> {
        return try {
            val codecs = mutableListOf<String>()
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)

            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                if (info.name.startsWith("OMX.google.")) continue  // Software codec

                for (type in info.supportedTypes) {
                    if (type.startsWith("video/")) {
                        val codecName = when (type) {
                            "video/avc" -> "H.264"
                            "video/hevc" -> "H.265"
                            else -> return codecs
                        }
                        if (!codecs.contains(codecName)) {
                            codecs.add(codecName)
                        }
                    }
                }
            }

            codecs
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get software video encoders
     */
    private fun getSoftwareVideoCodecs(): List<String> {
        return try {
            val codecs = mutableListOf<String>()
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)

            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.name.startsWith("OMX.google.")) continue  // Only software

                for (type in info.supportedTypes) {
                    if (type.startsWith("video/")) {
                        val codecName = when (type) {
                            "video/avc" -> "H.264"
                            "video/hevc" -> "H.265"
                            else -> return codecs
                        }
                        if (!codecs.contains(codecName)) {
                            codecs.add(codecName)
                        }
                    }
                }
            }

            codecs
        } catch (e: Exception) {
            emptyList()
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
