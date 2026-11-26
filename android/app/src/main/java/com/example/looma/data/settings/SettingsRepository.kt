package com.example.looma.data.settings

import android.content.Context
import com.example.looma.data.assistant.ModelChoice
import com.example.looma.domain.markdown.ToolbarItem
import com.example.looma.domain.markdown.ToolbarOrder
import com.example.looma.embedding.EmbeddingBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val preferences: SettingsPreferences,
) {

    constructor(context: Context) : this(
        SettingsPreferences(context.applicationContext)
    )

    val settings: Flow<AppSettings> =
        preferences.settingsDataFlow.map { data ->

            AppSettings(
                dynamicColor = data.dynamicColor ?: true,
                themePreset = data.themePreset
                    ?.let { stored -> ThemePreset.entries.firstOrNull { it.name == stored } }
                    ?: ThemePreset.Default,
                keepEmptyNotes = data.keepEmptyNotes ?: false,
                showInlineTitle = data.showInlineTitle ?: true,
                toolbarOrder = ToolbarOrder.restore(data.toolbarOrderString),
                imageUploadTarget = data.imageUploadTarget
                    ?.let { stored -> ImageUploadTarget.entries.firstOrNull { it.name == stored } }
                    ?: ImageUploadTarget.LocalAttachment,
                imageBed = S3ImageBedConfig(
                    endpoint = data.s3Endpoint.orEmpty(),
                    region = data.s3Region.orEmpty(),
                    bucket = data.s3Bucket.orEmpty(),
                    accessKey = data.s3AccessKey.orEmpty(),
                    secretKey = data.s3SecretKey.orEmpty(),
                    pathPrefix = data.s3PathPrefix.orEmpty(),
                    publicBaseUrl = data.s3PublicBaseUrl.orEmpty(),
                    pathStyle = data.s3PathStyle ?: true,
                    publicRead = data.s3PublicRead ?: true,
                ),
                embeddingBackend = data.embeddingBackend
                    ?.let { stored -> EmbeddingBackend.entries.firstOrNull { it.name == stored } }
                    ?: EmbeddingBackend.CPU,
                isLoaded = true,
                s3CredentialsUnavailable = data.s3CredentialsUnavailable,
                syncConfig = S3SyncConfig(
                    endpoint = data.syncEndpoint.orEmpty(),
                    region = data.syncRegion ?: "auto",
                    bucket = data.syncBucket.orEmpty(),
                    accessKey = data.syncAccessKey.orEmpty(),
                    secretKey = data.syncSecretKey.orEmpty(),
                    remotePrefix = data.syncRemotePrefix.orEmpty(),
                    pathStyle = data.syncPathStyle ?: false,
                ),
                syncCredentialsUnavailable = data.syncCredentialsUnavailable,
                askModelId = data.askModelId,
                askEffort = data.askEffort,
                askRoute = data.askRoute,
            )
        }

    suspend fun setToolbarOrder(order: List<ToolbarItem>) {
        preferences.saveToolbarOrder(ToolbarOrder.encode(order))
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        preferences.saveDynamicColor(enabled)
    }

    suspend fun setThemePreset(preset: ThemePreset) {
        preferences.saveThemePreset(preset.name)
    }

    suspend fun setKeepEmptyNotes(keep: Boolean) {
        preferences.saveKeepEmptyNotes(keep)
    }

    suspend fun setShowInlineTitle(show: Boolean) {
        preferences.saveShowInlineTitle(show)
    }

    suspend fun setImageUploadTarget(target: ImageUploadTarget) {
        preferences.saveImageUploadTarget(target.name)
    }

    suspend fun setS3ImageBed(config: S3ImageBedConfig) {
        preferences.saveS3ImageBed(config)
    }

    suspend fun setSyncConfig(config: S3SyncConfig) {
        preferences.saveSyncConfig(config)
    }

    suspend fun setEmbeddingBackend(backend: EmbeddingBackend) {
        preferences.saveEmbeddingBackend(backend.name)
    }

    internal suspend fun setAskModel(choice: ModelChoice) {
        preferences.saveAskModel(choice.model.id, choice.effort.name, choice.route?.name)
    }
}
