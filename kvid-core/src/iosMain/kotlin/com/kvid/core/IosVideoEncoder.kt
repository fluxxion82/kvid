@file:OptIn(BetaInteropApi::class)

package com.kvid.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.time.TimeSource

/**
 * Simplified iOS video encoder that stores frames in memory and writes a raw stream to disk.
 *
 * This lightweight implementation keeps behaviour deterministic for tests without depending on
 * device-specific encoders that are unavailable in the current environment.
 */
@OptIn(ExperimentalForeignApi::class)
class IosVideoEncoder : VideoEncoder {
    private lateinit var params: VideoEncodingParams
    private val frames = mutableListOf<QueuedFrame>()
    private var initialized = false
    private var canceled = false
    private var startMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var outputCounter = 0
    private var enforcedPixelFormat: PixelFormat? = null

    override fun initialize(params: VideoEncodingParams): Result<Unit> {
        return try {
            validateParams(params)
            this.params = params
            frames.clear()
            enforcedPixelFormat = null
            startMark = TimeSource.Monotonic.markNow()
            initialized = true
            canceled = false
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun addFrame(frameData: FrameData, frameNumber: Int): Result<Unit> {
        if (!initialized || canceled) {
            return Result.failure(IllegalStateException("Encoder not initialized"))
        }

        if (frameData.width != params.width || frameData.height != params.height) {
            return Result.failure(IllegalArgumentException("Frame dimensions must match encoder settings"))
        }

        if (frameData.data.isEmpty()) {
            return Result.failure(IllegalArgumentException("Frame data cannot be empty"))
        }

        val targetFormat = enforcedPixelFormat ?: frameData.format
        if (frameData.format != targetFormat) {
            return Result.failure(IllegalArgumentException("Mixed pixel formats are not supported"))
        }
        enforcedPixelFormat = targetFormat

        frames += QueuedFrame(
            frameNumber = frameNumber,
            frame = frameData.copy(data = frameData.data.copyOf())
        )
        return Result.success(Unit)
    }

    override fun finalize(outputPath: String): Result<EncodingStats> {
        if (!initialized) {
            return Result.failure(IllegalStateException("Encoder not initialized"))
        }
        if (canceled) {
            return Result.failure(IllegalStateException("Encoder was cancelled"))
        }

        return try {
            val bytesWritten = writeFramesToFile(outputPath)
            val durationSeconds = if (params.fps > 0) {
                frames.size.toDouble() / params.fps
            } else {
                0.0
            }
            val encodingTimeMs = startMark?.elapsedNow()?.inWholeMilliseconds ?: 0L
            val averageBitrate = if (durationSeconds > 0) {
                ((bytesWritten * 8) / durationSeconds).toLong()
            } else {
                0L
            }

            val stats = EncodingStats(
                totalFrames = frames.size,
                fileSize = bytesWritten,
                durationSeconds = durationSeconds,
                averageBitrate = averageBitrate,
                codec = params.codec,
                encodingTimeMs = encodingTimeMs
            )

            cleanup()
            Result.success(stats)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun cancel() {
        frames.clear()
        initialized = false
        canceled = true
        enforcedPixelFormat = null
    }

    private fun validateParams(params: VideoEncodingParams) {
        require(params.width > 0) { "Width must be positive" }
        require(params.height > 0) { "Height must be positive" }
        require(params.fps > 0) { "FPS must be positive" }
    }

    private fun writeFramesToFile(outputPath: String): Long {
        if (frames.isEmpty()) {
            return 0L
        }

        val combined = buildContainerBytes()
        val destination = if (outputPath.isBlank()) getTempOutputPath() else outputPath

        combined.usePinned { pinned ->
            val data = NSData.create(
                bytes = pinned.addressOf(0),
                length = combined.size.toULong()
            ) ?: throw IllegalStateException("Failed to allocate NSData for encoded output")

            if (!data.writeToFile(destination, atomically = true)) {
                throw IllegalStateException("Failed to write encoded output to $destination")
            }
        }

        return combined.size.toLong()
    }

    private fun buildContainerBytes(): ByteArray {
        val frameCount = frames.size
        if (frameCount == 0) return ByteArray(0)

        val frameMetadataBytes = 4 + 8 + 4 // frameNumber + timestamp + payload length
        val payloadBytes = frames.sumOf { frameMetadataBytes + it.frame.data.size }
        val headerBytes = 4 + 1 + 1 + 1 + 1 + 4 * 4 // magic + version + codec + format + reserved + dims
        val totalBytes = headerBytes + payloadBytes

        require(totalBytes >= 0) { "Container too large" }

        val buffer = ByteArray(totalBytes)
        var offset = 0

        fun writeByte(value: Int) {
            buffer[offset++] = value.toByte()
        }

        fun writeInt(value: Int) {
            buffer[offset++] = ((value ushr 24) and 0xFF).toByte()
            buffer[offset++] = ((value ushr 16) and 0xFF).toByte()
            buffer[offset++] = ((value ushr 8) and 0xFF).toByte()
            buffer[offset++] = (value and 0xFF).toByte()
        }

        fun writeLong(value: Long) {
            buffer[offset++] = ((value ushr 56) and 0xFFL).toByte()
            buffer[offset++] = ((value ushr 48) and 0xFFL).toByte()
            buffer[offset++] = ((value ushr 40) and 0xFFL).toByte()
            buffer[offset++] = ((value ushr 32) and 0xFFL).toByte()
            buffer[offset++] = ((value ushr 24) and 0xFFL).toByte()
            buffer[offset++] = ((value ushr 16) and 0xFFL).toByte()
            buffer[offset++] = ((value ushr 8) and 0xFFL).toByte()
            buffer[offset++] = (value and 0xFFL).toByte()
        }

        // header
        writeByte('K'.code)
        writeByte('V'.code)
        writeByte('I'.code)
        writeByte('D'.code)
        writeByte(1) // version
        writeByte(params.codec.ordinal)
        writeByte((enforcedPixelFormat ?: frames.first().frame.format).ordinal)
        writeByte(0) // reserved
        writeInt(params.width)
        writeInt(params.height)
        writeInt(params.fps)
        writeInt(frameCount)

        // frames
        for (queued in frames) {
            writeInt(queued.frameNumber)
            writeLong(queued.frame.timestamp)
            val payload = queued.frame.data
            writeInt(payload.size)
            payload.copyInto(buffer, destinationOffset = offset)
            offset += payload.size
        }

        return buffer
    }

    private fun getTempOutputPath(): String {
        val base = NSTemporaryDirectory() ?: "/tmp"
        val sanitized = if (base.endsWith("/")) base.dropLast(1) else base
        val id = outputCounter++
        return "$sanitized/kvid_encoded_$id.bin"
    }

    private fun cleanup() {
        frames.clear()
        initialized = false
        canceled = false
        enforcedPixelFormat = null
    }
}

private data class QueuedFrame(
    val frameNumber: Int,
    val frame: FrameData
)
