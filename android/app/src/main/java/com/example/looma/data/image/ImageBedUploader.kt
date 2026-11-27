package com.example.looma.data.image

import com.example.looma.data.s3.S3Client
import com.example.looma.data.s3.S3EndpointConfig
import com.example.looma.data.s3.S3ErrorKind
import com.example.looma.data.s3.S3Exception
import com.example.looma.data.s3.S3PutCondition
import com.example.looma.data.s3.S3RequestSigner
import com.example.looma.data.settings.S3ImageBedConfig
import com.example.looma.domain.sync.RemotePrefix
import io.ktor.client.HttpClient
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

class ImageBedUploader(
    private val httpClient: HttpClient,
    private val compressor: ImageCompressor = NoOpImageCompressor,
) {
    suspend fun testConnection(config: S3ImageBedConfig): Result<Unit> {
        if (!config.isConfigured) {
            return Result.failure(
                S3Exception(
                    kind = S3ErrorKind.InvalidConfig,
                    message = "S3 image bed is not configured",
                ),
            )
        }
        val normalizedPrefix = ImageNaming.normalizePrefix(config.pathPrefix)
            .let { if (it.isEmpty()) "" else "$it/" }
        return try {
            S3Client(httpClient).testConnection(
                config = config.toEndpointConfig(),
                prefix = RemotePrefix(normalizedPrefix),
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: S3Exception) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(ImageUploadException(ImageUploadFailure.Network, e.message, e))
        }
    }

    suspend fun uploadBytes(
        config: S3ImageBedConfig,
        bytes: ByteArray,
        mimeType: String?,
        originalFileName: String? = null,
        noteName: String? = null,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ImageUpload.Remote {
        if (!config.isConfigured) {
            throw ImageUploadException(ImageUploadFailure.NotConfigured)
        }
        if (bytes.isEmpty()) {
            throw ImageUploadException(ImageUploadFailure.UnsupportedImage)
        }
        if (bytes.size > MAX_IMAGE_BYTES) {
            throw ImageUploadException(ImageUploadFailure.ImageTooLarge)
        }

        val detected = ImageFormatSniffer.detect(mimeType, bytes, originalFileName)
            ?: throw ImageUploadException(ImageUploadFailure.UnsupportedImage)

        val finalImage = if (
            (config.compressEnabled || config.convertToWebp) &&
            !(!config.compressEnabled && config.convertToWebp && detected.extension == "webp")
        ) {
            compressor.compress(
                bytes = bytes,
                format = detected,
                quality = config.compressQuality.coerceIn(10, 100),
                convertToWebp = config.convertToWebp,
            )
        } else {
            CompressedImage(bytes, detected.extension, detected.mimeType)
        }

        val sha256Hex = S3RequestSigner.sha256Hex(finalImage.bytes)
        val md5Hex = S3RequestSigner.hex(MessageDigest.getInstance("MD5").digest(finalImage.bytes))
        val namingContext = ImageNamingContext(
            originalFileName = originalFileName,
            extension = finalImage.extension,
            sha256Hex = sha256Hex,
            md5Hex = md5Hex,
            noteName = noteName,
            now = now,
            zoneId = zoneId,
        )
        val baseKey = ImageNaming.buildObjectKey(config.pathPrefix, config.namingTemplate, namingContext)
        val endpointConfig = config.toEndpointConfig()
        val extraHeaders = if (config.publicRead) mapOf("x-amz-acl" to "public-read") else emptyMap()
        val client = S3Client(httpClient)

        val finalKey = uploadWithCollisionHandling(
            client = client,
            endpointConfig = endpointConfig,
            baseKey = baseKey,
            bytes = finalImage.bytes,
            contentType = finalImage.mimeType,
            overwriteExisting = config.overwriteExisting,
            extraHeaders = extraHeaders,
        )

        val url = ImageLinkFormatter.buildPublicUrl(config, finalKey)
        val alt = originalFileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBeforeLast('.')
            ?.trim()
            .orEmpty()
        val snippet = ImageLinkFormatter.formatSnippet(
            url = url,
            alt = alt,
            format = config.linkFormat,
            customTemplate = config.customLinkTemplate,
        )
        return ImageUpload.Remote(
            url = url,
            snippet = snippet,
            objectKey = finalKey,
        )
    }

    private suspend fun uploadWithCollisionHandling(
        client: S3Client,
        endpointConfig: S3EndpointConfig,
        baseKey: String,
        bytes: ByteArray,
        contentType: String,
        overwriteExisting: Boolean,
        extraHeaders: Map<String, String>,
    ): String {
        if (overwriteExisting) {
            try {
                client.uploadBytes(
                    config = endpointConfig,
                    objectKey = baseKey,
                    bytes = bytes,
                    contentType = contentType,
                    condition = S3PutCondition.Unconditional,
                    extraHeaders = extraHeaders,
                )
                return baseKey
            } catch (e: S3Exception) {
                throw e.toImageUploadException()
            }
        }

        for (attempt in 0..MAX_COLLISION_RETRIES) {
            val candidateKey = if (attempt == 0) baseKey else appendSuffixBeforeExtension(baseKey, attempt)
            try {
                client.uploadBytes(
                    config = endpointConfig,
                    objectKey = candidateKey,
                    bytes = bytes,
                    contentType = contentType,
                    condition = S3PutCondition.IfNotExists,
                    extraHeaders = extraHeaders,
                )
                return candidateKey
            } catch (e: S3Exception) {
                if (e.kind == S3ErrorKind.PreconditionFailed && attempt < MAX_COLLISION_RETRIES) {
                    continue
                }
                throw e.toImageUploadException()
            }
        }
        throw ImageUploadException(ImageUploadFailure.Rejected, "Object key already exists after retries")
    }

    private fun appendSuffixBeforeExtension(objectKey: String, suffix: Int): String {
        val lastSlash = objectKey.lastIndexOf('/')
        val dir = if (lastSlash >= 0) objectKey.substring(0, lastSlash + 1) else ""
        val fileName = if (lastSlash >= 0) objectKey.substring(lastSlash + 1) else objectKey
        val lastDot = fileName.lastIndexOf('.')
        val updatedFile = if (lastDot > 0) {
            "${fileName.substring(0, lastDot)}-$suffix${fileName.substring(lastDot)}"
        } else {
            "$fileName-$suffix"
        }
        return "$dir$updatedFile"
    }

    private fun S3ImageBedConfig.toEndpointConfig(): S3EndpointConfig = S3EndpointConfig(
        endpoint = endpoint,
        region = region,
        bucket = bucket,
        accessKey = accessKey,
        secretKey = secretKey,
        pathStyle = pathStyle,
    )

    private fun S3Exception.toImageUploadException(): ImageUploadException {
        val failure = when (kind) {
            S3ErrorKind.InvalidConfig -> ImageUploadFailure.NotConfigured
            S3ErrorKind.Network -> ImageUploadFailure.Network
            else -> ImageUploadFailure.Rejected
        }
        return ImageUploadException(failure, message, this)
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        const val MAX_COLLISION_RETRIES = 20
    }
}
