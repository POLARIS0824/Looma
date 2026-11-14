package com.example.looma.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "looma_settings_preferences")

data class SettingsPreferencesData(
    val dynamicColor: Boolean?,
    val themePreset: String?,
    val keepEmptyNotes: Boolean?,
    val showInlineTitle: Boolean?,
    val toolbarOrderString: String?,
    val imageUploadTarget: String?,
    val s3Endpoint: String?,
    val s3Region: String?,
    val s3Bucket: String?,
    val s3AccessKey: String?,
    val s3SecretKey: String?,
    val s3PathPrefix: String?,
    val s3PublicBaseUrl: String?,
    val s3PathStyle: Boolean?,
    val s3PublicRead: Boolean?,
    val s3CredentialsUnavailable: Boolean = false,
)

class SettingsPreferences(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val settingsDataFlow: Flow<SettingsPreferencesData> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { snapshot ->
                val preferences = migrateS3Credentials(snapshot)
                val encryptedCredentials = preferences[KEY_S3_CREDENTIALS_V1]
                val credentials = encryptedCredentials?.let(S3CredentialCipher::decrypt)
                SettingsPreferencesData(
                    dynamicColor = preferences[KEY_DYNAMIC_COLOR],
                    themePreset = preferences[KEY_THEME_PRESET],
                    keepEmptyNotes = preferences[KEY_KEEP_EMPTY_NOTES],
                    showInlineTitle = preferences[KEY_SHOW_INLINE_TITLE],
                    toolbarOrderString = preferences[KEY_TOOLBAR_ORDER],
                    imageUploadTarget = preferences[KEY_IMAGE_UPLOAD_TARGET],
                    s3Endpoint = preferences[KEY_S3_ENDPOINT],
                    s3Region = preferences[KEY_S3_REGION],
                    s3Bucket = preferences[KEY_S3_BUCKET],
                    s3AccessKey = if (encryptedCredentials == null) preferences[KEY_S3_ACCESS_KEY] else credentials?.first,
                    s3SecretKey = if (encryptedCredentials == null) preferences[KEY_S3_SECRET_KEY] else credentials?.second,
                    s3PathPrefix = preferences[KEY_S3_PATH_PREFIX],
                    s3PublicBaseUrl = preferences[KEY_S3_PUBLIC_BASE_URL],
                    s3PathStyle = preferences[KEY_S3_PATH_STYLE],
                    s3PublicRead = preferences[KEY_S3_PUBLIC_READ],
                    s3CredentialsUnavailable = encryptedCredentials != null && credentials == null,
                )
            }
            .flowOn(Dispatchers.IO)

    private suspend fun migrateS3Credentials(snapshot: Preferences): Preferences {
        if (snapshot[KEY_S3_ACCESS_KEY] == null && snapshot[KEY_S3_SECRET_KEY] == null) return snapshot

        return try {
            dataStore.edit { preferences ->
                val accessKey = preferences[KEY_S3_ACCESS_KEY]
                val secretKey = preferences[KEY_S3_SECRET_KEY]
                if (accessKey != null || secretKey != null) {
                    val encryptedCredentials = preferences[KEY_S3_CREDENTIALS_V1]
                    if (encryptedCredentials == null) {
                        preferences.replaceS3Credentials(accessKey.orEmpty(), secretKey.orEmpty(), replaceInvalidKey = false)
                    } else if (S3CredentialCipher.decrypt(encryptedCredentials) != null) {
                        preferences.remove(KEY_S3_ACCESS_KEY)
                        preferences.remove(KEY_S3_SECRET_KEY)
                    }
                }
            }
        } catch (_: IOException) {
            snapshot
        }
    }

    private fun MutablePreferences.replaceS3Credentials(
        accessKey: String,
        secretKey: String,
        replaceInvalidKey: Boolean,
    ) {
        if (accessKey.isEmpty() && secretKey.isEmpty()) {
            remove(KEY_S3_CREDENTIALS_V1)
        } else {
            this[KEY_S3_CREDENTIALS_V1] = S3CredentialCipher.encrypt(accessKey, secretKey, replaceInvalidKey)
        }
        remove(KEY_S3_ACCESS_KEY)
        remove(KEY_S3_SECRET_KEY)
    }

    suspend fun saveToolbarOrder(orderString: String) {
        dataStore.edit { it[KEY_TOOLBAR_ORDER] = orderString }
    }
    suspend fun saveDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }
    suspend fun saveThemePreset(presetName: String) {
        dataStore.edit { it[KEY_THEME_PRESET] = presetName }
    }
    suspend fun saveKeepEmptyNotes(keep: Boolean) {
        dataStore.edit { it[KEY_KEEP_EMPTY_NOTES] = keep }
    }

    suspend fun saveShowInlineTitle(show: Boolean) {
        dataStore.edit { it[KEY_SHOW_INLINE_TITLE] = show }
    }

    suspend fun saveImageUploadTarget(target: String) {
        dataStore.edit { it[KEY_IMAGE_UPLOAD_TARGET] = target }
    }

    suspend fun saveS3ImageBed(config: S3ImageBedConfig): Unit = withContext(Dispatchers.IO) {
        dataStore.edit {
            it[KEY_S3_ENDPOINT] = config.endpoint
            it[KEY_S3_REGION] = config.region
            it[KEY_S3_BUCKET] = config.bucket
            it.replaceS3Credentials(config.accessKey, config.secretKey, replaceInvalidKey = true)
            it[KEY_S3_PATH_PREFIX] = config.pathPrefix
            it[KEY_S3_PUBLIC_BASE_URL] = config.publicBaseUrl
            it[KEY_S3_PATH_STYLE] = config.pathStyle
            it[KEY_S3_PUBLIC_READ] = config.publicRead
        }
    }

    companion object {
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("key_dynamic_color")
        val KEY_THEME_PRESET = stringPreferencesKey("key_theme_preset")
        val KEY_KEEP_EMPTY_NOTES = booleanPreferencesKey("key_keep_empty_notes")
        val KEY_SHOW_INLINE_TITLE = booleanPreferencesKey("key_show_inline_title")
        val KEY_TOOLBAR_ORDER = stringPreferencesKey("key_toolbar_order")
        val KEY_IMAGE_UPLOAD_TARGET = stringPreferencesKey("key_image_upload_target")
        val KEY_S3_ENDPOINT = stringPreferencesKey("key_s3_endpoint")
        val KEY_S3_REGION = stringPreferencesKey("key_s3_region")
        val KEY_S3_BUCKET = stringPreferencesKey("key_s3_bucket")
        val KEY_S3_ACCESS_KEY = stringPreferencesKey("key_s3_access_key")
        val KEY_S3_SECRET_KEY = stringPreferencesKey("key_s3_secret_key")
        private val KEY_S3_CREDENTIALS_V1 = stringPreferencesKey("key_s3_credentials_v1")
        val KEY_S3_PATH_PREFIX = stringPreferencesKey("key_s3_path_prefix")
        val KEY_S3_PUBLIC_BASE_URL = stringPreferencesKey("key_s3_public_base_url")
        val KEY_S3_PATH_STYLE = booleanPreferencesKey("key_s3_path_style")
        val KEY_S3_PUBLIC_READ = booleanPreferencesKey("key_s3_public_read")
    }
}
