package com.example.looma.data.s3

import com.example.looma.domain.sync.RemotePrefix
import io.ktor.client.HttpClient
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class S3ClientR2IntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var httpClient: HttpClient
    private lateinit var s3: S3Client
    private lateinit var baseConfig: S3EndpointConfig

    @Before
    fun setUp() {
        val endpoint = System.getenv("R2_TEST_ENDPOINT").orEmpty().trim()
        val bucket = System.getenv("R2_TEST_BUCKET").orEmpty().trim()
        val accessKey = System.getenv("R2_TEST_ACCESS_KEY").orEmpty().trim()
        val secretKey = System.getenv("R2_TEST_SECRET_KEY").orEmpty().trim()
        val region = System.getenv("R2_TEST_REGION")?.trim()?.takeIf { it.isNotEmpty() } ?: "auto"

        assumeTrue(
            "Skipping R2 integration tests: R2_TEST_* env vars not set",
            endpoint.isNotEmpty() && bucket.isNotEmpty() && accessKey.isNotEmpty() && secretKey.isNotEmpty(),
        )

        baseConfig = S3EndpointConfig(
            endpoint = endpoint,
            region = region,
            bucket = bucket,
            accessKey = accessKey,
            secretKey = secretKey,
            pathStyle = true,
        )
        httpClient = createLoomaHttpClient()
        s3 = S3Client(httpClient)
    }

    @After
    fun tearDown() {
        if (::httpClient.isInitialized) {
            httpClient.close()
        }
    }

    private fun uniquePrefix(): RemotePrefix =
        RemotePrefix("looma-test-${System.currentTimeMillis()}-${UUID.randomUUID()}/")

    private fun withPrefixCleanup(
        config: S3EndpointConfig = baseConfig,
        block: suspend (RemotePrefix) -> Unit,
    ) = runBlocking {
        val prefix = uniquePrefix()
        try {
            block(prefix)
        } finally {
            runCatching {
                val objects = s3.listAllObjects(config, prefix)
                for (obj in objects) {
                    runCatching { s3.deleteObject(config, obj.key) }
                }
            }
        }
    }

    @Test
    fun `round-trip CRUD works with pathStyle true and pathStyle false`() {
        for (pathStyle in listOf(true, false)) {
            val cfg = baseConfig.copy(pathStyle = pathStyle)
            withPrefixCleanup(cfg) { prefix ->
                s3.testConnection(cfg, prefix)

                val key = prefix.value + "folder/hello.md"
                val srcFile = tempFolder.newFile("hello-$pathStyle.md").apply {
                    writeText("# Hello R2\npathStyle=$pathStyle", Charsets.UTF_8)
                }

                val put = s3.uploadFile(
                    config = cfg,
                    objectKey = key,
                    file = srcFile,
                    contentType = "text/markdown; charset=utf-8",
                )
                assertTrue(put.etag.isNotBlank())
                assertEquals(srcFile.length(), put.sizeBytes)

                val head = s3.headObject(cfg, key)
                assertNotNull(head)
                assertEquals(put.etag, head?.etag)
                assertEquals(srcFile.length(), head?.sizeBytes)

                val listed = s3.listAllObjects(cfg, prefix)
                assertEquals(listOf(key), listed.map { it.key })

                val dstFile = File(tempFolder.root, "downloaded-$pathStyle.md")
                val dl = s3.downloadToFile(cfg, key, dstFile)
                assertEquals(put.sha256, dl.sha256)
                assertEquals(srcFile.readText(Charsets.UTF_8), dstFile.readText(Charsets.UTF_8))

                s3.deleteObject(cfg, key)
                assertNull(s3.headObject(cfg, key))
                assertTrue(s3.listAllObjects(cfg, prefix).isEmpty())
            }
        }
    }

    @Test
    fun `uploadFile and uploadBytes handle markdown, 0-byte, binary, mtimeMillis, and special keys`() {
        withPrefixCleanup { prefix ->
            val emptyFile = tempFolder.newFile("empty.md")
            val emptyKey = prefix.value + "empty.md"
            val emptyPut = s3.uploadFile(
                config = baseConfig,
                objectKey = emptyKey,
                file = emptyFile,
                contentType = "text/markdown; charset=utf-8",
                mtimeMillis = 1_700_000_000_123L,
            )
            assertEquals(0L, emptyPut.sizeBytes)
            val emptyDlFile = File(tempFolder.root, "empty-dl.md")
            val emptyDl = s3.downloadToFile(baseConfig, emptyKey, emptyDlFile)
            assertEquals(0L, emptyDl.sizeBytes)
            assertEquals(emptyPut.sha256, emptyDl.sha256)
            assertEquals(0L, emptyDlFile.length())

            val binaryBytes = ByteArray(4096) { i -> ((i * 31) and 0xFF).toByte() }
            val binaryKey = prefix.value + "assets/blob.bin"
            val binaryPut = s3.uploadBytes(
                config = baseConfig,
                objectKey = binaryKey,
                bytes = binaryBytes,
                contentType = "application/octet-stream",
            )
            val binaryDlFile = File(tempFolder.root, "blob-dl.bin")
            val binaryDl = s3.downloadToFile(baseConfig, binaryKey, binaryDlFile)
            assertEquals(binaryPut.sha256, binaryDl.sha256)
            assertArrayEquals(binaryBytes, binaryDlFile.readBytes())

            val specialKey = prefix.value + "笔记/2026 测试/a + b % c # d & (1).md"
            val specialContent = "# 中文内容\n+ % # & ( )".toByteArray(Charsets.UTF_8)
            val specialPut = s3.uploadBytes(
                config = baseConfig,
                objectKey = specialKey,
                bytes = specialContent,
                contentType = "text/markdown; charset=utf-8",
            )
            val listedKeys = s3.listAllObjects(baseConfig, prefix).map { it.key }.toSet()
            assertTrue(specialKey in listedKeys)

            val specialDlFile = File(tempFolder.root, "special-dl.md")
            val specialDl = s3.downloadToFile(baseConfig, specialKey, specialDlFile)
            assertEquals(specialPut.sha256, specialDl.sha256)
            assertArrayEquals(specialContent, specialDlFile.readBytes())
        }
    }

    @Test
    fun `conditional put, download, and delete enforce ETag preconditions`() {
        withPrefixCleanup { prefix ->
            val key = prefix.value + "cond/note.md"

            val created = s3.uploadBytes(
                config = baseConfig,
                objectKey = key,
                bytes = "v1".toByteArray(),
                contentType = "text/markdown",
                condition = S3PutCondition.IfNotExists,
            )

            val ifNotExistsErr = runCatching {
                s3.uploadBytes(
                    config = baseConfig,
                    objectKey = key,
                    bytes = "v1b".toByteArray(),
                    contentType = "text/markdown",
                    condition = S3PutCondition.IfNotExists,
                )
            }.exceptionOrNull() as? S3Exception
            assertEquals(S3ErrorKind.PreconditionFailed, ifNotExistsErr?.kind)

            val ifMatchMismatchErr = runCatching {
                s3.uploadBytes(
                    config = baseConfig,
                    objectKey = key,
                    bytes = "v2".toByteArray(),
                    contentType = "text/markdown",
                    condition = S3PutCondition.IfMatch("00000000000000000000000000000000"),
                )
            }.exceptionOrNull() as? S3Exception
            assertEquals(S3ErrorKind.PreconditionFailed, ifMatchMismatchErr?.kind)

            val updated = s3.uploadBytes(
                config = baseConfig,
                objectKey = key,
                bytes = "v2".toByteArray(),
                contentType = "text/markdown",
                condition = S3PutCondition.IfMatch(created.etag),
            )

            val dlMismatchErr = runCatching {
                s3.downloadToFile(
                    config = baseConfig,
                    objectKey = key,
                    destinationFile = File(tempFolder.root, "cond-mismatch.md"),
                    expectedEtag = created.etag,
                )
            }.exceptionOrNull() as? S3Exception
            assertEquals(S3ErrorKind.PreconditionFailed, dlMismatchErr?.kind)

            val dlMatch = s3.downloadToFile(
                config = baseConfig,
                objectKey = key,
                destinationFile = File(tempFolder.root, "cond-match.md"),
                expectedEtag = updated.etag,
            )
            assertEquals(updated.sha256, dlMatch.sha256)

            val delMismatchErr = runCatching {
                s3.deleteObject(baseConfig, key, expectedEtag = created.etag)
            }.exceptionOrNull() as? S3Exception
            assertEquals(S3ErrorKind.PreconditionFailed, delMismatchErr?.kind)

            s3.deleteObject(baseConfig, key, expectedEtag = updated.etag)
            assertNull(s3.headObject(baseConfig, key))

            s3.deleteObject(baseConfig, key, expectedEtag = updated.etag)
        }
    }

    @Test
    fun `error mapping handles missing bucket, bad credentials, and missing objects`() {
        withPrefixCleanup { prefix ->
            val missingBucketCfg = baseConfig.copy(bucket = "looma-nonexistent-${UUID.randomUUID()}")
            val bucketErr = runCatching {
                s3.testConnection(missingBucketCfg, prefix)
            }.exceptionOrNull() as? S3Exception
            assertNotNull(bucketErr)
            assertTrue(
                "Expected BucketNotFound or AuthFailed, got ${bucketErr?.kind}",
                bucketErr?.kind == S3ErrorKind.BucketNotFound || bucketErr?.kind == S3ErrorKind.AuthFailed,
            )

            val badSecretCfg = baseConfig.copy(secretKey = "invalid-secret-key")
            val authErr = runCatching {
                s3.testConnection(badSecretCfg, prefix)
            }.exceptionOrNull() as? S3Exception
            assertEquals(S3ErrorKind.AuthFailed, authErr?.kind)

            val missingKey = prefix.value + "does-not-exist.md"
            assertNull(s3.headObject(baseConfig, missingKey))

            val notFoundErr = runCatching {
                s3.downloadToFile(
                    config = baseConfig,
                    objectKey = missingKey,
                    destinationFile = File(tempFolder.root, "missing-dl.md"),
                )
            }.exceptionOrNull() as? S3Exception
            assertEquals(S3ErrorKind.NotFound, notFoundErr?.kind)
        }
    }
}
