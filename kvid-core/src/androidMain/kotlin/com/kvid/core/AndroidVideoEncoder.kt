package com.kvid.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.graphics.createBitmap

/**
 * Android implementation of video encoding using MediaCodec
 *
 * Uses hardware-accelerated video encoding when available.
 * Falls back to software encoding if hardware codec not supported.
 *
 * Supports API 21+ (Android 5.0+)
 */
class AndroidVideoEncoder : VideoEncoder {
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isInitialized = false
    private var params: VideoEncodingParams? = null
    private var frameCount = 0
    private val presentationTimeUs = mutableListOf<Long>()
    private val startTime = System.currentTimeMillis()

    override fun initialize(params: VideoEncodingParams): Result<Unit> {
        return try {
            this.params = params

            val mimeType = when (params.codec) {
                VideoCodec.H264 -> "video/avc"
                VideoCodec.H265 -> "video/hevc"
                VideoCodec.VP9 -> "video/x-vnd.on2.vp9"
                VideoCodec.AV1 -> "video/av01"
            }

            if (!isCodecSupported(mimeType)) {
                // Fall back to H.264
                if (mimeType != "video/avc" && isCodecSupported("video/avc")) {
                    return initializeCodec(params.copy(codec = VideoCodec.H264), "video/avc", params.width, params.height)
                }
                return Result.failure(Exception("No suitable video codec found for $mimeType"))
            }

            initializeCodec(params, mimeType, params.width, params.height)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun initializeCodec(
        params: VideoEncodingParams,
        mimeType: String,
        width: Int,
        height: Int
    ): Result<Unit> {
        return try {
            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, mimeType)
                setInteger(MediaFormat.KEY_WIDTH, width)
                setInteger(MediaFormat.KEY_HEIGHT, height)
                setInteger(MediaFormat.KEY_BIT_RATE, calculateBitrate(width, height, params.fps))
                setInteger(MediaFormat.KEY_FRAME_RATE, params.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)

                when (mimeType) {
                    "video/avc" -> {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
                    }
                    "video/hevc" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41)
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)  // 0 = realtime priority
                }
            }

            mediaCodec = createCodec(mimeType, true)
                ?: createCodec(mimeType, false)
                ?: return Result.failure(Exception("Failed to create codec for $mimeType"))

            mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // create muxer (dummy file for now - we'll handle output properly in finalize)
            mediaMuxer = null  // Will be created in finalize with actual output path

            this.isInitialized = true
            this.frameCount = 0
            this.videoTrackIndex = -1
            this.presentationTimeUs.clear()

            Result.success(Unit)
        } catch (e: Exception) {
            mediaCodec?.release()
            mediaCodec = null
            Result.failure(e)
        }
    }

    private fun createCodec(mimeType: String, preferHardware: Boolean): MediaCodec? {
        return try {
            val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            val codecInfo = codecs.find { info ->
                if (!info.isEncoder) return@find false

                val supportedTypes = info.supportedTypes
                val typeSupported = supportedTypes.contains(mimeType)
                val isHardware = !info.name.startsWith("OMX.google.")

                if (preferHardware) {
                    typeSupported && isHardware
                } else {
                    typeSupported && !isHardware
                }
            } ?: return null

            MediaCodec.createByCodecName(codecInfo.name)
        } catch (e: Exception) {
            null
        }
    }

    private fun isCodecSupported(mimeType: String): Boolean {
        return try {
            val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            codecs.any { info ->
                info.isEncoder && info.supportedTypes.contains(mimeType)
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun addFrame(frameData: FrameData, frameNumber: Int): Result<Unit> {
        return try {
            if (!isInitialized || mediaCodec == null) {
                return Result.failure(IllegalStateException("Encoder not initialized"))
            }

            if (frameData.format != PixelFormat.RGB_888) {
                return Result.failure(IllegalArgumentException("Only RGB_888 format is supported"))
            }

            val codec = mediaCodec!!

            if (frameNumber == 0) {
                codec.start()
            }

            val yuvData = rgbToYuv420(frameData.data, frameData.width, frameData.height)
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.put(yuvData)

                val presentationTime = (frameNumber * 1_000_000L) / (params?.fps ?: 30)
                presentationTimeUs.add(presentationTime)

                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    yuvData.size,
                    presentationTime,
                    0
                )

                frameCount++
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun finalize(outputPath: String): Result<EncodingStats> {
        return try {
            if (!isInitialized || mediaCodec == null) {
                return Result.failure(IllegalStateException("Encoder not initialized"))
            }

            val codec = mediaCodec!!
            val params = params ?: return Result.failure(Exception("No encoding params"))

            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    0,
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var outputFormat: MediaFormat? = null

            while (true) {
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)

                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = codec.outputFormat
                    videoTrackIndex = muxer.addTrack(outputFormat)
                    muxer.start()
                } else if (outputBufferIndex >= 0) {
                    if (videoTrackIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        outputBuffer?.let {
                            muxer.writeSampleData(videoTrackIndex, it, bufferInfo)
                        }
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                } else if (outputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // continue processing
                }
            }

            muxer.stop()
            muxer.release()
            codec.stop()

            val outputFile = File(outputPath)
            val fileSize = if (outputFile.exists()) outputFile.length() else 0L
            val durationSeconds = frameCount.toDouble() / params.fps
            val averageBitrate = if (durationSeconds > 0) {
                (fileSize * 8) / durationSeconds.toLong()
            } else {
                0L
            }

            val encodingTimeMs = System.currentTimeMillis() - startTime

            Result.success(
                EncodingStats(
                    totalFrames = frameCount,
                    fileSize = fileSize,
                    durationSeconds = durationSeconds,
                    averageBitrate = averageBitrate,
                    codec = params.codec,
                    encodingTimeMs = encodingTimeMs
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            cleanup()
        }
    }

    override fun cancel() {
        isInitialized = false
        frameCount = 0
        cleanup()
    }

    private fun cleanup() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            mediaMuxer?.release()
            mediaMuxer = null
            presentationTimeUs.clear()
        } catch (e: Exception) {
            // Silently fail on cleanup
        }
    }

    /**
     * Convert RGB888 to YUV420 semi-planar format
     * This is needed because Android MediaCodec requires YUV420SP for input
     */
    private fun rgbToYuv420(rgbData: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvSize = ySize / 4
        val yuvData = ByteArray(ySize + uvSize * 2)

        var rgbIndex = 0
        var yIndex = 0
        var uIndex = ySize
        var vIndex = ySize + uvSize

        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = rgbData[rgbIndex++].toInt() and 0xFF
                val g = rgbData[rgbIndex++].toInt() and 0xFF
                val b = rgbData[rgbIndex++].toInt() and 0xFF

                val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuvData[yIndex++] = yValue.toByte()

                if (x % 2 == 0 && y % 2 == 0) {
                    val uValue = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vValue = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                    yuvData[uIndex++] = uValue.toByte()
                    yuvData[vIndex++] = vValue.toByte()
                }
            }
        }

        return yuvData
    }

    private fun calculateBitrate(width: Int, height: Int, fps: Int): Int {
        // bitrate = width * height * fps * bitsPerPixel / quality_factor
        // target: ~0.1 bits per pixel per frame for quality
        val pixels = width * height
        val baseRate = pixels * fps / 10  // 0.1 bits per pixel

        return maxOf(500_000, baseRate)  // minimum 500 kbps
    }
}

/**
 * Android implementation of video decoding using MediaExtractor
 */
class AndroidVideoDecoder : VideoDecoder {
    override suspend fun extractFrames(
        videoPath: String,
        frameIndices: List<Int>?
    ): Result<List<DecodedFrame>> = withContext(Dispatchers.Default) {
        try {
            val file = File(videoPath)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("Video file not found: $videoPath"))
            }

            val infoResult = getVideoInfo(videoPath)
            val videoInfo = infoResult.getOrNull() ?: return@withContext Result.failure(
                infoResult.exceptionOrNull() ?: Exception("Failed to get video info")
            )

            val extractor = MediaExtractor()
            extractor.setDataSource(videoPath)

            var videoTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    break
                }
            }

            if (videoTrackIndex < 0) {
                extractor.release()
                return@withContext Result.failure(Exception("No video track found"))
            }

            extractor.selectTrack(videoTrackIndex)

            val format = extractor.getTrackFormat(videoTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val decoder = MediaCodec.createDecoderByType(mime)

            decoder.configure(format, null, null, 0)
            decoder.start()

            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)

            val framesToExtract = frameIndices ?: (0 until videoInfo.totalFrames).toList()

            val results = mutableListOf<DecodedFrame>()
            val bufferInfo = MediaCodec.BufferInfo()
            var currentFrame = 0

            while (true) {
                val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                    val sampleSize = extractor.readSampleData(inputBuffer!!, 0)

                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputBufferIndex, 0, 0, 0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    } else {
                        val sampleTime = extractor.sampleTime
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, sampleTime, 0)
                        extractor.advance()
                    }
                }

                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    if (framesToExtract.contains(currentFrame)) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null) {
                            val frameData = yuv420ToRgb(outputBuffer, width, height)
                            results.add(
                                DecodedFrame(
                                    frameNumber = currentFrame,
                                    data = frameData,
                                    width = width,
                                    height = height,
                                    format = PixelFormat.RGB_888
                                )
                            )
                        }
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false)
                    currentFrame++

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }

            decoder.stop()
            decoder.release()
            extractor.release()

            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getVideoInfo(videoPath: String): Result<VideoInfo> = withContext(Dispatchers.Default) {
        try {
            val file = File(videoPath)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("Video file not found: $videoPath"))
            }

            val extractor = MediaExtractor()
            extractor.setDataSource(videoPath)

            // Find video track
            var videoTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    break
                }
            }

            if (videoTrackIndex < 0) {
                extractor.release()
                return@withContext Result.failure(Exception("No video track found"))
            }

            val format = extractor.getTrackFormat(videoTrackIndex)

            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val duration = format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0  // Convert to seconds

            val fps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.getIntegerIfAvailable(MediaFormat.KEY_FRAME_RATE, 30)
            } else {
                30
            }

            val totalFrames = if (fps > 0) (duration * fps).toInt() else 0
            val codec = when (mime) {
                "video/avc" -> VideoCodec.H264
                "video/hevc" -> VideoCodec.H265
                "video/x-vnd.on2.vp9" -> VideoCodec.VP9
                "video/av01" -> VideoCodec.AV1
                else -> VideoCodec.H264
            }

            extractor.release()

            Result.success(
                VideoInfo(
                    totalFrames = totalFrames,
                    width = width,
                    height = height,
                    fps = fps,
                    duration = duration,
                    codec = codec
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Convert YUV420 semi-planar to RGB888
     */
    private fun yuv420ToRgb(yuvData: java.nio.ByteBuffer, width: Int, height: Int): ByteArray {
        val rgbData = ByteArray(width * height * 3)
        val uvPixelStride = 1

        val ySize = width * height
        val uvSize = ySize / 4

        var rgbIndex = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val yIndex = y * width + x
                val uvIndex = (y / 2) * (width / 2) + (x / 2)

                val yVal = (yuvData.get(yIndex).toInt() and 0xFF) - 16
                val uVal = (yuvData.get(ySize + uvIndex * uvPixelStride).toInt() and 0xFF) - 128
                val vVal = (yuvData.get(ySize + uvSize + uvIndex * uvPixelStride).toInt() and 0xFF) - 128

                val r = (1.164 * yVal + 1.596 * vVal).toInt().coerceIn(0, 255)
                val g = (1.164 * yVal - 0.392 * uVal - 0.813 * vVal).toInt().coerceIn(0, 255)
                val b = (1.164 * yVal + 2.017 * uVal).toInt().coerceIn(0, 255)

                rgbData[rgbIndex++] = r.toByte()
                rgbData[rgbIndex++] = g.toByte()
                rgbData[rgbIndex++] = b.toByte()
            }
        }

        return rgbData
    }
}

/**
 * Helper to get codec name for display
 */
fun getCodecNameForCodec(codec: VideoCodec): String {
    return when (codec) {
        VideoCodec.H264 -> "H.264 (AVC)"
        VideoCodec.H265 -> "H.265 (HEVC)"
        VideoCodec.VP9 -> "VP9"
        VideoCodec.AV1 -> "AV1"
    }
}

/**
 * Extension function to safely get integer from MediaFormat
 */
private fun MediaFormat.getIntegerIfAvailable(key: String, defaultValue: Int): Int {
    return try {
        getInteger(key)
    } catch (e: Exception) {
        defaultValue
    }
}

/**
 * Android implementation of QR code decoding using ZXing
 */
class AndroidQRCodeDecoder : QRCodeDecoder {
    private val reader = com.google.zxing.MultiFormatReader()

    override suspend fun decodeQRCode(frameData: DecodedFrame): Result<String> = withContext(Dispatchers.Default) {
        try {
            val bitmap = createBitmap(frameData.width, frameData.height, android.graphics.Bitmap.Config.RGB_565)

            var idx = 0
            for (y in 0 until frameData.height) {
                for (x in 0 until frameData.width) {
                    val r = frameData.data[idx++].toInt() and 0xFF
                    val g = frameData.data[idx++].toInt() and 0xFF
                    val b = frameData.data[idx++].toInt() and 0xFF
                    val rgb = (r shl 16) or (g shl 8) or b
                    bitmap.setPixel(x, y, rgb or 0xFF000000.toInt())
                }
            }

            val grayData = getPixelDataFromBitmap(bitmap)

            val source = com.google.zxing.PlanarYUVLuminanceSource(
                grayData,
                frameData.width,
                frameData.height,
                0, 0,
                frameData.width,
                frameData.height,
                false
            )

            val binaryBitmap = com.google.zxing.BinaryBitmap(
                com.google.zxing.common.HybridBinarizer(source)
            )

            val hints = mapOf(
                com.google.zxing.DecodeHintType.TRY_HARDER to true,
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(
                    com.google.zxing.BarcodeFormat.QR_CODE
                )
            )

            val result = reader.decode(binaryBitmap, hints)
            bitmap.recycle()

            Result.success(result.text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun decodeBatch(frames: List<DecodedFrame>): List<Result<String>> = withContext(Dispatchers.Default) {
        frames.map { decodeQRCode(it) }
    }

    /**
     * Convert RGB bitmap to grayscale pixel data for ZXing
     */
    private fun getPixelDataFromBitmap(bitmap: android.graphics.Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val grayData = ByteArray(bitmap.width * bitmap.height)
        for (i in pixels.indices) {
            val rgb = pixels[i]
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF

            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            grayData[i] = gray.toByte()
        }
        return grayData
    }
}
