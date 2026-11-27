package com.example.looma.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageFormatSnifferTest {

    @Test
    fun `detects magic bytes for PNG, JPEG, WebP, GIF87a, GIF89a, and BMP when mime is null or generic`() {
        val cases = listOf(
            Triple(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00),
                "png",
                "image/png",
            ),
            Triple(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00),
                "jpg",
                "image/jpeg",
            ),
            Triple(
                byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x10, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50),
                "webp",
                "image/webp",
            ),
            Triple(
                "GIF87a\u0001\u0000".toByteArray(Charsets.ISO_8859_1),
                "gif",
                "image/gif",
            ),
            Triple(
                "GIF89a\u0001\u0000".toByteArray(Charsets.ISO_8859_1),
                "gif",
                "image/gif",
            ),
            Triple(
                byteArrayOf(0x42, 0x4D, 0x1E, 0x00, 0x00, 0x00),
                "bmp",
                "image/bmp",
            ),
        )

        for ((bytes, expectedExt, expectedMime) in cases) {
            for (mime in listOf(null, "", "application/octet-stream")) {
                val detected = ImageFormatSniffer.detect(mimeType = mime, bytes = bytes)
                assertEquals(DetectedImageFormat(expectedExt, expectedMime), detected)
            }
        }
    }

    @Test
    fun `detects standard image mime types`() {
        val dummy = byteArrayOf(1, 2, 3, 4)
        assertEquals(DetectedImageFormat("png", "image/png"), ImageFormatSniffer.detect("image/png", dummy))
        assertEquals(DetectedImageFormat("jpg", "image/jpeg"), ImageFormatSniffer.detect("image/jpeg", dummy))
        assertEquals(DetectedImageFormat("jpg", "image/jpeg"), ImageFormatSniffer.detect("image/jpg", dummy))
        assertEquals(DetectedImageFormat("webp", "image/webp"), ImageFormatSniffer.detect("image/webp", dummy))
        assertEquals(DetectedImageFormat("gif", "image/gif"), ImageFormatSniffer.detect("image/gif", dummy))
        assertEquals(DetectedImageFormat("bmp", "image/bmp"), ImageFormatSniffer.detect("image/bmp", dummy))
        assertEquals(DetectedImageFormat("bmp", "image/bmp"), ImageFormatSniffer.detect("image/x-ms-bmp", dummy))
    }

    @Test
    fun `rejects empty bytes, random non-image bytes, and unsupported mime types`() {
        assertNull(ImageFormatSniffer.detect(null, ByteArray(0)))
        assertNull(ImageFormatSniffer.detect("image/png", ByteArray(0)))
        assertNull(ImageFormatSniffer.detect(null, "hello world".toByteArray()))
        assertNull(ImageFormatSniffer.detect("application/octet-stream", byteArrayOf(0x01, 0x02, 0x03, 0x04)))
        assertNull(ImageFormatSniffer.detect("application/pdf", "%PDF-1.7".toByteArray()))
        assertNull(ImageFormatSniffer.detect("text/plain", "plain text".toByteArray()))
    }
}
