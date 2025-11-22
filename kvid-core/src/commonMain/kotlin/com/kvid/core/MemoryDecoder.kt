package com.kvid.core

/**
 * Interface for decoding messages from video files
 */
interface VideoDecoder {
    /**
     * Extract frames from video file
     */
    suspend fun extractFrames(
        videoPath: String,
        frameIndices: List<Int>? = null
    ): Result<List<DecodedFrame>>

    /**
     * Get video metadata
     */
    suspend fun getVideoInfo(videoPath: String): Result<VideoInfo>
}

data class DecodedFrame(
    val frameNumber: Int,
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val format: PixelFormat = PixelFormat.RGB_888
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedFrame) return false
        if (frameNumber != other.frameNumber) return false
        if (!data.contentEquals(other.data)) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (format != other.format) return false
        return true
    }

    override fun hashCode(): Int {
        var result = frameNumber
        result = 31 * result + data.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + format.hashCode()
        return result
    }
}

data class VideoInfo(
    val totalFrames: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val duration: Double,
    val codec: VideoCodec
)

/**
 * Interface for QR code decoding
 */
interface QRCodeDecoder {
    /**
     * Decode QR code from frame data
     */
    suspend fun decodeQRCode(frameData: DecodedFrame): Result<String>

    /**
     * Batch decode multiple frames
     */
    suspend fun decodeBatch(frames: List<DecodedFrame>): List<Result<String>>
}

/**
 * Main API for decoding messages from video files
 */
class MemoryDecoder(
    private val videoDecoder: VideoDecoder,
    private val qrDecoder: QRCodeDecoder
) {
    /**
     * Retrieve messages from video file
     */
    suspend fun retrieve(videoPath: String): Result<List<String>> {
        try {
            val videoInfo = videoDecoder.getVideoInfo(videoPath)
                .getOrElse { return Result.failure(it) }

            val frames = videoDecoder.extractFrames(videoPath)
                .getOrElse { return Result.failure(it) }

            val messages = qrDecoder.decodeBatch(frames)
                .mapNotNull { it.getOrNull() }

            return Result.success(messages)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Retrieve specific frames by indices
     */
    suspend fun retrieveFrames(
        videoPath: String,
        frameIndices: List<Int>
    ): Result<List<String>> {
        try {
            val frames = videoDecoder.extractFrames(videoPath, frameIndices)
                .getOrElse { return Result.failure(it) }

            val messages = qrDecoder.decodeBatch(frames)
                .mapNotNull { it.getOrNull() }

            return Result.success(messages)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Get video information without decoding
     */
    suspend fun getVideoInfo(videoPath: String): Result<VideoInfo> {
        return videoDecoder.getVideoInfo(videoPath)
    }
}
