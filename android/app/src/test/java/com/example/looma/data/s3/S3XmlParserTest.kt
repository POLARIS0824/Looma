package com.example.looma.data.s3

import java.io.IOException
import java.time.Instant
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.SAXNotRecognizedException

class S3XmlParserTest {

    @Test
    fun `parseListObjectsV2 parses namespaced response and unescapes XML entities`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Name>looma-bucket</Name>
                <IsTruncated>true</IsTruncated>
                <NextContinuationToken>tok+1/2=3&amp;4</NextContinuationToken>
                <Contents>
                    <Key>notes/a &amp; b &lt;1&gt; &quot;q&quot;.md</Key>
                    <LastModified>2026-11-24T12:34:56.789Z</LastModified>
                    <ETag>&quot;abc123def456&quot;</ETag>
                    <Size>42</Size>
                </Contents>
                <Contents>
                    <Key>empty.md</Key>
                    <LastModified>2026-11-24T00:00:00Z</LastModified>
                    <ETag>"d41d8cd98f00b204e9800998ecf8427e"</ETag>
                    <Size>0</Size>
                </Contents>
            </ListBucketResult>
        """.trimIndent()

        val page = S3XmlParser.parseListObjectsV2(xml)

        assertTrue(page.isTruncated)
        assertEquals("tok+1/2=3&4", page.nextContinuationToken)
        assertEquals(2, page.objects.size)

        val first = page.objects[0]
        assertEquals("notes/a & b <1> \"q\".md", first.key)
        assertEquals("abc123def456", first.etag)
        assertEquals(42L, first.sizeBytes)
        assertEquals(Instant.parse("2026-11-24T12:34:56.789Z").toEpochMilli(), first.lastModifiedMillis)

        val second = page.objects[1]
        assertEquals("empty.md", second.key)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", second.etag)
        assertEquals(0L, second.sizeBytes)
        assertEquals(Instant.parse("2026-11-24T00:00:00Z").toEpochMilli(), second.lastModifiedMillis)
    }

    @Test
    fun `parseListObjectsV2 parses non-namespaced response without truncation`() {
        val xml = """
            <ListBucketResult>
                <IsTruncated>false</IsTruncated>
                <NextContinuationToken>   </NextContinuationToken>
                <Contents>
                    <Key>笔记/测试.md</Key>
                    <ETag>"etag-1"</ETag>
                    <Size>1024</Size>
                    <LastModified>invalid-date</LastModified>
                </Contents>
                <Contents>
                    <Key></Key>
                    <ETag>"ignored"</ETag>
                    <Size>10</Size>
                </Contents>
            </ListBucketResult>
        """.trimIndent()

        val page = S3XmlParser.parseListObjectsV2(xml)

        assertFalse(page.isTruncated)
        assertNull(page.nextContinuationToken)
        assertEquals(1, page.objects.size)
        assertEquals("笔记/测试.md", page.objects[0].key)
        assertEquals("etag-1", page.objects[0].etag)
        assertEquals(1024L, page.objects[0].sizeBytes)
        assertEquals(0L, page.objects[0].lastModifiedMillis)
    }

    @Test(expected = IOException::class)
    fun `parseListObjectsV2 throws IOException when root element is not ListBucketResult`() {
        val xml = """
            <Error>
                <Code>NoSuchBucket</Code>
                <Message>Missing</Message>
            </Error>
        """.trimIndent()

        S3XmlParser.parseListObjectsV2(xml)
    }

    @Test(expected = Exception::class)
    fun `parseListObjectsV2 throws on malformed XML`() {
        S3XmlParser.parseListObjectsV2("<ListBucketResult><IsTruncated>true")
    }

    @Test
    fun `parseError extracts Code Message and RequestId with entity unescaping`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Error>
                <Code>NoSuchKey</Code>
                <Message>Key &quot;a &amp; b&quot; &lt;not found&gt;</Message>
                <RequestId>req-12345</RequestId>
            </Error>
        """.trimIndent()

        val error = S3XmlParser.parseError(xml)

        assertNotNull(error)
        assertEquals("NoSuchKey", error?.code)
        assertEquals("Key \"a & b\" <not found>", error?.message)
        assertEquals("req-12345", error?.requestId)
    }

    @Test
    fun `parseError returns null for blank, non-error, or malformed XML`() {
        assertNull(S3XmlParser.parseError(""))
        assertNull(S3XmlParser.parseError("   \n\t  "))
        assertNull(S3XmlParser.parseError("<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>"))
        assertNull(S3XmlParser.parseError("<Error><Code>Broken"))
    }

    @Test
    fun `parseListObjectsV2 and parseError tolerate Android SAXParserFactory without XInclude or feature support`() {
        val delegate = SAXParserFactory.newInstance()
        val androidLikeFactory = object : SAXParserFactory() {
            override fun newSAXParser(): SAXParser {
                val namespaceAware = isNamespaceAware
                val validating = isValidating
                return delegate.apply {
                    isNamespaceAware = namespaceAware
                    isValidating = validating
                }.newSAXParser()
            }

            override fun setFeature(name: String, value: Boolean) {
                throw SAXNotRecognizedException(name)
            }

            override fun getFeature(name: String): Boolean = throw SAXNotRecognizedException(name)

            override fun setXIncludeAware(state: Boolean) {
                throw UnsupportedOperationException(
                    "This parser doesn't support specification \"unknown\" version \"0.0\"",
                )
            }
        }

        val listXml = """
            <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <IsTruncated>false</IsTruncated>
                <Contents>
                    <Key>note.md</Key>
                    <ETag>"abc"</ETag>
                    <Size>5</Size>
                </Contents>
            </ListBucketResult>
        """.trimIndent()
        val page = S3XmlParser.parseListObjectsV2(listXml, androidLikeFactory)
        assertEquals(1, page.objects.size)
        assertEquals("note.md", page.objects[0].key)

        val errXml = "<Error><Code>NoSuchBucket</Code><Message>Missing</Message></Error>"
        val err = S3XmlParser.parseError(errXml, androidLikeFactory)
        assertEquals("NoSuchBucket", err?.code)
    }
}
