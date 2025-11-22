package com.kvid.core

/**
 * Platform-independent interface for QR code generation
 */
interface QRCodeGenerator {
    /**
     * Generate a QR code from the given text data
     * @param data Text data to encode
     * @param version QR code version (1-40, higher = more data capacity)
     * @param errorCorrection Error correction level (L, M, Q, H)
     * @return Raw pixel data as ByteArray (grayscale or RGB)
     */
    fun generateQRCode(
        data: String,
        version: Int = 30,
        errorCorrection: String = "M"
    ): QRCodeData

    /**
     * Get metadata about QR code generation
     */
    fun getCapabilities(): QRCapabilities
}

data class QRCodeData(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,  // Raw pixel data (0-255 grayscale)
    val version: Int,
    val dataCapacity: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QRCodeData) return false

        if (width != other.width) return false
        if (height != other.height) return false
        if (!pixels.contentEquals(other.pixels)) return false
        if (version != other.version) return false
        if (dataCapacity != other.dataCapacity) return false

        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        result = 31 * result + version
        result = 31 * result + dataCapacity
        return result
    }
}

data class QRCapabilities(
    val maxDataCapacity: Int,        // Max bytes
    val supportedVersions: IntRange,
    val supportedErrorCorrection: List<String>,
    val supportsAnimation: Boolean = false
)
