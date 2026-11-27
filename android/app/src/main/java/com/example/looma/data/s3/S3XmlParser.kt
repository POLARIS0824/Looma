package com.example.looma.data.s3

import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

internal data class S3ObjectSummary(
    val key: String,
    val etag: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

internal data class ListObjectsPage(
    val objects: List<S3ObjectSummary>,
    val isTruncated: Boolean,
    val nextContinuationToken: String?,
)

internal data class S3ErrorPayload(
    val code: String?,
    val message: String?,
    val requestId: String?,
)

internal object S3XmlParser {
    fun parseListObjectsV2(
        xml: String,
        factory: SAXParserFactory = SAXParserFactory.newInstance(),
    ): ListObjectsPage {
        val objects = mutableListOf<S3ObjectSummary>()
        var isTruncated = false
        var nextContinuationToken: String? = null
        var seenRoot = false

        var currentKey: String? = null
        var currentEtag: String? = null
        var currentSize = 0L
        var currentLastModified = 0L

        val stack = ArrayDeque<String>()
        val text = StringBuilder()

        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                InputSource(StringReader(""))

            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes?,
            ) {
                val name = elementName(localName, qName)
                if (stack.isEmpty() && name == "ListBucketResult") {
                    seenRoot = true
                }
                if (stack.lastOrNull() == "ListBucketResult" && name == "Contents") {
                    currentKey = null
                    currentEtag = null
                    currentSize = 0L
                    currentLastModified = 0L
                }
                stack.addLast(name)
                text.setLength(0)
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val name = elementName(localName, qName)
                val parent = if (stack.size >= 2) stack[stack.size - 2] else null
                when {
                    parent == "ListBucketResult" && name == "IsTruncated" -> {
                        isTruncated = text.toString().trim().equals("true", ignoreCase = true)
                    }
                    parent == "ListBucketResult" && name == "NextContinuationToken" -> {
                        nextContinuationToken = text.toString().trim().takeIf { it.isNotEmpty() }
                    }
                    parent == "ListBucketResult" && name == "Contents" -> {
                        val key = currentKey
                        if (!key.isNullOrEmpty()) {
                            objects += S3ObjectSummary(
                                key = key,
                                etag = currentEtag.orEmpty(),
                                sizeBytes = currentSize,
                                lastModifiedMillis = currentLastModified,
                            )
                        }
                    }
                    parent == "Contents" && name == "Key" -> {
                        currentKey = text.toString()
                    }
                    parent == "Contents" && name == "ETag" -> {
                        currentEtag = text.toString().trim().trim('"')
                    }
                    parent == "Contents" && name == "Size" -> {
                        currentSize = text.toString().trim().toLongOrNull() ?: 0L
                    }
                    parent == "Contents" && name == "LastModified" -> {
                        currentLastModified = parseIsoMillis(text.toString().trim())
                    }
                }
                if (stack.isNotEmpty()) stack.removeLast()
                text.setLength(0)
            }
        }

        createSaxParser(factory).parse(InputSource(StringReader(xml)), handler)
        if (!seenRoot) {
            throw IOException("Unexpected S3 ListObjectsV2 XML response")
        }
        return ListObjectsPage(
            objects = objects,
            isTruncated = isTruncated,
            nextContinuationToken = nextContinuationToken,
        )
    }

    fun parseError(
        xml: String,
        factory: SAXParserFactory = SAXParserFactory.newInstance(),
    ): S3ErrorPayload? {
        if (xml.isBlank()) return null
        return runCatching {
            var seenError = false
            var code: String? = null
            var message: String? = null
            var requestId: String? = null
            val stack = ArrayDeque<String>()
            val text = StringBuilder()

            val handler = object : DefaultHandler() {
                override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                    InputSource(StringReader(""))

                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: Attributes?,
                ) {
                    val name = elementName(localName, qName)
                    if (stack.isEmpty() && name == "Error") {
                        seenError = true
                    }
                    stack.addLast(name)
                    text.setLength(0)
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    text.append(ch, start, length)
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val name = elementName(localName, qName)
                    val parent = if (stack.size >= 2) stack[stack.size - 2] else null
                    if (parent == "Error") {
                        when (name) {
                            "Code" -> code = text.toString().trim().takeIf { it.isNotEmpty() }
                            "Message" -> message = text.toString().trim().takeIf { it.isNotEmpty() }
                            "RequestId" -> requestId = text.toString().trim().takeIf { it.isNotEmpty() }
                        }
                    }
                    if (stack.isNotEmpty()) stack.removeLast()
                    text.setLength(0)
                }
            }

            createSaxParser(factory).parse(InputSource(StringReader(xml)), handler)
            if (!seenError) null else S3ErrorPayload(code, message, requestId)
        }.getOrNull()
    }

    private fun createSaxParser(factory: SAXParserFactory = SAXParserFactory.newInstance()) = factory.apply {
        isNamespaceAware = true
        isValidating = false
        runCatching { isXIncludeAware = false }
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    }.newSAXParser()

    private fun elementName(localName: String?, qName: String?): String =
        localName?.takeIf { it.isNotEmpty() }
            ?: qName.orEmpty().substringAfter(':')

    private fun parseIsoMillis(raw: String): Long {
        if (raw.isEmpty()) return 0L
        return runCatching {
            OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
        }
    }
}
