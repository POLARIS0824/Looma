package com.example.looma.data.assistant.api

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.ApiModel
import com.example.looma.data.assistant.ApiProvider
import com.example.looma.data.assistant.ConnectionStatus
import com.example.looma.data.settings.S3CredentialCipher
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private val Context.apiConnectionsDataStore: DataStore<Preferences> by preferencesDataStore(name = "looma_api_connections")

internal data class StoredApiState(
    val connections: List<ApiConnection> = emptyList(),
    val defaultModelId: String? = null,
)

internal class ApiConnectionStore(context: Context) {
    private val dataStore = context.applicationContext.apiConnectionsDataStore

    val state: Flow<StoredApiState> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val apiKeys = decodeApiKeys(preferences[KEY_API_KEYS_V1])
            val connections = decodeConnections(preferences[KEY_CONNECTIONS_JSON], apiKeys)
            StoredApiState(
                connections = connections,
                defaultModelId = preferences[KEY_DEFAULT_MODEL_ID],
            )
        }
        .flowOn(Dispatchers.IO)

    suspend fun saveConnections(connections: List<ApiConnection>): Unit = withContext(Dispatchers.IO) {
        dataStore.edit { preferences ->
            preferences[KEY_CONNECTIONS_JSON] = encodeConnections(connections)
            val keyObject = JSONObject()
            connections.forEach { connection ->
                if (connection.apiKey.isNotBlank()) {
                    keyObject.put(connection.id, connection.apiKey)
                }
            }
            if (keyObject.length() == 0) {
                preferences.remove(KEY_API_KEYS_V1)
            } else {
                preferences[KEY_API_KEYS_V1] = S3CredentialCipher.encryptText(
                    plaintext = keyObject.toString(),
                    replaceInvalidKey = true,
                    keyAlias = S3CredentialCipher.API_KEYS_ALIAS,
                )
            }
        }
    }

    suspend fun saveDefaultModelId(modelId: String?): Unit = withContext(Dispatchers.IO) {
        dataStore.edit { preferences ->
            if (modelId == null) {
                preferences.remove(KEY_DEFAULT_MODEL_ID)
            } else {
                preferences[KEY_DEFAULT_MODEL_ID] = modelId
            }
        }
    }

    private fun encodeConnections(connections: List<ApiConnection>): String {
        val array = JSONArray()
        connections.forEach { connection ->
            val models = JSONArray()
            connection.models.forEach { model ->
                models.put(
                    JSONObject()
                        .put("id", model.id)
                        .put("enabled", model.enabled),
                )
            }
            array.put(
                JSONObject()
                    .put("id", connection.id)
                    .put("name", connection.name)
                    .put("provider", connection.provider.name)
                    .put("baseUrl", connection.baseUrl)
                    .put("status", encodeStatus(connection.status))
                    .put("models", models),
            )
        }
        return array.toString()
    }

    private fun decodeConnections(raw: String?, apiKeys: Map<String, String>): List<ApiConnection> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    val providerName = item.optString("provider")
                    val provider = ApiProvider.entries.firstOrNull { it.name == providerName }
                        ?: ApiProvider.OpenAiCompatible
                    val modelsArray = item.optJSONArray("models")
                    val models = if (modelsArray == null) {
                        emptyList()
                    } else {
                        buildList(modelsArray.length()) {
                            for (j in 0 until modelsArray.length()) {
                                val modelObj = modelsArray.optJSONObject(j) ?: continue
                                val modelId = modelObj.optString("id")
                                if (modelId.isNotBlank()) {
                                    add(ApiModel(id = modelId, enabled = modelObj.optBoolean("enabled", false)))
                                }
                            }
                        }
                    }
                    add(
                        ApiConnection(
                            id = id,
                            name = item.optString("name"),
                            provider = provider,
                            baseUrl = item.optString("baseUrl"),
                            apiKey = apiKeys[id].orEmpty(),
                            status = decodeStatus(item.optString("status")),
                            models = models,
                        ),
                    )
                }
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun decodeApiKeys(encoded: String?): Map<String, String> {
        if (encoded.isNullOrBlank()) return emptyMap()
        val plaintext = S3CredentialCipher.decryptText(encoded, S3CredentialCipher.API_KEYS_ALIAS)
            ?: return emptyMap()
        return try {
            val obj = JSONObject(plaintext)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, obj.optString(key))
                }
            }
        } catch (_: JSONException) {
            emptyMap()
        }
    }

    private fun encodeStatus(status: ConnectionStatus): String = when (status) {
        ConnectionStatus.Ok -> "ok"
        ConnectionStatus.Failed -> "failed"
        ConnectionStatus.Unchecked, ConnectionStatus.Checking -> "unchecked"
    }

    private fun decodeStatus(raw: String): ConnectionStatus = when (raw) {
        "ok" -> ConnectionStatus.Ok
        "failed" -> ConnectionStatus.Failed
        else -> ConnectionStatus.Unchecked
    }

    private companion object {
        val KEY_CONNECTIONS_JSON = stringPreferencesKey("key_connections_json")
        val KEY_API_KEYS_V1 = stringPreferencesKey("key_api_keys_v1")
        val KEY_DEFAULT_MODEL_ID = stringPreferencesKey("key_default_model_id")
    }
}
