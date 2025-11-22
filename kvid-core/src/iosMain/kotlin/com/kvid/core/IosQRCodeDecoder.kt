@file:OptIn(BetaInteropApi::class)

package com.kvid.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Vision.VNDetectBarcodesRequest
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNBarcodeSymbologyQR
import platform.CoreGraphics.CGImageRef
import platform.CoreImage.CIImage
import platform.CoreImage.CIContext
import platform.Foundation.NSData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreImage.createCGImage
import platform.Foundation.NSError
import platform.Foundation.create
import platform.Vision.VNBarcodeObservation
import platform.darwin.NSUInteger

/**
 * iOS implementation of QR code decoding using the Vision framework
 *
 * This implementation uses Apple's Vision framework (available on iOS 11+)
 * for efficient QR code detection and decoding.
 */
@OptIn(ExperimentalForeignApi::class)
class IosQRCodeDecoder : QRCodeDecoder {

    override suspend fun decodeQRCode(frameData: DecodedFrame): Result<String> = withContext(Dispatchers.Default) {
        try {
            val cgImage = convertFrameToCGImage(frameData)
            val result = decodeFromCGImage(cgImage)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun decodeBatch(frames: List<DecodedFrame>): List<Result<String>> = withContext(Dispatchers.Default) {
        frames.map { frame ->
            try {
                val cgImage = convertFrameToCGImage(frame)
                val result = decodeFromCGImage(cgImage)
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun decodeFromCGImage(cgImage: CGImageRef): String {
        val request = VNDetectBarcodesRequest()
        request.setSymbologies(listOf(VNBarcodeSymbologyQR))

        val handler = VNImageRequestHandler(cgImage, mapOf<Any?, Any?>())

        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val success = handler.performRequests(listOf(request), error.ptr)

            if (!success || error.value != null) {
                throw RuntimeException("Vision request failed: ${error.value?.localizedDescription}")
            }
        }

        val results = request.results() as? List<*>
        if (results == null || results.isEmpty()) {
            throw RuntimeException("No QR codes detected in frame")
        }

        val firstResult = results.firstOrNull() as? VNBarcodeObservation
            ?: throw RuntimeException("No barcode detection results")

        val payload = firstResult.payloadStringValue()
            ?: throw RuntimeException("Failed to extract QR code payload")

        return payload
    }

    @OptIn(BetaInteropApi::class)
    private fun convertFrameToCGImage(frameData: DecodedFrame): CGImageRef {
        val nsData = frameData.data.usePinned { pinned ->
            NSData.create(
                bytes = pinned.addressOf(0),
                length = frameData.data.size.toULong()
            )
        }
        val ciImage = CIImage.imageWithData(nsData)
            ?: throw RuntimeException("Failed to create CIImage from frame data")

        val context = CIContext.context()
        val cgImage = context.createCGImage(ciImage, ciImage.extent)
            ?: throw RuntimeException("Failed to create CGImage from CIImage")

        return cgImage
    }
}
