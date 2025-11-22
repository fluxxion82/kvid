package com.kvid.core

/**
 * Platform-independent interface for video encoding
 */
interface VideoEncoder {
    /**
     * Initialize video encoding with specified parameters
     */
    fun initialize(params: VideoEncodingParams): Result<Unit>

    /**
     * Add a frame to the video
     * @param frameData Raw image data (RGB or YUV)
     * @param frameNumber Sequential frame number
     */
    fun addFrame(frameData: FrameData, frameNumber: Int): Result<Unit>

    /**
     * Finalize and write the video file
     */
    fun finalize(outputPath: String): Result<EncodingStats>

    /**
     * Cancel encoding and cleanup
     */
    fun cancel()
}

data class VideoEncodingParams(
    val width: Int = 256,
    val height: Int = 256,
    val fps: Int = 30,
    val codec: VideoCodec = VideoCodec.H264,
    val preset: EncodingPreset = EncodingPreset.MEDIUM,
    val qualityFactor: Int = 28  // CRF or equivalent
)

data class FrameData(
    val data: ByteArray,
    val format: PixelFormat = PixelFormat.RGB_888,
    val width: Int,
    val height: Int,
    val timestamp: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameData) return false
        if (!data.contentEquals(other.data)) return false
        if (format != other.format) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (timestamp != other.timestamp) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

enum class VideoCodec {
    H264, H265, VP9, AV1
}

enum class EncodingPreset {
    ULTRAFAST, FAST, MEDIUM, SLOW, VERYSLOW
}

enum class PixelFormat {
    RGB_888,
    YUV_420P
}

data class EncodingStats(
    val totalFrames: Int,
    val fileSize: Long,
    val durationSeconds: Double,
    val averageBitrate: Long,
    val codec: VideoCodec,
    val encodingTimeMs: Long
)
