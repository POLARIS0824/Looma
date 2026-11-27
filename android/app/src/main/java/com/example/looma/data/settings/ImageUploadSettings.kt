package com.example.looma.data.settings

enum class ImageUploadTarget {
    LocalAttachment,
    ImageBed,
}

enum class ImageLinkFormat {
    Markdown,
    Html,
    PlainUrl,
    Custom,
}

data class S3ImageBedConfig(
    val endpoint: String = "",
    val region: String = "auto",
    val bucket: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val pathPrefix: String = "",
    val publicBaseUrl: String = "",
    val pathStyle: Boolean = true,
    val publicRead: Boolean = false,
    val namingTemplate: String = DEFAULT_NAMING_TEMPLATE,
    val linkFormat: ImageLinkFormat = ImageLinkFormat.Markdown,
    val customLinkTemplate: String = DEFAULT_CUSTOM_LINK_TEMPLATE,
    val urlSuffix: String = "",
    val encodeUrl: Boolean = true,
    val overwriteExisting: Boolean = true,
    val compressEnabled: Boolean = false,
    val compressQuality: Int = DEFAULT_COMPRESS_QUALITY,
    val convertToWebp: Boolean = false,
) {
    val isConfigured: Boolean
        get() = endpoint.isNotBlank() &&
            region.isNotBlank() &&
            bucket.isNotBlank() &&
            accessKey.isNotBlank() &&
            secretKey.isNotBlank()

    companion object {
        const val DEFAULT_NAMING_TEMPLATE = "{sha256}.{extName}"
        const val DEFAULT_CUSTOM_LINK_TEMPLATE = "![{alt}]({url})"
        const val DEFAULT_COMPRESS_QUALITY = 85
    }
}
