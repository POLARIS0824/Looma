package com.example.looma.data.image

import com.example.looma.data.settings.ImageLinkFormat
import com.example.looma.data.settings.S3ImageBedConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBedUploaderTest {

    private fun validConfig(
        publicBaseUrl: String = "https://cdn.example.com",
        pathStyle: Boolean = true,
        encodeUrl: Boolean = true,
        urlSuffix: String = "",
    ) = S3ImageBedConfig(
        endpoint = "https://s3.example.com",
        region = "auto",
        bucket = "my-bucket",
        accessKey = "ak",
        secretKey = "sk",
        publicBaseUrl = publicBaseUrl,
        pathStyle = pathStyle,
        encodeUrl = encodeUrl,
        urlSuffix = urlSuffix,
    )

    @Test
    fun `buildPublicUrl handles publicBaseUrl, scheme, trailing slash, subpath, encoding, suffix, and S3 fallback`() {
        assertEquals(
            "https://cdn.example.com/images/2026%20test/%E5%9B%BE.png?w=800",
            ImageLinkFormatter.buildPublicUrl(
                validConfig(
                    publicBaseUrl = "cdn.example.com/",
                    encodeUrl = true,
                    urlSuffix = "?w=800",
                ),
                "/images/2026 test/图.png",
            ),
        )

        assertEquals(
            "https://cdn.example.com/assets/images/2026 test/图.png",
            ImageLinkFormatter.buildPublicUrl(
                validConfig(
                    publicBaseUrl = "https://cdn.example.com/assets/",
                    encodeUrl = false,
                ),
                "images/2026 test/图.png",
            ),
        )

        assertEquals(
            "https://s3.example.com/my-bucket/test/pic.png",
            ImageLinkFormatter.buildPublicUrl(
                validConfig(publicBaseUrl = "", pathStyle = true),
                "test/pic.png",
            ),
        )

        assertEquals(
            "https://my-bucket.s3.example.com/test/pic.png",
            ImageLinkFormatter.buildPublicUrl(
                validConfig(publicBaseUrl = "", pathStyle = false),
                "test/pic.png",
            ),
        )
    }

    @Test
    fun `formatSnippet renders Markdown, Html, PlainUrl, and Custom`() {
        val url = "https://cdn.example.com/pic.png"
        assertEquals("![photo]($url)", ImageLinkFormatter.formatSnippet(url, "photo", ImageLinkFormat.Markdown, ""))
        assertEquals("![]($url)", ImageLinkFormatter.formatSnippet(url, "", ImageLinkFormat.Markdown, ""))
        assertEquals("<img src=\"$url\" alt=\"photo\" />", ImageLinkFormatter.formatSnippet(url, "photo", ImageLinkFormat.Html, ""))
        assertEquals(url, ImageLinkFormatter.formatSnippet(url, "photo", ImageLinkFormat.PlainUrl, ""))
        assertEquals(
            "[photo -> $url]",
            ImageLinkFormatter.formatSnippet(url, "photo", ImageLinkFormat.Custom, "[{alt} -> {url}]"),
        )
    }

    @Test
    fun `formatSnippet escapes special characters in Markdown alt and Html attributes`() {
        val rawUrl = "https://cdn.example.com/pic.png?a=1&b=\"<2>\""
        val rawAlt = "a[b]\\c\nd&\"<e>\r"
        assertEquals(
            "![a\\[b\\]\\\\c d&\"<e> ]($rawUrl)",
            ImageLinkFormatter.formatSnippet(rawUrl, rawAlt, ImageLinkFormat.Markdown, ""),
        )
        assertEquals(
            "<img src=\"https://cdn.example.com/pic.png?a=1&amp;b=&quot;&lt;2&gt;&quot;\" alt=\"a[b]\\c d&amp;&quot;&lt;e&gt; \" />",
            ImageLinkFormatter.formatSnippet(rawUrl, rawAlt, ImageLinkFormat.Html, ""),
        )
    }

    @Test
    fun `uploadBytes validates unconfigured, empty, oversized, and unsupported inputs`() = runTest {
        val mockClient = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) })
        val uploader = ImageBedUploader(mockClient)

        val unconfiguredErr = runCatching {
            uploader.uploadBytes(S3ImageBedConfig(), SAMPLE_PNG, "image/png")
        }.exceptionOrNull() as? ImageUploadException
        assertEquals(ImageUploadFailure.NotConfigured, unconfiguredErr?.failure)

        val emptyErr = runCatching {
            uploader.uploadBytes(validConfig(), ByteArray(0), "image/png")
        }.exceptionOrNull() as? ImageUploadException
        assertEquals(ImageUploadFailure.UnsupportedImage, emptyErr?.failure)

        val oversized = ByteArray(12 * 1024 * 1024 + 1)
        val tooLargeErr = runCatching {
            uploader.uploadBytes(validConfig(), oversized, "image/png")
        }.exceptionOrNull() as? ImageUploadException
        assertEquals(ImageUploadFailure.ImageTooLarge, tooLargeErr?.failure)

        val unsupportedErr = runCatching {
            uploader.uploadBytes(validConfig(), "not-an-image".toByteArray(), "text/plain")
        }.exceptionOrNull() as? ImageUploadException
        assertEquals(ImageUploadFailure.UnsupportedImage, unsupportedErr?.failure)
    }

    @Test
    fun `uploadBytes invokes compressor only when compressEnabled or convertToWebp is true`() = runTest {
        val uploadedContentTypes = mutableListOf<String?>()
        val mockClient = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Put) {
                uploadedContentTypes += request.headers[HttpHeaders.ContentType] ?: request.body.contentType?.toString()
                respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"abc\""))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        })

        var compressorCalled = false
        val fakeCompressor = ImageCompressor { _, _, _, convertToWebp ->
            compressorCalled = true
            if (convertToWebp) {
                CompressedImage(byteArrayOf(0x52, 0x49, 0x46, 0x46), "webp", "image/webp")
            } else {
                CompressedImage(SAMPLE_PNG, "png", "image/png")
            }
        }
        val uploader = ImageBedUploader(mockClient, fakeCompressor)

        uploader.uploadBytes(
            validConfig().copy(compressEnabled = false, convertToWebp = false),
            SAMPLE_PNG,
            "image/png",
            "a.png",
        )
        assertFalse(compressorCalled)

        val converted = uploader.uploadBytes(
            validConfig().copy(compressEnabled = false, convertToWebp = true),
            SAMPLE_PNG,
            "image/png",
            "a.png",
        )
        assertTrue(compressorCalled)
        assertTrue(converted.objectKey.endsWith(".webp"))
        assertEquals("image/webp", uploadedContentTypes.last())

        compressorCalled = false
        uploader.uploadBytes(
            validConfig().copy(compressEnabled = true, convertToWebp = false),
            SAMPLE_PNG,
            "image/png",
            "a.png",
        )
        assertTrue(compressorCalled)
    }

    @Test
    fun `uploadBytes passes compressQuality when convertToWebp is true without compressEnabled and skips compressor for webp`() = runTest {
        val mockClient = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Put) {
                respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"abc\""))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        })

        val capturedQualities = mutableListOf<Int>()
        var compressorCalled = false
        val fakeCompressor = ImageCompressor { _, _, quality, _ ->
            compressorCalled = true
            capturedQualities += quality
            CompressedImage(STATIC_WEBP_VP8, "webp", "image/webp")
        }
        val uploader = ImageBedUploader(mockClient, fakeCompressor)

        uploader.uploadBytes(
            validConfig().copy(compressEnabled = false, convertToWebp = true, compressQuality = 60),
            SAMPLE_PNG,
            "image/png",
            "a.png",
        )
        assertTrue(compressorCalled)
        assertEquals(listOf(60), capturedQualities)

        compressorCalled = false
        uploader.uploadBytes(
            validConfig().copy(compressEnabled = false, convertToWebp = true, compressQuality = 60),
            STATIC_WEBP_VP8,
            "image/webp",
            "a.webp",
        )
        assertFalse(compressorCalled)
    }

    @Test
    fun `isAnimatedWebp detects VP8X animation flag and ANIM chunk`() {
        assertTrue(isAnimatedWebp(webpWithVp8xFlags(0x02)))
        assertTrue(isAnimatedWebp(webpWithAnimChunk()))
        assertFalse(isAnimatedWebp(webpWithVp8xFlags(0x00)))
        assertFalse(isAnimatedWebp(STATIC_WEBP_VP8))
        assertFalse(isAnimatedWebp(STATIC_WEBP_VP8L))
        assertFalse(isAnimatedWebp(SAMPLE_PNG))
        assertFalse(isAnimatedWebp(byteArrayOf(0x01, 0x02, 0x03)))
    }

    private fun webpWithVp8xFlags(flags: Int): ByteArray = byteArrayOf(
        'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
        24, 0, 0, 0,
        'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
        'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), 'X'.code.toByte(),
        10, 0, 0, 0,
        flags.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0,
    )

    private fun webpWithAnimChunk(): ByteArray = byteArrayOf(
        'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
        20, 0, 0, 0,
        'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
        'A'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte(),
        6, 0, 0, 0,
        0, 0, 0, 0, 0, 0,
    )

    private companion object {
        val SAMPLE_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        )
        val STATIC_WEBP_VP8 = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            16, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
            4, 0, 0, 0,
            0, 0, 0, 0,
        )
        val STATIC_WEBP_VP8L = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            16, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), 'L'.code.toByte(),
            4, 0, 0, 0,
            0x2F, 0, 0, 0,
        )
    }
}
