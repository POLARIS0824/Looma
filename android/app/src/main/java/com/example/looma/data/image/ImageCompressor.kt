package com.example.looma.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

data class CompressedImage(
    val bytes: ByteArray,
    val extension: String,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CompressedImage) return false
        return bytes.contentEquals(other.bytes) &&
            extension == other.extension &&
            mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + extension.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

fun interface ImageCompressor {
    fun compress(
        bytes: ByteArray,
        format: DetectedImageFormat,
        quality: Int,
        convertToWebp: Boolean,
    ): CompressedImage
}

object NoOpImageCompressor : ImageCompressor {
    override fun compress(
        bytes: ByteArray,
        format: DetectedImageFormat,
        quality: Int,
        convertToWebp: Boolean,
    ): CompressedImage = CompressedImage(bytes, format.extension, format.mimeType)
}

internal fun isAnimatedWebp(bytes: ByteArray): Boolean {
    if (bytes.size < 16) return false
    if (
        bytes[0] != 'R'.code.toByte() ||
        bytes[1] != 'I'.code.toByte() ||
        bytes[2] != 'F'.code.toByte() ||
        bytes[3] != 'F'.code.toByte() ||
        bytes[8] != 'W'.code.toByte() ||
        bytes[9] != 'E'.code.toByte() ||
        bytes[10] != 'B'.code.toByte() ||
        bytes[11] != 'P'.code.toByte()
    ) {
        return false
    }
    var offset = 12
    while (offset + 8 <= bytes.size) {
        val c0 = bytes[offset].toInt() and 0xFF
        val c1 = bytes[offset + 1].toInt() and 0xFF
        val c2 = bytes[offset + 2].toInt() and 0xFF
        val c3 = bytes[offset + 3].toInt() and 0xFF
        val chunkSize = (bytes[offset + 4].toInt() and 0xFF) or
            ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 7].toInt() and 0xFF) shl 24)
        if (chunkSize < 0) return false
        val payloadStart = offset + 8
        if (c0 == 'V'.code && c1 == 'P'.code && c2 == '8'.code && c3 == 'X'.code) {
            if (chunkSize >= 1 && payloadStart < bytes.size && (bytes[payloadStart].toInt() and 0x02) != 0) {
                return true
            }
        } else if (c0 == 'A'.code && c1 == 'N'.code && c2 == 'I'.code && c3 == 'M'.code) {
            return true
        }
        val paddedSize = chunkSize.toLong() + (chunkSize.toLong() and 1L)
        val nextOffset = payloadStart.toLong() + paddedSize
        if (nextOffset <= offset || nextOffset > bytes.size.toLong()) break
        offset = nextOffset.toInt()
    }
    return false
}

class AndroidImageCompressor : ImageCompressor {
    override fun compress(
        bytes: ByteArray,
        format: DetectedImageFormat,
        quality: Int,
        convertToWebp: Boolean,
    ): CompressedImage {
        val original = CompressedImage(bytes, format.extension, format.mimeType)
        if (format.extension == "gif") return original
        if (format.extension == "webp" && isAnimatedWebp(bytes)) return original
        if (!convertToWebp && format.extension == "bmp") return original

        val clampedQuality = quality.coerceIn(10, 100)
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return original

        var activeBitmap = decoded
        return try {
            val matrix = exifMatrix(bytes)
            if (!matrix.isIdentity) {
                val oriented = Bitmap.createBitmap(
                    decoded,
                    0,
                    0,
                    decoded.width,
                    decoded.height,
                    matrix,
                    true,
                )
                if (oriented !== decoded) {
                    decoded.recycle()
                    activeBitmap = oriented
                }
            }

            val target = if (convertToWebp) {
                Triple(webpFormat(), "webp", "image/webp")
            } else {
                when (format.extension) {
                    "png" -> Triple(Bitmap.CompressFormat.PNG, "png", "image/png")
                    "jpg", "jpeg" -> Triple(Bitmap.CompressFormat.JPEG, "jpg", "image/jpeg")
                    "webp" -> Triple(webpFormat(), "webp", "image/webp")
                    else -> return original
                }
            }
            val output = ByteArrayOutputStream()
            val ok = activeBitmap.compress(target.first, clampedQuality, output)
            if (!ok) return original
            val compressedBytes = output.toByteArray()
            if (compressedBytes.isEmpty()) return original
            if (!convertToWebp && compressedBytes.size >= bytes.size) {
                return original
            }
            CompressedImage(compressedBytes, target.second, target.third)
        } catch (_: Throwable) {
            original
        } finally {
            if (!activeBitmap.isRecycled) {
                activeBitmap.recycle()
            }
        }
    }

    private fun exifMatrix(bytes: ByteArray): Matrix {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    setRotate(180f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
    }

    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
}
