package com.example.looma.data.s3

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S3RequestSignerTest {

    private fun config(
        endpoint: String = "https://s3.example.com",
        bucket: String = "looma-bucket",
        pathStyle: Boolean = true,
    ) = S3EndpointConfig(
        endpoint = endpoint,
        region = "us-east-1",
        bucket = bucket,
        accessKey = "ACCESSKEY",
        secretKey = "SECRETKEY",
        pathStyle = pathStyle,
    )

    @Test
    fun `parseEndpoint normalizes mixed-case host to lowercase`() {
        val parsed = S3RequestSigner.parseEndpoint("HTTPS://S3.Example.COM:8443/custom/")

        assertEquals("https", parsed.scheme)
        assertEquals("s3.example.com", parsed.host)
        assertEquals(8443, parsed.port)
        assertEquals(8443, parsed.effectivePort)
        assertEquals("s3.example.com:8443", parsed.hostHeader)
        assertEquals("/custom", parsed.basePath)
    }

    @Test
    fun `sign lowercases host and host header for mixed-case endpoint and virtual-hosted bucket`() {
        val now = Instant.parse("2026-11-24T12:00:00Z")
        val signed = S3RequestSigner.sign(
            config = config(
                endpoint = "https://S3.Example.COM",
                bucket = "My.Mixed-Bucket",
                pathStyle = false,
            ),
            method = "GET",
            objectKey = "notes/hello.md",
            now = now,
        )

        assertEquals("my.mixed-bucket.s3.example.com", signed.host)
        assertTrue(signed.headers.getValue("Authorization").contains("SignedHeaders=host;"))
        assertTrue(signed.url.startsWith("https://my.mixed-bucket.s3.example.com/notes/hello.md"))
    }

    @Test
    fun `sign formats canonicalUri and url differently for path-style vs virtual-hosted`() {
        val now = Instant.parse("2026-11-24T12:00:00Z")
        val pathStyled = S3RequestSigner.sign(
            config = config(pathStyle = true),
            method = "PUT",
            objectKey = "folder/note.md",
            now = now,
        )
        val virtualHosted = S3RequestSigner.sign(
            config = config(pathStyle = false),
            method = "PUT",
            objectKey = "folder/note.md",
            now = now,
        )

        assertEquals("s3.example.com", pathStyled.host)
        assertEquals("/looma-bucket/folder/note.md", pathStyled.canonicalUri)
        assertEquals("https://s3.example.com/looma-bucket/folder/note.md", pathStyled.url)

        assertEquals("looma-bucket.s3.example.com", virtualHosted.host)
        assertEquals("/folder/note.md", virtualHosted.canonicalUri)
        assertEquals("https://looma-bucket.s3.example.com/folder/note.md", virtualHosted.url)
        assertTrue(pathStyled.headers.getValue("Authorization").contains("Signature="))
        assertTrue(virtualHosted.headers.getValue("Authorization").contains("Signature="))
    }

    @Test
    fun `sign encodes UTF-8 and CJK object keys in canonicalUri and url`() {
        val now = Instant.parse("2026-11-24T12:00:00Z")
        val signed = S3RequestSigner.sign(
            config = config(pathStyle = true),
            method = "GET",
            objectKey = "笔记/2026 测试/图片.png",
            now = now,
        )

        assertEquals(
            "/looma-bucket/%E7%AC%94%E8%AE%B0/2026%20%E6%B5%8B%E8%AF%95/%E5%9B%BE%E7%89%87.png",
            signed.canonicalUri,
        )
        assertEquals(
            "https://s3.example.com/looma-bucket/%E7%AC%94%E8%AE%B0/2026%20%E6%B5%8B%E8%AF%95/%E5%9B%BE%E7%89%87.png",
            signed.url,
        )
    }
}
