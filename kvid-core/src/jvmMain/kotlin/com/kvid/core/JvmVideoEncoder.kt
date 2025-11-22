package com.kvid.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * JVM implementation of video encoding using FFmpeg
 *
 * Writes frames to temporary PNG files, then shells out to FFmpeg to create the video.
 * Automatically cleans up temporary files after encoding.
 */
class JvmVideoEncoder : VideoEncoder {
    private var isInitialized = false
    private var params: VideoEncodingParams? = null
    private val frameFiles = mutableListOf<File>()
    private var frameCount = 0
    private var tempDir: Path? = null
    private val startTime = System.currentTimeMillis()

    override fun initialize(params: VideoEncodingParams): Result<Unit> {
        return try {
            if (!isFFmpegAvailable()) {
                return Result.failure(Exception(
                    "FFmpeg is not available on this system. Please install FFmpeg: " +
                    "macOS: brew install ffmpeg, Linux: apt install ffmpeg, Windows: choco install ffmpeg"
                ))
            }

            this.params = params
            this.isInitialized = true
            this.frameCount = 0
            this.frameFiles.clear()

            tempDir = Files.createTempDirectory("kvid-video-")

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun addFrame(frameData: FrameData, frameNumber: Int): Result<Unit> {
        return try {
            if (!isInitialized) {
                return Result.failure(IllegalStateException("Encoder not initialized"))
            }

            if (frameData.format != PixelFormat.RGB_888) {
                return Result.failure(IllegalArgumentException("Only RGB_888 format is supported"))
            }

            val tempDir = tempDir ?: return Result.failure(Exception("Temp directory not created"))

            // write frame data to temporary PNG file using a simple PPM format
            // (which we'll later convert with FFmpeg if needed)
            val frameFile = tempDir.resolve("frame_%06d.ppm".format(frameNumber)).toFile()

            frameFile.outputStream().use { out ->
                // PPM header (P6 = raw RGB)
                out.write("P6\n".toByteArray())
                out.write("${frameData.width} ${frameData.height}\n".toByteArray())
                out.write("255\n".toByteArray())
                // Raw pixel data
                out.write(frameData.data)
            }

            frameFiles.add(frameFile)
            frameCount++
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun finalize(outputPath: String): Result<EncodingStats> {
        return try {
            if (!isInitialized) {
                return Result.failure(IllegalStateException("Encoder not initialized"))
            }

            if (frameCount == 0) {
                return Result.failure(Exception("No frames added"))
            }

            val params = params ?: return Result.failure(Exception("No encoding params"))
            val tempDir = tempDir ?: return Result.failure(Exception("Temp directory not available"))

            // Build FFmpeg command
            val inputPattern = tempDir.resolve("frame_%06d.ppm").toString()
            val ffmpegCmd = buildFFmpegCommand(
                inputPattern = inputPattern,
                outputPath = outputPath,
                params = params,
                frameCount = frameCount
            )

            val ffmpegResult = executeFFmpeg(ffmpegCmd)
            if (ffmpegResult.isFailure) {
                return Result.failure(ffmpegResult.exceptionOrNull() ?: Exception("FFmpeg execution failed"))
            }

            val outputFile = File(outputPath)
            val fileSize = if (outputFile.exists()) outputFile.length() else 0L

            if (fileSize == 0L) {
                return Result.failure(Exception("FFmpeg produced an empty or missing output file. Check FFmpeg output: ${ffmpegResult.getOrNull()}"))
            }

            val durationSeconds = frameCount.toDouble() / params.fps
            val averageBitrate = if (durationSeconds > 0) {
                ((fileSize * 8) / durationSeconds).toLong()
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

    /**
     * Clean up temporary files
     */
    private fun cleanup() {
        try {
            frameFiles.forEach { it.delete() }
            frameFiles.clear()

            tempDir?.let { dir ->
                Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
            tempDir = null
        } catch (e: Exception) {

        }
    }

    /**
     * Build FFmpeg command line
     */
    private fun buildFFmpegCommand(
        inputPattern: String,
        outputPath: String,
        params: VideoEncodingParams,
        frameCount: Int
    ): List<String> {
        val cmd = mutableListOf<String>()
        cmd.add("ffmpeg")
        cmd.add("-y")  // Overwrite output file without asking
        cmd.add("-framerate")
        cmd.add(params.fps.toString())
        cmd.add("-i")
        cmd.add(inputPattern)

        // Video codec
        cmd.add("-c:v")
        cmd.add(when (params.codec) {
            VideoCodec.H264 -> "libx264"
            VideoCodec.H265 -> "libx265"
            VideoCodec.VP9 -> "libvpx-vp9"
            VideoCodec.AV1 -> "libaom-av1"
        })

        when (params.codec) {
            VideoCodec.H264, VideoCodec.H265 -> {
                cmd.add("-crf")
                cmd.add(params.qualityFactor.toString())
            }
            VideoCodec.VP9 -> {
                cmd.add("-b:v")
                cmd.add("0")  // VBR mode
                cmd.add("-crf")
                cmd.add(params.qualityFactor.toString())
            }
            VideoCodec.AV1 -> {
                cmd.add("-crf")
                cmd.add(params.qualityFactor.toString())
            }
        }

        cmd.add("-preset")
        cmd.add(when (params.preset) {
            EncodingPreset.ULTRAFAST -> "ultrafast"
            EncodingPreset.FAST -> "fast"
            EncodingPreset.MEDIUM -> "medium"
            EncodingPreset.SLOW -> "slow"
            EncodingPreset.VERYSLOW -> "veryslow"
        })

        cmd.add("-pix_fmt")
        cmd.add("yuv420p")  // most compatible

        cmd.add("-threads")
        cmd.add("0")  // use all available threads

        cmd.add("-loglevel")
        cmd.add("info")  // show more detail for debugging

        cmd.add(outputPath)

        return cmd
    }

    /**
     * Execute FFmpeg command line
     */
    private fun executeFFmpeg(command: List<String>): Result<String> {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Result.success(output)
            } else {
                Result.failure(Exception("FFmpeg failed with exit code $exitCode\nOutput: $output"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Failed to execute FFmpeg: ${e.message}", e))
        }
    }
}

/**
 * Check if FFmpeg is available on the system
 */
fun isFFmpegAvailable(): Boolean {
    return try {
        val process = ProcessBuilder("ffmpeg", "-version")
            .redirectErrorStream(true)
            .start()
        process.waitFor() == 0
    } catch (e: Exception) {
        false
    }
}

/**
 * Get FFmpeg version information
 */
fun getFFmpegVersion(): String {
    return try {
        val process = ProcessBuilder("ffmpeg", "-version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readLine() }
        process.waitFor()
        output ?: "Unknown"
    } catch (e: Exception) {
        "Not available"
    }
}
