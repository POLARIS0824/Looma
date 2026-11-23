package com.example.looma.data.s3

import com.example.looma.domain.sync.RemotePrefix
import java.util.Locale

internal data class S3EndpointConfig(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val pathStyle: Boolean,
) {
    val isConfigured: Boolean
        get() = endpoint.isNotBlank() &&
            region.isNotBlank() &&
            bucket.isNotBlank() &&
            accessKey.isNotBlank() &&
            secretKey.isNotBlank()

    fun targetKey(prefix: RemotePrefix): String {
        val normalizedEndpoint = runCatching {
            val parsed = S3RequestSigner.parseEndpoint(endpoint)
            val portPart = if (parsed.effectivePort > 0) ":${parsed.effectivePort}" else ""
            "${parsed.scheme.lowercase(Locale.ROOT)}://${parsed.host.lowercase(Locale.ROOT)}$portPart${parsed.basePath}"
        }.getOrElse {
            endpoint.trim().trimEnd('/').lowercase(Locale.ROOT)
        }
        val canonical = "$normalizedEndpoint\n${bucket.trim()}\n${prefix.value}"
        return S3RequestSigner.sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }
}
