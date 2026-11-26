package com.example.looma.feature.models

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.ApiModel
import com.example.looma.data.assistant.ApiProvider
import com.example.looma.data.assistant.ConnectionStatus
import com.example.looma.data.assistant.ModelSettingsRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
internal data class ApiConnectionEditUiState(
    val saved: Boolean,
    val provider: ApiProvider,
    val models: List<ApiModel>,
    val status: ConnectionStatus,
)

internal class ApiConnectionEditViewModel(
    private val repository: ModelSettingsRepository,
    connectionId: String?,
) : ViewModel() {
    private val initial = repository.connections.value.firstOrNull { it.id == connectionId } ?: ApiConnection.draft()
    private val id = initial.id

    val name = TextFieldState(initial.name)
    val baseUrl = TextFieldState(initial.baseUrl)
    val apiKey = TextFieldState(initial.apiKey)

    private val mutable = MutableStateFlow(
        ApiConnectionEditUiState(
            saved = connectionId != null && initial.id == connectionId,
            provider = initial.provider,
            models = initial.models,
            status = initial.status,
        ),
    )
    val uiState: StateFlow<ApiConnectionEditUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            repository.connections
                .map { list -> list.firstOrNull { it.id == id } }
                .distinctUntilChanged()
                .collect { stored ->
                    if (stored == null) return@collect
                    mutable.update { current ->
                        val toggles = current.models.associate { it.id to it.enabled }
                        current.copy(
                            saved = true,
                            status = stored.status,
                            models = stored.models.map { model -> toggles[model.id]?.let { model.copy(enabled = it) } ?: model },
                        )
                    }
                }
        }
    }

    fun selectProvider(provider: ApiProvider) = mutable.update { it.copy(provider = provider) }

    fun setModelEnabled(modelId: String, enabled: Boolean) = mutable.update { current ->
        current.copy(models = current.models.map { if (it.id == modelId) it.copy(enabled = enabled) else it })
    }

    fun canSave(): Boolean {
        val provider = mutable.value.provider
        return name.text.isNotBlank() && (provider != ApiProvider.Custom || baseUrl.text.isNotBlank())
    }

    fun save() = repository.saveConnection(draft())

    fun test() {
        repository.saveConnection(draft())
        repository.testConnection(id)
    }

    fun fetchModels() {
        repository.saveConnection(draft())
        repository.fetchModels(id)
    }

    fun duplicate(copyName: String): String {
        val copy = draft().copy(id = UUID.randomUUID().toString(), name = copyName, status = ConnectionStatus.Unchecked)
        repository.saveConnection(copy)
        return copy.id
    }

    fun delete() = repository.deleteConnection(id)

    private fun draft(): ApiConnection {
        val current = mutable.value
        return ApiConnection(
            id = id,
            name = name.text.toString().trim(),
            provider = current.provider,
            baseUrl = baseUrl.text.toString().trim().ifEmpty { current.provider.defaultBaseUrl },
            apiKey = apiKey.text.toString(),
            status = current.status,
            models = current.models,
        )
    }
}
