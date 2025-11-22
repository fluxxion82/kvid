package com.kvid.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Main API for encoding messages into video files
 */
class MemoryEncoder(
    private val qrGenerator: QRCodeGenerator,
    private val videoEncoder: VideoEncoder,
    private val chunkSize: Int = 512
) {
    private val chunker = TextChunker(chunkSize = chunkSize)
    private val chunks = mutableListOf<TextChunk>()
    private var isEncoding = false

    /**
     * Add messages to be encoded
     */
    suspend fun addMessages(messages: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            for (message in messages) {
                addMessage(message)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Add a single message
     */
    suspend fun addMessage(message: String): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            val textChunks = chunker.chunk(message)
            chunks.addAll(textChunks)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Build the video file with all added messages
     */
    @OptIn(ExperimentalTime::class)
    suspend fun buildVideo(
        outputPath: String,
        params: VideoEncodingParams = VideoEncodingParams()
    ): Result<EncodingStats> = withContext(Dispatchers.Default) {
        try {
            if (chunks.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("No messages added"))
            }

            if (isEncoding) {
                return@withContext Result.failure(IllegalStateException("Encoding already in progress"))
            }

            isEncoding = true

            val capabilities = qrGenerator.getCapabilities()
            val startTime = Clock.System.now().toEpochMilliseconds()

            val errorCorrection = "M"
            if (!capabilities.supportedErrorCorrection.contains(errorCorrection)) {
                isEncoding = false
                return@withContext Result.failure(
                    IllegalArgumentException("Error correction level '$errorCorrection' not supported")
                )
            }

            val oversizedChunks = chunks.filter { it.content.length > capabilities.maxDataCapacity }
            if (oversizedChunks.isNotEmpty()) {
                isEncoding = false
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "Found ${oversizedChunks.size} chunk(s) exceeding max QR capacity of ${capabilities.maxDataCapacity} bytes. " +
                        "Largest: ${oversizedChunks.maxOf { it.content.length }} bytes"
                    )
                )
            }

            videoEncoder.initialize(params).getOrElse { return@withContext Result.failure(it) }

            for ((frameNum, chunk) in chunks.withIndex()) {
                // Use version 30 which reliably handles 256-byte chunks
                val qrData = qrGenerator.generateQRCode(
                    data = chunk.content,
                    version = 30,
                    errorCorrection = errorCorrection
                )

                val frameData = convertToRGB(qrData, params.width, params.height)
                videoEncoder.addFrame(frameData, frameNum)
                    .getOrElse { return@withContext Result.failure(it) }
            }

            val stats = videoEncoder.finalize(outputPath)
                .getOrElse { return@withContext Result.failure(it) }

            isEncoding = false
            val encodingTime = Clock.System.now().toEpochMilliseconds() - startTime

            Result.success(
                stats.copy(encodingTimeMs = encodingTime)
            )
        } catch (e: Exception) {
            isEncoding = false
            videoEncoder.cancel()
            Result.failure(e)
        }
    }

    /**
     * Get encoding statistics
     */
    fun getStats(): EncoderStats {
        return EncoderStats(
            totalChunks = chunks.size,
            totalCharacters = chunks.sumOf { it.length },
            averageChunkSize = if (chunks.isEmpty()) 0 else chunks.map { it.length }.average().toInt(),
            isEncoding = isEncoding
        )
    }

    /**
     * Clear all chunks
     */
    fun clear() {
        chunks.clear()
        isEncoding = false
    }

    /**
     * Convert grayscale QR code data to RGB frame data
     */
    private fun convertToRGB(qrData: QRCodeData, targetWidth: Int, targetHeight: Int): FrameData {
        val rgbPixels = ByteArray(targetWidth * targetHeight * 3)

        for (y in 0 until targetHeight) {
            for (x in 0 until targetWidth) {
                val qrX = (x * qrData.width) / targetWidth
                val qrY = (y * qrData.height) / targetHeight
                val qrPixel = qrData.pixels[qrY * qrData.width + qrX]

                val rgbIndex = (y * targetWidth + x) * 3
                rgbPixels[rgbIndex] = qrPixel       // R
                rgbPixels[rgbIndex + 1] = qrPixel   // G
                rgbPixels[rgbIndex + 2] = qrPixel   // B
            }
        }

        return FrameData(
            data = rgbPixels,
            format = PixelFormat.RGB_888,
            width = targetWidth,
            height = targetHeight
        )
    }
}

data class EncoderStats(
    val totalChunks: Int,
    val totalCharacters: Int,
    val averageChunkSize: Int,
    val isEncoding: Boolean
)
