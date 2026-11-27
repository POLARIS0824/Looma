package com.example.looma.data.image

import java.util.Locale

data class DetectedImageFormat(
    val extension: String,
    val mimeType: String,
)

object ImageFormatSniffer {
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

    fun detect(
        mimeType: String?,
        bytes: ByteArray,
        fileName: String? = null,
    ): DetectedImageFormat? {
        if (bytes.isEmpty()) return null

        val normalizedMime = mimeType
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.substringBefore(';')
            ?.trim()
            .orEmpty()

        val mimeFormat = formatForMime(normalizedMime)
        val isGenericMime = normalizedMime.isEmpty() ||
            normalizedMime == "application/octet-stream" ||
            normalizedMime == "image/*"

        if (!isGenericMime && mimeFormat == null) {
            return null
        }

        val sniffed = sniffMagicBytes(bytes)
        if (sniffed != null) {
            return sniffed
        }

        if (mimeFormat != null) {
            return mimeFormat
        }

        val ext = fileName
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return formatForExtension(ext)
    }

    private fun sniffMagicBytes(bytes: ByteArray): DetectedImageFormat? {
        if (bytes.size >= PNG_MAGIC.size && PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] }) {
            return DetectedImageFormat("png", "image/png")
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return DetectedImageFormat("jpg", "image/jpeg")
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return DetectedImageFormat("webp", "image/webp")
        }
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()
        ) {
            return DetectedImageFormat("gif", "image/gif")
        }
        if (bytes.size >= 2 &&
            bytes[0] == 'B'.code.toByte() &&
            bytes[1] == 'M'.code.toByte()
        ) {
            return DetectedImageFormat("bmp", "image/bmp")
        }
        return null
    }

    private fun formatForMime(mimeType: String): DetectedImageFormat? = when (mimeType) {
        "image/png" -> DetectedImageFormat("png", "image/png")
        "image/jpeg", "image/jpg" -> DetectedImageFormat("jpg", "image/jpeg")
        "image/webp" -> DetectedImageFormat("webp", "image/webp")
        "image/gif" -> DetectedImageFormat("gif", "image/gif")
        "image/bmp", "image/x-ms-bmp" -> DetectedImageFormat("bmp", "image/bmp")
        else -> null
    }

    private fun formatForExtension(extension: String): DetectedImageFormat? = when (extension) {
        "png" -> DetectedImageFormat("png", "image/png")
        "jpg", "jpeg" -> DetectedImageFormat("jpg", "image/jpeg")
        "webp" -> DetectedImageFormat("webp", "image/webp")
        "gif" -> DetectedImageFormat("gif", "image/gif")
        "bmp" -> DetectedImageFormat("bmp", "image/bmp")
        else -> null
    }
}
