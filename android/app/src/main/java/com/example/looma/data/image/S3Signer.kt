package com.example.looma.data.image

import com.example.looma.data.s3.S3EndpointConfig
import com.example.looma.data.s3.S3RequestSigner
import com.example.looma.data.settings.S3ImageBedConfig
import java.time.Instant

internal data class SignedS3Put(
    val url: String,
    val headers: Map<String, String>,
)

internal object S3Signer {
    data class Endpoint(
        val scheme: String,
        val host: String,
        val port: Int,
        val basePath: String,
    ) {
        val hostHeader: String get() = if (port > 0) "$host:$port" else host
    }

    fun endpoint(raw: String): Endpoint {
        val parsed = S3RequestSigner.parseEndpoint(raw)
        return Endpoint(
            scheme = parsed.scheme,
            host = parsed.host,
            port = parsed.effectivePort,
            basePath = parsed.basePath,
        )
    }

    fun objectUrl(config: S3ImageBedConfig, key: String): String =
        S3RequestSigner.objectUrl(
            config = config.toEndpointConfig(),
            objectKey = key,
            publicBaseUrl = config.publicBaseUrl,
        )

    fun signPut(
        config: S3ImageBedConfig,
        key: String,
        contentType: String,
        payload: ByteArray,
        now: Instant,
    ): SignedS3Put {
        val headers = buildMap {
            put("Content-Type", contentType)
            if (config.publicRead) put("x-amz-acl", "public-read")
        }
        val signed = S3RequestSigner.sign(
            config = config.toEndpointConfig(),
            method = "PUT",
            objectKey = key,
            headers = headers,
            payloadSha256 = S3RequestSigner.sha256Hex(payload),
            now = now,
        )
        return SignedS3Put(
            url = signed.url,
            headers = signed.headers,
        )
    }

    private fun S3ImageBedConfig.toEndpointConfig(): S3EndpointConfig = S3EndpointConfig(
        endpoint = endpoint,
        region = region,
        bucket = bucket,
        accessKey = accessKey,
        secretKey = secretKey,
        pathStyle = pathStyle,
    )
}
