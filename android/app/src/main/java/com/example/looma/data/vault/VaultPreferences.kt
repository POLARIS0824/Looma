package com.example.looma.data.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.io.File
import java.util.UUID

private val Context.vaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "looma_vault_settings")

data class VaultPreferencesData(
    val id: String?,
    val name: String?,
    val path: String?,
)

class VaultPreferences(context: Context) {

    private val dataStore = context.applicationContext.vaultDataStore

    val vaultDataFlow: Flow<VaultPreferencesData> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                VaultPreferencesData(
                    id = preferences[KEY_VAULT_ID],
                    name = preferences[KEY_VAULT_NAME],
                    path = preferences[KEY_VAULT_PATH],
                )
            }

    suspend fun saveVault(
        id: String,
        name: String,
        path: String,
    ) {
        dataStore.edit { preferences ->
            preferences.rememberActiveVault()
            preferences[vaultIdKey(canonicalPath(path))] = id
            preferences.setActiveVault(id, name, path)
        }
    }

    internal suspend fun activateVault(name: String, canonicalPath: String): String {
        val key = vaultIdKey(canonicalPath)
        val updated = dataStore.edit { preferences ->
            preferences.rememberActiveVault()
            val id = preferences[key] ?: UUID.randomUUID().toString()
            preferences[key] = id
            preferences.setActiveVault(id, name, canonicalPath)
        }
        return checkNotNull(updated[KEY_VAULT_ID])
    }

    suspend fun clearVault() {
        dataStore.edit { preferences ->
            preferences.rememberActiveVault()
            preferences.remove(KEY_VAULT_ID)
            preferences.remove(KEY_VAULT_NAME)
            preferences.remove(KEY_VAULT_PATH)
        }
    }

    private fun MutablePreferences.rememberActiveVault() {
        val id = this[KEY_VAULT_ID]?.takeIf { it.isNotBlank() } ?: return
        val path = this[KEY_VAULT_PATH]?.takeIf { it.isNotBlank() } ?: return
        val key = vaultIdKey(canonicalPath(path))
        if (this[key] == null) this[key] = id
    }

    private fun MutablePreferences.setActiveVault(id: String, name: String, path: String) {
        this[KEY_VAULT_ID] = id
        this[KEY_VAULT_NAME] = name
        this[KEY_VAULT_PATH] = path
    }

    private fun canonicalPath(path: String): String =
        try {
            File(path).canonicalPath
        } catch (_: IOException) {
            path
        }

    companion object {
        private fun vaultIdKey(path: String) = stringPreferencesKey("vault_id_for:$path")
        private val KEY_VAULT_ID = stringPreferencesKey("key_vault_id")
        private val KEY_VAULT_NAME = stringPreferencesKey("key_vault_name")
        private val KEY_VAULT_PATH = stringPreferencesKey("key_vault_path")
    }
}
