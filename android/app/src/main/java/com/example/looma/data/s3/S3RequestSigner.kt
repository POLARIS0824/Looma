package com.example.looma.data.s3

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class SignedS3Request(
    val method: String,
    val scheme: String,
    val host: String,
    val effectivePort: Int,
    val canonicalUri: String,
    val encodedQueryParameters: Map<String, String>,
    val url: String,
    val headers: Map<String, String>,
)

internal object S3RequestSigner {
    const val EMPTY_PAYLOAD_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "s3"
    private const val TERMINATOR = "aws4_request"
    private const val HEX = "0123456789abcdef"

    private val WHITESPACE_COLLAPSE = Regex("\\s+")
    private val amzDateFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
    private val dateStampFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd")
        .withZone(ZoneOffset.UTC)

    data class Endpoint(
        val scheme: String,
        val host: String,
        val port: Int,
        val basePath: String,
    ) {
        val effectivePort: Int
            get() = when {
                port <= 0 -> -1
                scheme.equals("https", ignoreCase = true) && port == 443 -> -1
                scheme.equals("http", ignoreCase = true) && port == 80 -> -1
                else -> port
            }

        val hostHeader: String
            get() = if (effectivePort > 0) "$host:$effectivePort" else host
    }

    fun parseEndpoint(raw: String): Endpoint {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "the S3 endpoint is empty" }
        val normalized = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = URI(normalized)
        val scheme = (uri.scheme ?: "https").lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "unsupported S3 endpoint scheme: $scheme" }
        val host = requireNotNull(uri.host?.takeIf { it.isNotBlank() }) { "the S3 endpoint has no host" }
        return Endpoint(
            scheme = scheme,
            host = host,
            port = uri.port,
            basePath = uri.path.orEmpty().trimEnd('/'),
        )
    }

    fun objectUrl(
        config: S3EndpointConfig,
        objectKey: String,
        publicBaseUrl: String = "",
    ): String {
        val encodedKey = encodePath(objectKey)
        val publicBase = publicBaseUrl.trim().trimEnd('/')
        if (publicBase.isNotEmpty()) return "$publicBase/$encodedKey"
        val parsed = parseEndpoint(config.endpoint)
        val encodedBase = encodePath(parsed.basePath)
        val bucket = config.bucket.trim()
        return if (config.pathStyle) {
            "${parsed.scheme}://${parsed.hostHeader}$encodedBase/${encodeSegment(bucket)}/$encodedKey"
        } else {
            "${parsed.scheme}://${encodeSegment(bucket)}.${parsed.hostHeader}$encodedBase/$encodedKey"
        }
    }

    fun sign(
        config: S3EndpointConfig,
        method: String,
        objectKey: String? = null,
        queryParameters: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        payloadSha256: String = EMPTY_PAYLOAD_SHA256,
        now: Instant = Instant.now(),
    ): SignedS3Request {
        val parsed = parseEndpoint(config.endpoint)
        val bucket = config.bucket.trim()
        require(bucket.isNotEmpty()) { "the S3 bucket is empty" }
        val region = config.region.trim()
        require(region.isNotEmpty()) { "the S3 region is empty" }
        val accessKey = config.accessKey.trim()
        require(accessKey.isNotEmpty()) { "the S3 access key is empty" }
        require(config.secretKey.isNotEmpty()) { "the S3 secret key is empty" }

        val normalizedMethod = method.trim().uppercase(Locale.ROOT)
        val requestHost = if (config.pathStyle) parsed.host else "$bucket.${parsed.host}"
        val hostHeader = if (parsed.effectivePort > 0) "$requestHost:${parsed.effectivePort}" else requestHost
        val cleanKey = objectKey?.trimStart('/')?.takeIf { it.isNotEmpty() }
        val rawPath = if (config.pathStyle) {
            if (cleanKey != null) "${parsed.basePath}/$bucket/$cleanKey" else "${parsed.basePath}/$bucket"
        } else {
            if (cleanKey != null) "${parsed.basePath}/$cleanKey" else parsed.basePath.ifEmpty { "/" }
        }
        val canonicalUri = encodePath(rawPath)

        val sortedQueryPairs = queryParameters.entries
            .map { (key, value) -> encodeSegment(key) to encodeSegment(value) }
            .sortedWith(compareBy({ it.first }, { it.second }))
        val encodedQueryParameters = linkedMapOf<String, String>().apply {
            sortedQueryPairs.forEach { (key, value) -> put(key, value) }
        }
        val canonicalQueryString = sortedQueryPairs.joinToString("&") { (key, value) -> "$key=$value" }

        val url = buildString {
            append(parsed.scheme).append("://").append(hostHeader).append(canonicalUri)
            if (canonicalQueryString.isNotEmpty()) {
                append('?').append(canonicalQueryString)
            }
        }

        val timestamp = amzDateFormatter.format(now)
        val dateStamp = dateStampFormatter.format(now)

        val signedHeaderValues = sortedMapOf(
            "host" to hostHeader,
            "x-amz-content-sha256" to payloadSha256,
            "x-amz-date" to timestamp,
        )
        val outgoingHeaders = linkedMapOf<String, String>()
        val reservedLower = hashSetOf("host", "authorization", "x-amz-content-sha256", "x-amz-date")
        for ((rawName, rawValue) in headers) {
            val trimmedName = rawName.trim()
            val lowerName = trimmedName.lowercase(Locale.ROOT)
            if (lowerName.isEmpty() || !reservedLower.add(lowerName)) continue
            val normalizedValue = rawValue.trim().replace(WHITESPACE_COLLAPSE, " ")
            signedHeaderValues[lowerName] = normalizedValue
            outgoingHeaders[trimmedName] = normalizedValue
        }

        val canonicalHeaders = buildString {
            signedHeaderValues.forEach { (name, value) ->
                append(name).append(':').append(value).append('\n')
            }
        }
        val signedHeaders = signedHeaderValues.keys.joinToString(";")
        val canonicalRequest = buildString {
            append(normalizedMethod).append('\n')
            append(canonicalUri).append('\n')
            append(canonicalQueryString).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(payloadSha256)
        }

        val scope = "$dateStamp/$region/$SERVICE/$TERMINATOR"
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(timestamp).append('\n')
            append(scope).append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }
        var signingKey = ("AWS4" + config.secretKey).toByteArray(Charsets.UTF_8)
        for (part in listOf(dateStamp, region, SERVICE, TERMINATOR)) {
            signingKey = hmacSha256(signingKey, part)
        }
        val signature = hex(hmacSha256(signingKey, stringToSign))
        val authorization =
            "$ALGORITHM Credential=$accessKey/$scope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"

        outgoingHeaders["x-amz-content-sha256"] = payloadSha256
        outgoingHeaders["x-amz-date"] = timestamp
        outgoingHeaders["Authorization"] = authorization

        return SignedS3Request(
            method = normalizedMethod,
            scheme = parsed.scheme,
            host = requestHost,
            effectivePort = parsed.effectivePort,
            canonicalUri = canonicalUri,
            encodedQueryParameters = encodedQueryParameters,
            url = url,
            headers = outgoingHeaders,
        )
    }

    fun sha256Hex(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    internal fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encodeSegment(it) }

    internal fun encodeSegment(segment: String): String {
        val builder = StringBuilder()
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '.' || char == '_' || char == '~'
            ) {
                builder.append(char)
            } else {
                builder.append('%')
                builder.append(HEX[value shr 4].uppercaseChar())
                builder.append(HEX[value and 0x0F].uppercaseChar())
            }
        }
        return builder.toString()
    }

    private fun hmacSha256(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
    }
}
