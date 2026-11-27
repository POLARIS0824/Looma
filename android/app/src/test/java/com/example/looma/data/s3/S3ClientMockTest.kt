package com.example.looma.data.s3

import com.example.looma.domain.sync.RemotePrefix
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class S3ClientMockTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun config(pathStyle: Boolean = true) = S3EndpointConfig(
        endpoint = "https://s3.example.com",
        region = "auto",
        bucket = "looma-bucket",
        accessKey = "ACCESSKEY",
        secretKey = "SECRETKEY",
        pathStyle = pathStyle,
    )

    private fun mockClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpClient = HttpClient(MockEngine { request -> handler(request) }) {
        expectSuccess = false
    }

    @Test
    fun `listAllObjects paginates across pages with special-char continuation tokens`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val token1 = "abc+def/ghi=="
        val token2 = "xyz+123/456="

        val client = mockClient { request ->
            requests += request
            val token = request.url.parameters["continuation-token"]
            val xml = when (token) {
                null -> """
                    <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <IsTruncated>true</IsTruncated>
                        <NextContinuationToken>$token1</NextContinuationToken>
                        <Contents><Key>vault/a.md</Key><ETag>"e1"</ETag><Size>1</Size></Contents>
                    </ListBucketResult>
                """.trimIndent()
                token1 -> """
                    <ListBucketResult>
                        <IsTruncated>true</IsTruncated>
                        <NextContinuationToken>$token2</NextContinuationToken>
                        <Contents><Key>vault/b.md</Key><ETag>"e2"</ETag><Size>2</Size></Contents>
                    </ListBucketResult>
                """.trimIndent()
                token2 -> """
                    <ListBucketResult>
                        <IsTruncated>false</IsTruncated>
                        <Contents><Key>vault/c.md</Key><ETag>"e3"</ETag><Size>3</Size></Contents>
                    </ListBucketResult>
                """.trimIndent()
                else -> error("Unexpected token: $token")
            }
            respond(
                content = xml,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Xml.toString()),
            )
        }

        val s3 = S3Client(client)
        val result = s3.listAllObjects(config(), RemotePrefix("vault/"))

        assertEquals(listOf("vault/a.md", "vault/b.md", "vault/c.md"), result.map { it.key })
        assertEquals(3, requests.size)
        assertEquals("2", requests[0].url.parameters["list-type"])
        assertEquals("vault/", requests[0].url.parameters["prefix"])
        assertEquals(token1, requests[1].url.parameters["continuation-token"])
        assertEquals(token2, requests[2].url.parameters["continuation-token"])
        client.close()
    }

    @Test
    fun `listAllObjects throws InvalidResponse on missing or repeated continuation token`() = runTest {
        val missingTokenClient = mockClient {
            respond(
                content = """
                    <ListBucketResult>
                        <IsTruncated>true</IsTruncated>
                        <Contents><Key>a.md</Key><ETag>"e1"</ETag><Size>1</Size></Contents>
                    </ListBucketResult>
                """.trimIndent(),
                status = HttpStatusCode.OK,
            )
        }
        val missingError = runCatching {
            S3Client(missingTokenClient).listAllObjects(config(), RemotePrefix(""))
        }.exceptionOrNull() as? S3Exception
        assertEquals(S3ErrorKind.InvalidResponse, missingError?.kind)
        missingTokenClient.close()

        val repeatedTokenClient = mockClient {
            respond(
                content = """
                    <ListBucketResult>
                        <IsTruncated>true</IsTruncated>
                        <NextContinuationToken>same-token</NextContinuationToken>
                        <Contents><Key>a.md</Key><ETag>"e1"</ETag><Size>1</Size></Contents>
                    </ListBucketResult>
                """.trimIndent(),
                status = HttpStatusCode.OK,
            )
        }
        val repeatedError = runCatching {
            S3Client(repeatedTokenClient).listAllObjects(config(), RemotePrefix(""))
        }.exceptionOrNull() as? S3Exception
        assertEquals(S3ErrorKind.InvalidResponse, repeatedError?.kind)
        repeatedTokenClient.close()
    }

    @Test
    fun `downloadToFile rejects Content-Length over maxBytes and leaves no temp file`() = runTest {
        val destDir = tempFolder.newFolder("dl-header-limit")
        val destFile = File(destDir, "note.md")
        val client = mockClient {
            respond(
                content = ByteReadChannel(ByteArray(16)),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf("100"),
                    HttpHeaders.ETag to listOf("\"etag1\""),
                ),
            )
        }

        val err = runCatching {
            S3Client(client).downloadToFile(
                config = config(),
                objectKey = "note.md",
                destinationFile = destFile,
                maxBytes = 50L,
            )
        }.exceptionOrNull() as? S3Exception

        assertEquals(S3ErrorKind.FileTooLarge, err?.kind)
        assertTrue(destDir.listFiles().orEmpty().none { it.name.startsWith(".looma-dl-") })
        client.close()
    }

    @Test
    fun `downloadToFile rejects streaming body over maxBytes and cleans temp file`() = runTest {
        val destDir = tempFolder.newFolder("dl-stream-limit")
        val destFile = File(destDir, "note.md")
        val client = mockClient {
            respond(
                content = ByteReadChannel(ByteArray(64) { 7 }),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ETag to listOf("\"etag1\"")),
            )
        }

        val err = runCatching {
            S3Client(client).downloadToFile(
                config = config(),
                objectKey = "note.md",
                destinationFile = destFile,
                maxBytes = 32L,
            )
        }.exceptionOrNull() as? S3Exception

        assertEquals(S3ErrorKind.FileTooLarge, err?.kind)
        assertTrue(destDir.listFiles().orEmpty().none { it.name.startsWith(".looma-dl-") })
        client.close()
    }

    @Test
    fun `downloadToFile throws InvalidResponse on truncated body shorter than Content-Length`() = runTest {
        val destDir = tempFolder.newFolder("dl-truncated")
        val destFile = File(destDir, "note.md")
        val client = mockClient {
            respond(
                content = ByteReadChannel(ByteArray(10) { 1 }),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentLength to listOf("50"),
                    HttpHeaders.ETag to listOf("\"etag1\""),
                ),
            )
        }

        val err = runCatching {
            S3Client(client).downloadToFile(
                config = config(),
                objectKey = "note.md",
                destinationFile = destFile,
                maxBytes = 100L,
            )
        }.exceptionOrNull() as? S3Exception

        assertEquals(S3ErrorKind.InvalidResponse, err?.kind)
        assertTrue(destDir.listFiles().orEmpty().none { it.name.startsWith(".looma-dl-") })
        client.close()
    }

    @Test
    fun `uploadFile enforces maxBytes and file existence, and falls back to headObject when ETag is missing`() = runTest {
        val missingFile = File(tempFolder.root, "missing.md")
        val dirFile = tempFolder.newFolder("a-dir")
        val oversizedFile = tempFolder.newFile("big.bin").apply { writeBytes(ByteArray(20)) }
        val normalFile = tempFolder.newFile("ok.md").apply { writeText("hello") }

        val client = mockClient { request ->
            when (request.method) {
                HttpMethod.Put -> respond(content = "", status = HttpStatusCode.OK)
                HttpMethod.Head -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ETag to listOf("\"fallback-etag\""),
                        HttpHeaders.ContentLength to listOf("5"),
                    ),
                )
                else -> error("Unexpected method: ${request.method}")
            }
        }
        val s3 = S3Client(client)

        val missingErr = runCatching {
            s3.uploadFile(config(), "missing.md", missingFile, "text/markdown")
        }.exceptionOrNull() as? S3Exception
        assertEquals(S3ErrorKind.InvalidConfig, missingErr?.kind)

        val dirErr = runCatching {
            s3.uploadFile(config(), "dir.md", dirFile, "text/markdown")
        }.exceptionOrNull() as? S3Exception
        assertEquals(S3ErrorKind.InvalidConfig, dirErr?.kind)

        val bigErr = runCatching {
            s3.uploadFile(config(), "big.bin", oversizedFile, "application/octet-stream", maxBytes = 10L)
        }.exceptionOrNull() as? S3Exception
        assertEquals(S3ErrorKind.FileTooLarge, bigErr?.kind)

        val putResult = s3.uploadFile(config(), "ok.md", normalFile, "text/markdown; charset=utf-8")
        assertEquals("fallback-etag", putResult.etag)
        assertEquals(5L, putResult.sizeBytes)
        client.close()
    }

    @Test
    fun `mapErrorResponse maps S3 error codes and HTTP statuses accurately`() = runTest {
        data class Case(val status: HttpStatusCode, val code: String?, val expected: S3ErrorKind)

        val cases = listOf(
            Case(HttpStatusCode.NotFound, "NoSuchBucket", S3ErrorKind.BucketNotFound),
            Case(HttpStatusCode.Forbidden, "RequestTimeTooSkewed", S3ErrorKind.ClockSkew),
            Case(HttpStatusCode.Unauthorized, null, S3ErrorKind.AuthFailed),
            Case(HttpStatusCode.Forbidden, "AccessDenied", S3ErrorKind.AuthFailed),
            Case(HttpStatusCode.NotFound, "NoSuchKey", S3ErrorKind.NotFound),
            Case(HttpStatusCode.NotFound, null, S3ErrorKind.NotFound),
            Case(HttpStatusCode.Conflict, "ConditionalRequestConflict", S3ErrorKind.PreconditionFailed),
            Case(HttpStatusCode.PreconditionFailed, "PreconditionFailed", S3ErrorKind.PreconditionFailed),
            Case(HttpStatusCode.PayloadTooLarge, "EntityTooLarge", S3ErrorKind.FileTooLarge),
            Case(HttpStatusCode.TooManyRequests, "SlowDown", S3ErrorKind.RateLimited),
            Case(HttpStatusCode.InternalServerError, null, S3ErrorKind.ServerError),
            Case(HttpStatusCode.ServiceUnavailable, "ServiceUnavailable", S3ErrorKind.ServerError),
        )

        for (case in cases) {
            val client = mockClient {
                val body = if (case.code != null) {
                    "<Error><Code>${case.code}</Code><Message>detail</Message></Error>"
                } else {
                    ""
                }
                respond(content = body, status = case.status)
            }
            val err = runCatching {
                S3Client(client).testConnection(config(), RemotePrefix(""))
            }.exceptionOrNull() as? S3Exception

            assertEquals("Failed for status=${case.status} code=${case.code}", case.expected, err?.kind)
            client.close()
        }
    }

    @Test
    fun `S3Client preserves Content-Type header and encodes query parameters properly`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = mockClient { request ->
            captured += request
            when (request.method) {
                HttpMethod.Put -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ETag to listOf("\"etag-ok\"")),
                )
                HttpMethod.Get -> respond(
                    content = "<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>",
                    status = HttpStatusCode.OK,
                )
                else -> error("Unexpected method: ${request.method}")
            }
        }
        val s3 = S3Client(client)
        val file = tempFolder.newFile("note.md").apply { writeText("# Hi") }

        s3.uploadFile(config(), "notes/file.md", file, "text/markdown; charset=utf-8")
        s3.uploadBytes(config(), "img/a.png", byteArrayOf(1, 2, 3), "image/png")
        s3.listAllObjects(config(), RemotePrefix("笔记/a+b & c/"))

        assertEquals("text/markdown; charset=utf-8", captured[0].headers[HttpHeaders.ContentType])
        assertEquals("image/png", captured[1].headers[HttpHeaders.ContentType])

        val listUrl = captured[2].url.toString()
        assertTrue(listUrl.contains("list-type=2"))
        assertTrue(listUrl.contains("max-keys=1000"))
        assertTrue(
            listUrl.contains(
                "prefix=%E7%AC%94%E8%AE%B0%2Fa%2Bb%20%26%20c%2F",
            ),
        )
        assertEquals("笔记/a+b & c/", captured[2].url.parameters["prefix"])
        client.close()
    }
}
