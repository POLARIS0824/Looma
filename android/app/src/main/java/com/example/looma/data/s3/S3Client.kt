package com.example.looma.data.s3

import com.example.looma.domain.sync.RemotePrefix
import com.example.looma.domain.sync.SyncFileFilter
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.DEFAULT_PORT
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.content.OutgoingContent
import io.ktor.http.encodedPath
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class S3ErrorKind {
    InvalidConfig,
    AuthFailed,
    BucketNotFound,
    ClockSkew,
    NotFound,
    PreconditionFailed,
    RateLimited,
    ServerError,
    ClientError,
    Network,
    InvalidResponse,
    FileTooLarge,
}

internal class S3Exception(
    val kind: S3ErrorKind,
    val statusCode: Int? = null,
    val s3Code: String? = null,
    override val message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal sealed interface S3PutCondition {
    data object Unconditional : S3PutCondition
    data object IfNotExists : S3PutCondition
    data class IfMatch(val etag: String) : S3PutCondition
}

internal data class S3ObjectHead(
    val objectKey: String,
    val etag: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

internal data class S3PutResult(
    val objectKey: String,
    val etag: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal data class S3DownloadResult(
    val objectKey: String,
    val etag: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val sha256: String,
)

internal class S3Client(
    private val httpClient: HttpClient,
) {
    suspend fun testConnection(
        config: S3EndpointConfig,
        prefix: RemotePrefix,
    ) = withContext(Dispatchers.IO) {
        val query = buildMap {
            put("list-type", "2")
            put("max-keys", "1")
            if (prefix.value.isNotEmpty()) put("prefix", prefix.value)
        }
        val signed = signOrThrow {
            S3RequestSigner.sign(
                config = config,
                method = "GET",
                objectKey = null,
                queryParameters = query,
                payloadSha256 = S3RequestSigner.EMPTY_PAYLOAD_SHA256,
            )
        }
        executeStatement(signed) { response ->
            if (response.status.value !in 200..299) {
                throw mapErrorResponse(response)
            }
            val body = readBoundedText(response, MAX_XML_RESPONSE_BYTES)
            try {
                S3XmlParser.parseListObjectsV2(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw S3Exception(
                    kind = S3ErrorKind.InvalidResponse,
                    statusCode = response.status.value,
                    message = e.message ?: "Invalid S3 list response",
                    cause = e,
                )
            }
        }
    }

    suspend fun listAllObjects(
        config: S3EndpointConfig,
        prefix: RemotePrefix,
    ): List<S3ObjectSummary> = withContext(Dispatchers.IO) {
        val objects = mutableListOf<S3ObjectSummary>()
        var continuationToken: String? = null
        val seenTokens = HashSet<String>()

        while (true) {
            coroutineContext.ensureActive()
            val query = buildMap {
                put("list-type", "2")
                put("max-keys", "1000")
                if (prefix.value.isNotEmpty()) put("prefix", prefix.value)
                val token = continuationToken
                if (token != null) put("continuation-token", token)
            }
            val signed = signOrThrow {
                S3RequestSigner.sign(
                    config = config,
                    method = "GET",
                    objectKey = null,
                    queryParameters = query,
                    payloadSha256 = S3RequestSigner.EMPTY_PAYLOAD_SHA256,
                )
            }
            val page = executeStatement(signed) { response ->
                if (response.status.value !in 200..299) {
                    throw mapErrorResponse(response)
                }
                val body = readBoundedText(response, MAX_XML_RESPONSE_BYTES)
                try {
                    S3XmlParser.parseListObjectsV2(body)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw S3Exception(
                        kind = S3ErrorKind.InvalidResponse,
                        statusCode = response.status.value,
                        message = e.message ?: "Invalid S3 list XML",
                        cause = e,
                    )
                }
            }
            objects += page.objects
            if (!page.isTruncated) break
            val next = page.nextContinuationToken
            if (next.isNullOrEmpty() || !seenTokens.add(next)) {
                throw S3Exception(
                    kind = S3ErrorKind.InvalidResponse,
                    message = "Truncated S3 list response missing valid continuation token",
                )
            }
            continuationToken = next
        }
        objects
    }

    suspend fun headObject(
        config: S3EndpointConfig,
        objectKey: String,
    ): S3ObjectHead? = withContext(Dispatchers.IO) {
        val signed = signOrThrow {
            S3RequestSigner.sign(
                config = config,
                method = "HEAD",
                objectKey = objectKey,
                payloadSha256 = S3RequestSigner.EMPTY_PAYLOAD_SHA256,
            )
        }
        executeStatement(signed) { response ->
            when {
                response.status == HttpStatusCode.NotFound -> null
                response.status.value in 200..299 -> S3ObjectHead(
                    objectKey = objectKey,
                    etag = response.headers[HttpHeaders.ETag]?.trim()?.trim('"').orEmpty(),
                    sizeBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L,
                    lastModifiedMillis = parseHttpDateMillis(response.headers[HttpHeaders.LastModified]),
                )
                else -> throw mapErrorResponse(response)
            }
        }
    }

    suspend fun downloadToFile(
        config: S3EndpointConfig,
        objectKey: String,
        destinationFile: File,
        maxBytes: Long = SyncFileFilter.MAX_SYNC_FILE_BYTES,
        expectedEtag: String? = null,
    ): S3DownloadResult = withContext(Dispatchers.IO) {
        val requestHeaders = expectedEtag
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }
            ?.let { clean ->
                val value = if (clean == "*") "*" else "\"$clean\""
                mapOf("If-Match" to value)
            }
            .orEmpty()
        val signed = signOrThrow {
            S3RequestSigner.sign(
                config = config,
                method = "GET",
                objectKey = objectKey,
                headers = requestHeaders,
                payloadSha256 = S3RequestSigner.EMPTY_PAYLOAD_SHA256,
            )
        }
        executeStatement(
            signed = signed,
            configure = { header(HttpHeaders.AcceptEncoding, "identity") },
        ) { response ->
            if (response.status.value !in 200..299) {
                throw mapErrorResponse(response)
            }
            val etag = response.headers[HttpHeaders.ETag]?.trim()?.trim('"').orEmpty()
            val expectedLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val lastModifiedMillis = parseHttpDateMillis(response.headers[HttpHeaders.LastModified])
            if (expectedLength != null && expectedLength > maxBytes) {
                throw S3Exception(
                    kind = S3ErrorKind.FileTooLarge,
                    statusCode = response.status.value,
                    message = "Remote object exceeds max size ($maxBytes bytes)",
                )
            }

            val destinationPath = destinationFile.absoluteFile.toPath()
            val parentDir = checkNotNull(destinationPath.parent) { "Destination file has no parent" }
            java.nio.file.Files.createDirectories(parentDir)
            val tempPath = java.nio.file.Files.createTempFile(parentDir, ".looma-dl-", ".tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesWritten = 0L

            try {
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                FileOutputStream(tempPath.toFile()).use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        bytesWritten += count
                        if (bytesWritten > maxBytes) {
                            throw S3Exception(
                                kind = S3ErrorKind.FileTooLarge,
                                message = "Remote object exceeds max size ($maxBytes bytes)",
                            )
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    output.fd.sync()
                }
                if (expectedLength != null && bytesWritten != expectedLength) {
                    throw S3Exception(
                        kind = S3ErrorKind.InvalidResponse,
                        message = "Incomplete download: expected $expectedLength bytes, received $bytesWritten",
                    )
                }
                coroutineContext.ensureActive()
                try {
                    java.nio.file.Files.move(
                        tempPath,
                        destinationPath,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(
                        tempPath,
                        destinationPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                S3DownloadResult(
                    objectKey = objectKey,
                    etag = etag,
                    sizeBytes = bytesWritten,
                    lastModifiedMillis = lastModifiedMillis,
                    sha256 = S3RequestSigner.hex(digest.digest()),
                )
            } catch (t: Throwable) {
                runCatching { java.nio.file.Files.deleteIfExists(tempPath) }
                throw t
            }
        }
    }

    suspend fun uploadFile(
        config: S3EndpointConfig,
        objectKey: String,
        file: File,
        contentType: String,
        condition: S3PutCondition = S3PutCondition.Unconditional,
        maxBytes: Long = SyncFileFilter.MAX_SYNC_FILE_BYTES,
        mtimeMillis: Long? = null,
    ): S3PutResult = withContext(Dispatchers.IO) {
        if (!file.isFile) {
            throw S3Exception(
                kind = S3ErrorKind.InvalidConfig,
                message = "Upload source is not a file: ${file.name}",
            )
        }
        if (file.length() > maxBytes) {
            throw S3Exception(
                kind = S3ErrorKind.FileTooLarge,
                message = "File exceeds max sync size ($maxBytes bytes)",
            )
        }
        val (measuredLength, sha256) = hashFileSha256(file, maxBytes)
        val extraHeaders = if (mtimeMillis != null && mtimeMillis > 0L) {
            mapOf("x-amz-meta-mtime" to String.format(Locale.US, "%.3f", mtimeMillis / 1000.0))
        } else {
            emptyMap()
        }
        val requestHeaders = buildPutHeaders(contentType, condition, extraHeaders)
        val signed = signOrThrow {
            S3RequestSigner.sign(
                config = config,
                method = "PUT",
                objectKey = objectKey,
                headers = requestHeaders,
                payloadSha256 = sha256,
            )
        }
        val putResult = executeStatement(
            signed = signed,
            configure = {
                setBody(
                    object : OutgoingContent.WriteChannelContent() {
                        override val contentLength: Long = measuredLength

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            withContext(Dispatchers.IO) {
                                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                                var remaining = measuredLength
                                FileInputStream(file).use { input ->
                                    while (remaining > 0L) {
                                        coroutineContext.ensureActive()
                                        val maxRead = minOf(buffer.size.toLong(), remaining).toInt()
                                        val count = input.read(buffer, 0, maxRead)
                                        if (count < 0) {
                                            throw IOException("File shrank during upload")
                                        }
                                        if (count > 0) {
                                            channel.writeFully(buffer, startIndex = 0, endIndex = count)
                                            remaining -= count
                                        }
                                    }
                                    if (input.read() >= 0) {
                                        throw IOException("File grew during upload")
                                    }
                                }
                            }
                        }
                    },
                )
            },
        ) { response ->
            if (response.status.value !in 200..299) {
                throw mapErrorResponse(response)
            }
            val etag = response.headers[HttpHeaders.ETag]?.trim()?.trim('"').orEmpty()
            S3PutResult(
                objectKey = objectKey,
                etag = etag,
                sizeBytes = measuredLength,
                sha256 = sha256,
            )
        }
        if (putResult.etag.isNotBlank()) {
            putResult
        } else {
            putResult.copy(etag = headObject(config, objectKey)?.etag.orEmpty())
        }
    }

    suspend fun uploadBytes(
        config: S3EndpointConfig,
        objectKey: String,
        bytes: ByteArray,
        contentType: String,
        condition: S3PutCondition = S3PutCondition.Unconditional,
        extraHeaders: Map<String, String> = emptyMap(),
    ): S3PutResult = withContext(Dispatchers.IO) {
        val payloadSha256 = S3RequestSigner.sha256Hex(bytes)
        val requestHeaders = buildPutHeaders(contentType, condition, extraHeaders)
        val signed = signOrThrow {
            S3RequestSigner.sign(
                config = config,
                method = "PUT",
                objectKey = objectKey,
                headers = requestHeaders,
                payloadSha256 = payloadSha256,
            )
        }
        executeStatement(
            signed = signed,
            configure = {
                setBody(
                    object : OutgoingContent.ByteArrayContent() {
                        override val contentLength: Long = bytes.size.toLong()
                        override fun bytes(): ByteArray = bytes
                    },
                )
            },
        ) { response ->
            if (response.status.value !in 200..299) {
                throw mapErrorResponse(response)
            }
            val etag = response.headers[HttpHeaders.ETag]?.trim()?.trim('"').orEmpty()
            S3PutResult(
                objectKey = objectKey,
                etag = etag,
                sizeBytes = bytes.size.toLong(),
                sha256 = payloadSha256,
            )
        }
    }

    suspend fun deleteObject(
        config: S3EndpointConfig,
        objectKey: String,
        expectedEtag: String? = null,
    ) = withContext(Dispatchers.IO) {
        if (expectedEtag != null) {
            val normalizedExpected = expectedEtag.trim().trim('"')
            val current = headObject(config, objectKey) ?: return@withContext
            if (!current.etag.equals(normalizedExpected, ignoreCase = true)) {
                throw S3Exception(
                    kind = S3ErrorKind.PreconditionFailed,
                    statusCode = HttpStatusCode.PreconditionFailed.value,
                    s3Code = "PreconditionFailed",
                    message = "Remote object ETag changed before delete",
                )
            }
        }
        val signed = signOrThrow {
            S3RequestSigner.sign(
                config = config,
                method = "DELETE",
                objectKey = objectKey,
                payloadSha256 = S3RequestSigner.EMPTY_PAYLOAD_SHA256,
            )
        }
        executeStatement(signed) { response ->
            if (response.status == HttpStatusCode.NotFound || response.status.value in 200..299) {
                return@executeStatement
            }
            throw mapErrorResponse(response)
        }
    }

    private fun buildPutHeaders(
        contentType: String,
        condition: S3PutCondition,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildMap {
        putAll(extraHeaders)
        put("Content-Type", contentType)
        when (condition) {
            S3PutCondition.Unconditional -> Unit
            S3PutCondition.IfNotExists -> put("If-None-Match", "*")
            is S3PutCondition.IfMatch -> {
                val clean = condition.etag.trim().trim('"')
                val headerValue = if (clean == "*") "*" else "\"$clean\""
                put("If-Match", headerValue)
            }
        }
    }

    private inline fun <T> signOrThrow(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw S3Exception(
            kind = S3ErrorKind.InvalidConfig,
            message = e.message ?: "Invalid S3 configuration",
            cause = e,
        )
    }

    private suspend fun <T> executeStatement(
        signed: SignedS3Request,
        configure: HttpRequestBuilder.() -> Unit = {},
        block: suspend (HttpResponse) -> T,
    ): T {
        try {
            return httpClient.prepareRequest {
                method = HttpMethod.parse(signed.method)
                url {
                    protocol = URLProtocol.createOrDefault(signed.scheme)
                    host = signed.host
                    port = if (signed.effectivePort > 0) signed.effectivePort else DEFAULT_PORT
                    encodedPath = signed.canonicalUri
                    signed.encodedQueryParameters.forEach { (key, value) ->
                        encodedParameters.append(key, value)
                    }
                }
                signed.headers.forEach { (name, value) ->
                    header(name, value)
                }
                configure()
            }.execute(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: S3Exception) {
            throw e
        } catch (e: IOException) {
            throw S3Exception(
                kind = S3ErrorKind.Network,
                message = e.message ?: "Network error",
                cause = e,
            )
        }
    }

    private suspend fun mapErrorResponse(response: HttpResponse): S3Exception {
        val status = response.status.value
        val bodyText = if (response.call.request.method == HttpMethod.Head) {
            ""
        } else {
            runCatching { readBoundedText(response, MAX_ERROR_BODY_BYTES) }.getOrDefault("")
        }
        val parsed = S3XmlParser.parseError(bodyText)
        val s3Code = parsed?.code
        val detail = parsed?.message ?: bodyText.trim().take(300)
        val kind = when {
            s3Code == "NoSuchBucket" -> S3ErrorKind.BucketNotFound
            s3Code == "RequestTimeTooSkewed" -> S3ErrorKind.ClockSkew
            status == 401 || status == 403 || s3Code in AUTH_ERROR_CODES -> S3ErrorKind.AuthFailed
            status == 404 || s3Code in NOT_FOUND_ERROR_CODES -> S3ErrorKind.NotFound
            status == 409 || status == 412 || s3Code in PRECONDITION_ERROR_CODES -> S3ErrorKind.PreconditionFailed
            status == 413 || s3Code == "EntityTooLarge" -> S3ErrorKind.FileTooLarge
            status == 429 || s3Code == "SlowDown" -> S3ErrorKind.RateLimited
            status in 500..599 -> S3ErrorKind.ServerError
            s3Code in CONFIG_ERROR_CODES -> S3ErrorKind.InvalidConfig
            else -> S3ErrorKind.ClientError
        }
        val message = listOfNotNull(
            "HTTP $status",
            s3Code,
            detail.takeIf { it.isNotEmpty() },
        ).joinToString(": ")
        return S3Exception(
            kind = kind,
            statusCode = status,
            s3Code = s3Code,
            message = message,
        )
    }

    private suspend fun readBoundedText(response: HttpResponse, maxBytes: Int): String {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8 * 1024)
        val output = ByteArrayOutputStream()
        var total = 0
        while (total < maxBytes) {
            coroutineContext.ensureActive()
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (count == 0) continue
            val allowed = minOf(count, maxBytes - total)
            output.write(buffer, 0, allowed)
            total += allowed
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private suspend fun hashFileSha256(file: File, maxBytes: Long): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    total += count
                    if (total > maxBytes) {
                        throw S3Exception(
                            kind = S3ErrorKind.FileTooLarge,
                            message = "File exceeds max sync size ($maxBytes bytes)",
                        )
                    }
                    digest.update(buffer, 0, count)
                }
            }
        }
        return total to S3RequestSigner.hex(digest.digest())
    }

    private fun parseHttpDateMillis(raw: String?): Long {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return 0L
        return runCatching {
            ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    private companion object {
        const val STREAM_BUFFER_SIZE = 64 * 1024
        const val MAX_ERROR_BODY_BYTES = 64 * 1024
        const val MAX_XML_RESPONSE_BYTES = 8 * 1024 * 1024

        val AUTH_ERROR_CODES = setOf(
            "InvalidAccessKeyId",
            "SignatureDoesNotMatch",
            "AccessDenied",
            "ExpiredToken",
            "InvalidToken",
            "AccountProblem",
            "AllAccessDisabled",
        )
        val NOT_FOUND_ERROR_CODES = setOf("NoSuchKey", "NotFound")
        val PRECONDITION_ERROR_CODES = setOf("PreconditionFailed", "ConditionalRequestConflict")
        val CONFIG_ERROR_CODES = setOf("PermanentRedirect", "AuthorizationHeaderMalformed", "InvalidRegion")
    }
}
