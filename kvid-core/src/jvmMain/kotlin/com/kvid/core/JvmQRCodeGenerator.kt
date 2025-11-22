package com.kvid.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * JVM implementation of QR code generation using ZXing
 */
class JvmQRCodeGenerator : QRCodeGenerator {
    private val writer = QRCodeWriter()

    override fun generateQRCode(
        data: String,
        version: Int,
        errorCorrection: String
    ): QRCodeData {
        // Check if data is too large for the requested version
        val maxCapacity = getMaxCapacityForVersion(version, errorCorrection)
        if (data.toByteArray(Charsets.UTF_8).size > maxCapacity) {
            throw RuntimeException(
                "Data too big for requested version. " +
                "Data size: ${data.toByteArray(Charsets.UTF_8).size} bytes, " +
                "Max capacity for version $version with error correction $errorCorrection: $maxCapacity bytes. " +
                "Consider using version 40 or chunking the data."
            )
        }

        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to getErrorCorrectionLevel(errorCorrection),
            EncodeHintType.QR_VERSION to version,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        try {
            val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, 0, 0, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height

            // Convert BitMatrix to pixel array (grayscale)
            val pixels = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (bitMatrix[x, y]) 0 else 255.toByte()
                }
            }

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

    fun generateBufferedImage(
        data: String,
        version: Int = 30,
        errorCorrection: String = "M",
        size: Int = 512,
        margin: Int = 10
    ): BufferedImage {
        val qrData = generateQRCode(data, version, errorCorrection)
        val totalSize = size + (margin * 2)

        val image = BufferedImage(totalSize, totalSize, BufferedImage.TYPE_INT_RGB)

        // Fill background with white
        for (x in 0 until totalSize) {
            for (y in 0 until totalSize) {
                image.setRGB(x, y, Color.WHITE.rgb)
            }
        }

        // Draw QR code
        for (y in 0 until size) {
            for (x in 0 until size) {
                val qrX = (x * qrData.width) / size
                val qrY = (y * qrData.height) / size
                val pixel = qrData.pixels[qrY * qrData.width + qrX]
                val color = if (pixel == 0.toByte()) Color.BLACK else Color.WHITE
                image.setRGB(x + margin, y + margin, color.rgb)
            }
        }

        return image
    }

    private fun getErrorCorrectionLevel(level: String): ErrorCorrectionLevel {
        return when (level.uppercase()) {
            "L" -> ErrorCorrectionLevel.L
            "M" -> ErrorCorrectionLevel.M
            "Q" -> ErrorCorrectionLevel.Q
            "H" -> ErrorCorrectionLevel.H
            else -> ErrorCorrectionLevel.M
        }
    }

    /**
     * Get the maximum data capacity for a given QR code version and error correction level
     * Based on QR code specification for alphanumeric mode
     */
    private fun getMaxCapacityForVersion(version: Int, errorCorrection: String): Int {
        // Approximate capacities for UTF-8 bytes based on QR code specifications
        // This is a simplified lookup table - actual capacity depends on the data type
        return when (errorCorrection.uppercase()) {
            "L" -> when {
                version <= 10 -> 154 * version / 10
                version <= 20 -> 395 + (version - 10) * 41
                version <= 30 -> 809 + (version - 20) * 69
                else -> 1579 + (version - 30) * 137
            }
            "M" -> when {
                version <= 10 -> 122 * version / 10
                version <= 20 -> 312 + (version - 10) * 32
                version <= 30 -> 632 + (version - 20) * 54
                else -> 1252 + (version - 30) * 108
            }
            "Q" -> when {
                version <= 10 -> 88 * version / 10
                version <= 20 -> 224 + (version - 10) * 23
                version <= 30 -> 453 + (version - 20) * 38
                else -> 890 + (version - 30) * 77
            }
            "H" -> when {
                version <= 10 -> 65 * version / 10
                version <= 20 -> 170 + (version - 10) * 17
                version <= 30 -> 341 + (version - 20) * 29
                else -> 669 + (version - 30) * 58
            }
            else -> getMaxCapacityForVersion(version, "M")
        }
    }
}
