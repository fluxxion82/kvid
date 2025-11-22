@file:OptIn(BetaInteropApi::class)

package com.kvid.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.mediaType
import platform.AVFoundation.naturalSize
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.tracks
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreGraphics.CGSize
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSError
import platform.Foundation.NSURL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * iOS implementation of video decoding using AVFoundation
 *
 * This implementation uses AVAssetReader for efficient frame extraction
 * from MP4 video files without requiring external dependencies.
 */
@OptIn(ExperimentalForeignApi::class)
class IosVideoDecoder : VideoDecoder {

    override suspend fun extractFrames(
        videoPath: String,
        frameIndices: List<Int>?
    ): Result<List<DecodedFrame>> = withContext(Dispatchers.Default) {
        try {
            val videoUrl = NSURL(fileURLWithPath = videoPath)
            val asset = AVURLAsset(uRL = videoUrl, options = null)
            val videoTrack = asset.firstVideoTrack()
                ?: return@withContext Result.failure(
                    Exception("No video track found in media file")
                )

//            val videoInfo = getVideoInfo(videoPath).getOrNull()
//                ?: return@withContext Result.failure(
//                    Exception("Failed to get video info")
//                )

            val assetReader = createAssetReader(asset)
                .getOrElse { return@withContext Result.failure(it) }

            val outputSettings: Map<Any?, Any?> = mapOf(
                kCVPixelBufferPixelFormatTypeKey to kCVPixelFormatType_32BGRA
            )

            val readerOutput = AVAssetReaderTrackOutput(
                track = videoTrack,
                outputSettings = outputSettings
            )
                ?: return@withContext Result.failure(
                    Exception("Failed to create track output")
                )

            if (!assetReader.canAddOutput(readerOutput)) {
                return@withContext Result.failure(
                    Exception("Cannot add track output to asset reader")
                )
            }

            assetReader.addOutput(readerOutput)

            if (!assetReader.startReading()) {
                return@withContext Result.failure(
                    Exception("Failed to start reading: ${assetReader.error?.localizedDescription}")
                )
            }

            val frames = mutableListOf<DecodedFrame>()
            var frameNumber = 0

            while (true) {
                val sampleBuffer = readerOutput.copyNextSampleBuffer() ?: break
                val shouldProcess = frameIndices == null || frameNumber in frameIndices
                if (shouldProcess) {
                    val pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer)
                        ?: continue

                    val frameData = extractFrameData(pixelBuffer, frameNumber)
                    frames.add(frameData)
                }

                frameNumber++
            }

            assetReader.cancelReading()

            Result.success(frames)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getVideoInfo(videoPath: String): Result<VideoInfo> = withContext(Dispatchers.Default) {
        try {
            val videoUrl = NSURL(fileURLWithPath = videoPath)
            val asset = AVURLAsset(uRL = videoUrl, options = null)

            val videoTrack = asset.firstVideoTrack()
                ?: return@withContext Result.failure(
                    Exception("No video track found")
                )

            val durationSeconds = CMTimeGetSeconds(asset.duration)

            val naturalSize = videoTrack.naturalSize
            val width = naturalSize.useContents { this.width }.toInt()
            val height = naturalSize.useContents { this.height }.toInt()

            val nominaltimeBase = videoTrack.nominalFrameRate
            val fps = nominaltimeBase.toInt()

            val totalFrames = (durationSeconds * fps).toInt()

            // determine codec from format description
            // default to H264 until we add proper CMFormatDescription parsing
            val codec = VideoCodec.H264

            Result.success(
                VideoInfo(
                    totalFrames = totalFrames,
                    width = width,
                    height = height,
                    fps = fps,
                    duration = durationSeconds,
                    codec = codec
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractFrameData(pixelBuffer: platform.CoreVideo.CVPixelBufferRef, frameNumber: Int): DecodedFrame {
        val width = CVPixelBufferGetWidth(pixelBuffer).toInt()
        val height = CVPixelBufferGetHeight(pixelBuffer).toInt()

        CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)

        try {
            val baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer)?.reinterpret<ByteVar>()
                ?: throw RuntimeException("Failed to get pixel buffer base address")

            val bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer).toInt()
            val dataSize = height * bytesPerRow
            val pixelData = baseAddress.readBytes(dataSize)

            return DecodedFrame(
                frameNumber = frameNumber,
                data = pixelData,
                width = width,
                height = height,
                format = PixelFormat.RGB_888
            )
        } finally {
            CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
        }
    }

    private fun AVAsset.firstVideoTrack(): AVAssetTrack? {
        val trackList = this.tracks as? List<*> ?: return null
        for (track in trackList) {
            val assetTrack = track as? AVAssetTrack ?: continue
            val mediaType = assetTrack.mediaType
            if (mediaType == AVMediaTypeVideo) {
                return assetTrack
            }
        }
        return null
    }

    private fun createAssetReader(asset: AVAsset): Result<AVAssetReader> = memScoped {
        val errorPtr = alloc<ObjCObjectVar<NSError?>>()
        val reader = AVAssetReader(asset = asset, error = errorPtr.ptr)
        if (reader != null) {
            Result.success(reader)
        } else {
            val message = errorPtr.value?.localizedDescription ?: "Unknown error"
            Result.failure(Exception("Failed to create AVAssetReader: $message"))
        }
    }
}
