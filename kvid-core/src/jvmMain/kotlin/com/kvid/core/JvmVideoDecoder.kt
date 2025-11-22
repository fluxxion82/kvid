package com.kvid.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success
import javax.imageio.ImageIO

/**
 * JVM implementation of video decoding using FFmpeg
 */
class JvmVideoDecoder : VideoDecoder {
    override suspend fun extractFrames(
        videoPath: String,
        frameIndices: List<Int>?
    ): Result<List<DecodedFrame>> = withContext(Dispatchers.IO) {
        try {
            val infoResult = getVideoInfo(videoPath)
            val videoInfo = infoResult.getOrNull() ?: return@withContext failure(
                infoResult.exceptionOrNull() ?: Exception("Failed to get video info")
            )

            if (!File(videoPath).exists()) {
                return@withContext failure(Exception("Video file not found: $videoPath"))
            }

            val tempDir = Files.createTempDirectory("kvid-decode-")
            val framesToExtract = frameIndices ?: (0 until videoInfo.totalFrames).toList()
            val extractResult = extractFramesWithFFmpeg(videoPath, tempDir, videoInfo.fps)
            if (extractResult.isFailure) {
                return@withContext failure(
                    extractResult.exceptionOrNull() ?: Exception("FFmpeg extraction failed")
                )
            }

            val results = mutableListOf<DecodedFrame>()
            for (frameNum in framesToExtract) {
                val frameFile = tempDir.resolve("frame_%06d.ppm".format(frameNum)).toFile()

                if (!frameFile.exists()) {
                    val pngFile = tempDir.resolve("frame_%06d.png".format(frameNum)).toFile()
                    if (pngFile.exists()) {
                        val decodedFrame = loadFrameFromFile(pngFile, frameNum, videoInfo)
                        if (decodedFrame != null) {
                            results.add(decodedFrame)
                        }
                    }
                    continue
                }

                val decodedFrame = loadFrameFromPPM(frameFile, frameNum)
                if (decodedFrame != null) {
                    results.add(decodedFrame)
                }
            }

            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            } catch (e: Exception) { }

            success(results)
        } catch (e: Exception) {
            failure(e)
        }
    }

    override suspend fun getVideoInfo(videoPath: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            if (!File(videoPath).exists()) {
                return@withContext failure(Exception("Video file not found: $videoPath"))
            }

            // Use ffprobe to get video metadata
            val cmd = listOf(
                "ffprobe",
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,r_frame_rate,nb_frames,codec_name",
                "-of", "default=noprint_wrappers=1:nokey=1:noprint_wrappers=1",
                videoPath
            )

            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readLines() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                return@withContext failure(Exception("ffprobe failed for video: $videoPath"))
            }

            // Parse output
            // Output format: width, height, framerate, totalframes, codec
            if (output.size < 5) {
                return@withContext failure(Exception("Unexpected ffprobe output"))
            }

            val width = output[0].toIntOrNull() ?: 0
            val height = output[1].toIntOrNull() ?: 0
            val frameRateStr = output[2]  // e.g., "30/1"
            val totalFrames = output[3].toIntOrNull() ?: 0
            val codecName = output.getOrNull(4) ?: "h264"

            val fps = when {
                frameRateStr.contains("/") -> {
                    val (num, den) = frameRateStr.split("/").map { it.toIntOrNull() ?: 1 }
                    (num / den).coerceAtLeast(1)
                }
                else -> frameRateStr.toIntOrNull() ?: 30
            }

            val duration = if (totalFrames > 0 && fps > 0) {
                totalFrames.toDouble() / fps
            } else {
                0.0
            }

            val codec = when (codecName.lowercase()) {
                "h264" -> VideoCodec.H264
                "hevc", "h265" -> VideoCodec.H265
                "vp9" -> VideoCodec.VP9
                "av1" -> VideoCodec.AV1
                else -> VideoCodec.H264
            }

            success(VideoInfo(
                totalFrames = totalFrames,
                width = width,
                height = height,
                fps = fps,
                duration = duration,
                codec = codec
            ))
        } catch (e: Exception) {
            failure(e)
        }
    }

    /**
     * Extract all frames from video using FFmpeg
     */
    private fun extractFramesWithFFmpeg(
        videoPath: String,
        outputDir: Path,
        fps: Int
    ): Result<Unit> {
        return try {
            val cmd = listOf(
                "ffmpeg",
                "-i", videoPath,
                "-vf", "fps=$fps",
                "${outputDir}/frame_%06d.ppm",
                "-y"
            )

            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            val exitCode = process.waitFor()

            if (exitCode == 0) {
                success(Unit)
            } else {
                failure(Exception("FFmpeg frame extraction failed with code $exitCode"))
            }
        } catch (e: Exception) {
            failure(e)
        }
    }

    /**
     * Load frame from PPM file
     */
    private fun loadFrameFromPPM(file: File, frameNum: Int): DecodedFrame? {
        return try {
            val lines = file.readLines()
            if (lines.isEmpty() || !lines[0].startsWith("P6")) {
                return null
            }

            val (width, height) = lines[1].split(" ").map { it.toInt() }
            val maxVal = lines[2].toInt()

            val pixelData = ByteArray(width * height * 3)
            file.inputStream().use { input ->
                var bytesRead = 0
                for (i in 0..2) {
                    bytesRead += lines[i].length + 1  // +1 for newline
                }
                input.skip(bytesRead.toLong())
                input.read(pixelData)
            }

            DecodedFrame(
                frameNumber = frameNum,
                data = pixelData,
                width = width,
                height = height,
                format = PixelFormat.RGB_888
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Load frame from PNG/JPEG file
     */
    private fun loadFrameFromFile(file: File, frameNum: Int, videoInfo: VideoInfo): DecodedFrame? {
        return try {
            val image = ImageIO.read(file) ?: return null

            val rgbImage = if (image.type == BufferedImage.TYPE_INT_RGB) {
                image
            } else {
                BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).apply {
                    graphics.drawImage(image, 0, 0, null)
                }
            }

            val pixelData = ByteArray(image.width * image.height * 3)
            var idx = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val rgb = rgbImage.getRGB(x, y)
                    pixelData[idx++] = ((rgb shr 16) and 0xFF).toByte()  // R
                    pixelData[idx++] = ((rgb shr 8) and 0xFF).toByte()   // G
                    pixelData[idx++] = (rgb and 0xFF).toByte()           // B
                }
            }

            DecodedFrame(
                frameNumber = frameNum,
                data = pixelData,
                width = image.width,
                height = image.height,
                format = PixelFormat.RGB_888
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * JVM implementation of QR code decoding using ZXing
 */
class JvmQRCodeDecoder : QRCodeDecoder {
    private val reader = MultiFormatReader()
    private val baseHints = mapOf<DecodeHintType, Any>(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            com.google.zxing.BarcodeFormat.QR_CODE
        ),
        DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )
    private val pureHints = baseHints + (DecodeHintType.PURE_BARCODE to true)

    override suspend fun decodeQRCode(frameData: DecodedFrame): Result<String> = withContext(Dispatchers.Default) {
        try {
            val image = BufferedImage(frameData.width, frameData.height, BufferedImage.TYPE_INT_RGB)
            var idx = 0
            for (y in 0 until frameData.height) {
                for (x in 0 until frameData.width) {
                    val r = frameData.data[idx++].toInt() and 0xFF
                    val g = frameData.data[idx++].toInt() and 0xFF
                    val b = frameData.data[idx++].toInt() and 0xFF
                    val rgb = (r shl 16) or (g shl 8) or b
                    image.setRGB(x, y, rgb)
                }
            }

            val luminanceSource = BufferedImageLuminanceSource(image)
            val bitmap = BinaryBitmap(HybridBinarizer(luminanceSource))

            val decodedText = tryDecode(bitmap, baseHints)
                ?: tryDecode(bitmap, pureHints)
                ?: throw NotFoundException.getNotFoundInstance()

            // Decompress if the data was compressed
            val finalText = TextCompression.decompress(decodedText)

            success(finalText)
        } catch (e: Exception) {
            failure(e)
        }
    }

    override suspend fun decodeBatch(frames: List<DecodedFrame>): List<Result<String>> = withContext(Dispatchers.Default) {
        frames.map { decodeQRCode(it) }
    }

    private fun tryDecode(
        bitmap: BinaryBitmap,
        hints: Map<DecodeHintType, Any>
    ): String? {
        return try {
            reader.decode(bitmap, hints).text
        } catch (e: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }
}
