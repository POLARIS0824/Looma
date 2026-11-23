package com.example.looma.data.settings

data class S3SyncConfig(
    val endpoint: String = "",
    val region: String = "auto",
    val bucket: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val remotePrefix: String = "",
    val pathStyle: Boolean = false,
) {
    val isConfigured: Boolean
        get() = endpoint.isNotBlank() &&
            region.isNotBlank() &&
            bucket.isNotBlank() &&
            accessKey.isNotBlank() &&
            secretKey.isNotBlank()
}
