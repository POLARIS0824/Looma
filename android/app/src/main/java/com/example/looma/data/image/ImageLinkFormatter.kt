package com.example.looma.data.image

import com.example.looma.data.s3.S3EndpointConfig
import com.example.looma.data.s3.S3RequestSigner
import com.example.looma.data.settings.ImageLinkFormat
import com.example.looma.data.settings.S3ImageBedConfig

object ImageLinkFormatter {
    fun buildPublicUrl(config: S3ImageBedConfig, objectKey: String): String {
        val cleanKey = objectKey.trimStart('/')
        val formattedKey = if (config.encodeUrl) S3RequestSigner.encodePath(cleanKey) else cleanKey
        val rawPublicBase = config.publicBaseUrl.trim()
        val baseUrl = if (rawPublicBase.isNotEmpty()) {
            val withScheme = if ("://" in rawPublicBase) rawPublicBase else "https://$rawPublicBase"
            val normalizedBase = withScheme.trimEnd('/')
            if (formattedKey.isEmpty()) normalizedBase else "$normalizedBase/$formattedKey"
        } else {
            val endpointConfig = S3EndpointConfig(
                endpoint = config.endpoint,
                region = config.region,
                bucket = config.bucket,
                accessKey = config.accessKey,
                secretKey = config.secretKey,
                pathStyle = config.pathStyle,
            )
            if (config.encodeUrl) {
                S3RequestSigner.objectUrl(endpointConfig, cleanKey, "")
            } else {
                val parsed = S3RequestSigner.parseEndpoint(endpointConfig.endpoint)
                val encodedBase = S3RequestSigner.encodePath(parsed.basePath)
                val bucket = endpointConfig.bucket.trim()
                if (endpointConfig.pathStyle) {
                    "${parsed.scheme}://${parsed.hostHeader}$encodedBase/${S3RequestSigner.encodeSegment(bucket)}/$formattedKey"
                } else {
                    "${parsed.scheme}://${S3RequestSigner.encodeSegment(bucket)}.${parsed.hostHeader}$encodedBase/$formattedKey"
                }
            }
        }
        val suffix = config.urlSuffix.trim()
        return if (suffix.isEmpty()) baseUrl else "$baseUrl$suffix"
    }

    fun formatSnippet(
        url: String,
        alt: String,
        format: ImageLinkFormat,
        customTemplate: String,
    ): String = when (format) {
        ImageLinkFormat.Markdown -> "![${escapeMarkdownAlt(alt)}]($url)"
        ImageLinkFormat.Html -> "<img src=\"${escapeHtmlAttr(url)}\" alt=\"${escapeHtmlAttr(alt)}\" />"
        ImageLinkFormat.PlainUrl -> url
        ImageLinkFormat.Custom -> customTemplate
            .ifBlank { S3ImageBedConfig.DEFAULT_CUSTOM_LINK_TEMPLATE }
            .replace("{url}", url)
            .replace("{alt}", alt)
    }

    private fun escapeMarkdownAlt(alt: String): String = buildString(alt.length) {
        for (ch in alt) {
            when (ch) {
                '\r', '\n' -> append(' ')
                '\\', '[', ']' -> {
                    append('\\')
                    append(ch)
                }
                else -> append(ch)
            }
        }
    }

    private fun escapeHtmlAttr(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\r', '\n' -> append(' ')
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(ch)
            }
        }
    }
}
