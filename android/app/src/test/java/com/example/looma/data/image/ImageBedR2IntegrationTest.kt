package com.example.looma.data.image

import com.example.looma.data.s3.S3Client
import com.example.looma.data.s3.S3EndpointConfig
import com.example.looma.data.s3.S3ErrorKind
import com.example.looma.data.s3.S3Exception
import com.example.looma.data.s3.createLoomaHttpClient
import com.example.looma.data.settings.ImageLinkFormat
import com.example.looma.data.settings.S3ImageBedConfig
import com.example.looma.domain.sync.RemotePrefix
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class ImageBedR2IntegrationTest {

    private lateinit var httpClient: HttpClient
    private lateinit var s3: S3Client
    private lateinit var uploader: ImageBedUploader
    private lateinit var baseConfig: S3ImageBedConfig
    private lateinit var basePrefix: String
    private lateinit var publicUrl: String

    @Before
    fun setUp() {
        val endpoint = System.getenv("R2_TEST_ENDPOINT").orEmpty().trim()
        val bucket = System.getenv("R2_TEST_BUCKET").orEmpty().trim()
        val accessKey = System.getenv("R2_TEST_ACCESS_KEY").orEmpty().trim()
        val secretKey = System.getenv("R2_TEST_SECRET_KEY").orEmpty().trim()
        val region = System.getenv("R2_TEST_REGION")?.trim()?.takeIf { it.isNotEmpty() } ?: "auto"
        publicUrl = System.getenv("R2_TEST_PUBLIC_URL").orEmpty().trim().trimEnd('/')
        basePrefix = System.getenv("R2_TEST_PATH_PREFIX")?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: "test"

        assumeTrue(
            "Skipping R2 integration tests: R2_TEST_* env vars not set",
            endpoint.isNotEmpty() && bucket.isNotEmpty() && accessKey.isNotEmpty() && secretKey.isNotEmpty(),
        )

        baseConfig = S3ImageBedConfig(
            endpoint = endpoint,
            region = region,
            bucket = bucket,
            accessKey = accessKey,
            secretKey = secretKey,
            pathPrefix = basePrefix,
            publicBaseUrl = publicUrl,
            pathStyle = true,
        )
        httpClient = createLoomaHttpClient()
        s3 = S3Client(httpClient)
        uploader = ImageBedUploader(httpClient)
    }

    @After
    fun tearDown() {
        if (::httpClient.isInitialized) {
            httpClient.close()
        }
    }

    private fun S3ImageBedConfig.toEndpointConfig(): S3EndpointConfig = S3EndpointConfig(
        endpoint = endpoint,
        region = region,
        bucket = bucket,
        accessKey = accessKey,
        secretKey = secretKey,
        pathStyle = pathStyle,
    )

    private fun uniqueSubPrefix(): String =
        "$basePrefix/looma-img-test-${System.currentTimeMillis()}-${UUID.randomUUID()}"

    private fun withPrefixCleanup(
        config: S3ImageBedConfig = baseConfig,
        block: suspend (String, S3ImageBedConfig) -> Unit,
    ) = runBlocking {
        val prefix = uniqueSubPrefix()
        val scopedConfig = config.copy(pathPrefix = prefix)
        try {
            block(prefix, scopedConfig)
        } finally {
            runCatching {
                val objects = s3.listAllObjects(scopedConfig.toEndpointConfig(), RemotePrefix("$prefix/"))
                for (obj in objects) {
                    runCatching { s3.deleteObject(scopedConfig.toEndpointConfig(), obj.key) }
                }
            }
        }
    }

    @Test
    fun `testConnection handles pathStyle true, false, bad secret, bad bucket, and blank endpoint`() {
        runBlocking {
            assertTrue(uploader.testConnection(baseConfig.copy(pathStyle = true)).isSuccess)
            assertTrue(uploader.testConnection(baseConfig.copy(pathStyle = false)).isSuccess)

            val badSecret = uploader.testConnection(baseConfig.copy(secretKey = "invalid-secret-key"))
            assertTrue(badSecret.isFailure)
            assertEquals(S3ErrorKind.AuthFailed, (badSecret.exceptionOrNull() as? S3Exception)?.kind)

            val badBucket = uploader.testConnection(baseConfig.copy(bucket = "looma-nonexistent-${UUID.randomUUID()}"))
            assertTrue(badBucket.isFailure)
            val bucketKind = (badBucket.exceptionOrNull() as? S3Exception)?.kind
            assertTrue(
                "Expected BucketNotFound or AuthFailed, got $bucketKind",
                bucketKind == S3ErrorKind.BucketNotFound || bucketKind == S3ErrorKind.AuthFailed,
            )

            val blankEndpoint = uploader.testConnection(baseConfig.copy(endpoint = "   "))
            assertTrue(blankEndpoint.isFailure)
            assertEquals(S3ErrorKind.InvalidConfig, (blankEndpoint.exceptionOrNull() as? S3Exception)?.kind)
        }
    }

    @Test
    fun `uploadBytes uploads PNG, JPEG, WebP, and GIF and serves exact bytes over public URL`() {
        val samples = listOf(
            Triple("sample.png", "image/png", SAMPLE_PNG),
            Triple("sample.jpg", "image/jpeg", SAMPLE_JPEG),
            Triple("sample.webp", "image/webp", SAMPLE_WEBP),
            Triple("sample.gif", "image/gif", SAMPLE_GIF),
        )

        withPrefixCleanup { prefix, scopedConfig ->
            for ((fileName, expectedMime, payload) in samples) {
                val remote = uploader.uploadBytes(
                    config = scopedConfig,
                    bytes = payload,
                    mimeType = null,
                    originalFileName = fileName,
                )
                assertTrue(remote.objectKey.startsWith("$prefix/"))
                assertNotNull(s3.headObject(scopedConfig.toEndpointConfig(), remote.objectKey))

                if (publicUrl.isNotEmpty()) {
                    assertTrue(
                        "Expected URL to start with $publicUrl/$prefix/, got ${remote.url}",
                        remote.url.startsWith("$publicUrl/$prefix/"),
                    )
                    val response = httpClient.get(remote.url)
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals(expectedMime, response.contentType()?.withoutParameters()?.toString())
                    assertArrayEquals(payload, response.readRawBytes())
                }
            }
        }
    }

    @Test
    fun `uploadBytes succeeds with publicRead false and publicRead true`() {
        for (publicRead in listOf(false, true)) {
            withPrefixCleanup(baseConfig.copy(publicRead = publicRead)) { _, scopedConfig ->
                val remote = uploader.uploadBytes(
                    config = scopedConfig,
                    bytes = SAMPLE_PNG,
                    mimeType = "image/png",
                    originalFileName = "acl-$publicRead.png",
                )
                val head = s3.headObject(scopedConfig.toEndpointConfig(), remote.objectKey)
                assertNotNull(head)
                assertEquals(SAMPLE_PNG.size.toLong(), head?.sizeBytes)
            }
        }
    }

    @Test
    fun `uploadBytes supports PicGo subdirs and special characters in originalFileName`() {
        val fixedNow = Instant.parse("2026-11-27T06:20:00Z")
        withPrefixCleanup(
            baseConfig.copy(namingTemplate = "{year}/{month}/{fileName}.{extName}"),
        ) { prefix, scopedConfig ->
            val remote = uploader.uploadBytes(
                config = scopedConfig,
                bytes = SAMPLE_PNG,
                mimeType = "image/png",
                originalFileName = "测试 图片 + 100% #1.png",
                now = fixedNow,
                zoneId = ZoneOffset.UTC,
            )
            assertTrue(remote.objectKey.startsWith("$prefix/2026/11/"))
            val head = s3.headObject(scopedConfig.toEndpointConfig(), remote.objectKey)
            assertNotNull(head)
            assertEquals(SAMPLE_PNG.size.toLong(), head?.sizeBytes)

            if (publicUrl.isNotEmpty()) {
                val response = httpClient.get(remote.url)
                assertEquals(HttpStatusCode.OK, response.status)
                assertArrayEquals(SAMPLE_PNG, response.readRawBytes())
            }
        }
    }

    @Test
    fun `uploadBytes respects overwriteExisting true and false`() {
        val payloadV1 = SAMPLE_PNG + byteArrayOf(1)
        val payloadV2 = SAMPLE_PNG + byteArrayOf(2, 3)
        val payloadV3 = SAMPLE_PNG + byteArrayOf(4, 5, 6)

        withPrefixCleanup(
            baseConfig.copy(
                namingTemplate = "fixed-name.{extName}",
                overwriteExisting = true,
            ),
        ) { prefix, scopedConfig ->
            val first = uploader.uploadBytes(scopedConfig, payloadV1, "image/png", "fixed.png")
            val second = uploader.uploadBytes(scopedConfig, payloadV2, "image/png", "fixed.png")
            assertEquals(first.objectKey, second.objectKey)
            val listed = s3.listAllObjects(scopedConfig.toEndpointConfig(), RemotePrefix("$prefix/"))
            assertEquals(1, listed.size)
            assertEquals(payloadV2.size.toLong(), listed.single().sizeBytes)
        }

        withPrefixCleanup(
            baseConfig.copy(
                namingTemplate = "fixed-name.{extName}",
                overwriteExisting = false,
            ),
        ) { prefix, scopedConfig ->
            val first = uploader.uploadBytes(scopedConfig, payloadV1, "image/png", "fixed.png")
            val second = uploader.uploadBytes(scopedConfig, payloadV2, "image/png", "fixed.png")
            val third = uploader.uploadBytes(scopedConfig, payloadV3, "image/png", "fixed.png")

            assertEquals("$prefix/fixed-name.png", first.objectKey)
            assertEquals("$prefix/fixed-name-1.png", second.objectKey)
            assertEquals("$prefix/fixed-name-2.png", third.objectKey)

            val listed = s3.listAllObjects(scopedConfig.toEndpointConfig(), RemotePrefix("$prefix/"))
            assertEquals(
                setOf(first.objectKey, second.objectKey, third.objectKey),
                listed.map { it.key }.toSet(),
            )
            assertEquals(payloadV1.size.toLong(), s3.headObject(scopedConfig.toEndpointConfig(), first.objectKey)?.sizeBytes)
            assertEquals(payloadV2.size.toLong(), s3.headObject(scopedConfig.toEndpointConfig(), second.objectKey)?.sizeBytes)
            assertEquals(payloadV3.size.toLong(), s3.headObject(scopedConfig.toEndpointConfig(), third.objectKey)?.sizeBytes)
        }
    }

    @Test
    fun `uploadBytes applies urlSuffix and linkFormat to snippet`() {
        val formats = listOf(
            ImageLinkFormat.Markdown,
            ImageLinkFormat.Html,
            ImageLinkFormat.PlainUrl,
            ImageLinkFormat.Custom,
        )
        withPrefixCleanup(
            baseConfig.copy(
                urlSuffix = "?x-oss-process=image/quality,q_80",
                customLinkTemplate = "[img]({url}|{alt})",
            ),
        ) { _, scopedConfig ->
            for (format in formats) {
                val remote = uploader.uploadBytes(
                    config = scopedConfig.copy(linkFormat = format),
                    bytes = SAMPLE_PNG,
                    mimeType = "image/png",
                    originalFileName = "hero.png",
                )
                assertTrue(remote.url.endsWith("?x-oss-process=image/quality,q_80"))
                when (format) {
                    ImageLinkFormat.Markdown -> assertEquals("![hero](${remote.url})", remote.snippet)
                    ImageLinkFormat.Html -> assertEquals("<img src=\"${remote.url}\" alt=\"hero\" />", remote.snippet)
                    ImageLinkFormat.PlainUrl -> assertEquals(remote.url, remote.snippet)
                    ImageLinkFormat.Custom -> assertEquals("[img](${remote.url}|hero)", remote.snippet)
                }
            }
        }
    }

    private companion object {
        val SAMPLE_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte(),
        )
        val SAMPLE_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0xFF.toByte(), 0xD9.toByte())
        val SAMPLE_WEBP = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, 0x1A, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20,
            0x0E, 0x00, 0x00, 0x00, 0x30, 0x01, 0x00, 0x9D.toByte(),
            0x01, 0x2A, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00,
        )
        val SAMPLE_GIF = "GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;".toByteArray(Charsets.ISO_8859_1)
    }
}
