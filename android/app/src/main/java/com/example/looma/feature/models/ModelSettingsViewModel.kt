package com.example.looma.feature.models

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.LocalImport
import com.example.looma.data.assistant.LocalModelEntry
import com.example.looma.data.assistant.ModelSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Immutable
internal data class ModelSettingsUiState(
    val localModels: List<LocalModelEntry> = emptyList(),
    val connections: List<ApiConnection> = emptyList(),
    val defaultModelId: String? = null,
    val localImport: LocalImport = LocalImport.Idle,
)

internal class ModelSettingsViewModel(private val repository: ModelSettingsRepository) : ViewModel() {
    val uiState: StateFlow<ModelSettingsUiState> = combine(
        repository.localModels,
        repository.connections,
        repository.defaultModelId,
        repository.localImport,
        ::ModelSettingsUiState,
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ModelSettingsUiState(
            repository.localModels.value,
            repository.connections.value,
            repository.defaultModelId.value,
            repository.localImport.value,
        ),
    )

    fun setDefault(modelId: String) = repository.setDefault(modelId)

    fun download(id: String) = repository.download(id)

    fun cancelDownload(id: String) = repository.cancelDownload(id)

    fun importLocal(uri: Uri) = repository.importLocal(uri)
}
