@file:OptIn(BetaInteropApi::class)

package com.kvid.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreImage.CIFilter
import platform.CoreImage.CIImage
import platform.CoreImage.CIContext
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGColorSpaceCreateDeviceGray
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGDataProviderCopyData
import platform.Foundation.NSData
import platform.Foundation.create
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cValue
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.Foundation.setValue

/**
 * iOS implementation of QR code generation using CIFilter (Core Image)
 *
 * This implementation uses Apple's built-in QR code generation via CIFilter,
 * which is efficient and doesn't require external dependencies.
 */
class IosQRCodeGenerator : QRCodeGenerator {

    @OptIn(ExperimentalForeignApi::class)
    override fun generateQRCode(
        data: String,
        version: Int,
        errorCorrection: String
    ): QRCodeData {
        try {
            // Compress data if beneficial (similar to memvid)
            val processedData = TextCompression.compress(data)

            val filter = CIFilter.filterWithName("CIQRCodeGenerator") ?:
                throw IllegalStateException("CIQRCodeGenerator filter not available")

            val inputData = processedData.encodeToByteArray()
            val nsData = inputData.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = inputData.size.toULong())
            }
            filter.setValue(nsData, forKey = "inputMessage")

            val correctionLevel = when (errorCorrection.uppercase()) {
                "L" -> "L"
                "M" -> "M"
                "Q" -> "Q"
                "H" -> "H"
                else -> "M"
            }
            filter.setValue(correctionLevel, forKey = "inputCorrectionLevel")

            val ciImage = filter.outputImage ?:
                throw IllegalStateException("Failed to generate QR code image")

            val scaledImage = ciImage.imageByApplyingTransform(
                CGAffineTransformMakeScale(10.0, 10.0)
            )

            val context = CIContext.context()
            val cgImage = context.createCGImage(
                scaledImage,
                scaledImage.extent
            ) ?: throw IllegalStateException("Failed to create CGImage from QR code")

            val width = CGImageGetWidth(cgImage).toInt()
            val height = CGImageGetHeight(cgImage).toInt()
            val pixels = extractPixelsFromCGImage(cgImage, width, height)

            return QRCodeData(
                width = width,
                height = height,
                pixels = pixels,
                version = version,
                dataCapacity = data.length
            )
        } catch (e: Exception) {
            throw RuntimeException("Failed to generate QR code: ${e.message}", e)
        }
    }

    override fun getCapabilities(): QRCapabilities {
        return QRCapabilities(
            maxDataCapacity = 2953,  // Max capacity for QR version 40
            supportedVersions = 1..40,
            supportedErrorCorrection = listOf("L", "M", "Q", "H")
        )
    }

    /**
     * Extract grayscale pixel data from a CGImage
     * Returns a ByteArray where each byte represents a pixel (0-255)
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun extractPixelsFromCGImage(cgImage: platform.CoreGraphics.CGImageRef, width: Int, height: Int): ByteArray {
        val pixelCount = width * height
        val pixels = ByteArray(pixelCount)

        val colorSpace = CGColorSpaceCreateDeviceGray() ?:
            throw IllegalStateException("Failed to create grayscale color space")

        val bytesPerRow = width
        val bitsPerComponent = 8

        val bitmapData = ByteArray(pixelCount)
        bitmapData.usePinned { pinnedData ->
            val context = CGBitmapContextCreate(
                data = pinnedData.addressOf(0),
                width = width.toULong(),
                height = height.toULong(),
                bitsPerComponent = bitsPerComponent.toULong(),
                bytesPerRow = bytesPerRow.toULong(),
                space = colorSpace,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNone.value
            )

            context?.let {
                val rect = cValue<platform.CoreGraphics.CGRect> {
                    origin.x = 0.0
                    origin.y = 0.0
                    size.width = width.toDouble()
                    size.height = height.toDouble()
                }
                platform.CoreGraphics.CGContextDrawImage(it, rect, cgImage)
                platform.CoreGraphics.CGContextRelease(it)
            }
        }

        for (i in 0 until pixelCount) {
            pixels[i] = bitmapData[i]
        }

        return pixels
    }
}

/**
 * Extension function to apply a transformation to a CIImage
 */
private fun CIImage.imageByApplyingTransform(transform: platform.CoreGraphics.CGAffineTransform): CIImage {
    return this.imageByApplyingTransform(transform) as CIImage
}
