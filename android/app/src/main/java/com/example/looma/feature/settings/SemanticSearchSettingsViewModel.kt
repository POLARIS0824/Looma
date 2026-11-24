package com.example.looma.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.search.EmbeddingModelCatalog
import com.example.looma.data.search.SemanticSearchRepository
import com.example.looma.data.search.SemanticWorkState
import com.example.looma.embedding.EmbeddingBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class SemanticSearchSettingsViewModel(
    private val repository: SemanticSearchRepository,
) : ViewModel() {
    val supported = repository.supported
    val npuSupported = repository.npuSupported
    val backend = repository.embeddingBackend.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        EmbeddingBackend.CPU,
    )
    val modelSizeBytes = repository.modelSizeBytes.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        EmbeddingModelCatalog.CPU_MODEL.bytes,
    )
    val model = SemanticSettingsOperation(
        viewModelScope,
        repository.modelState,
        enqueue = repository::enqueueModelDownload,
        cancel = repository::cancelModelDownload,
        delete = repository::deleteModel,
    )
    val index = SemanticSettingsOperation(
        viewModelScope,
        repository.indexState,
        enqueue = repository::enqueueIndex,
        cancel = repository::cancelIndex,
    )

    fun setBackend(backend: EmbeddingBackend) {
        viewModelScope.launch {
            repository.setEmbeddingBackend(backend)
        }
    }

    companion object {
        fun factory(repository: SemanticSearchRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { SemanticSearchSettingsViewModel(repository) }
        }
    }
}

internal class SemanticSettingsOperation(
    private val scope: CoroutineScope,
    status: Flow<SemanticWorkState>,
    private val enqueue: suspend () -> Unit,
    private val cancel: suspend () -> Unit,
    private val delete: (suspend () -> Unit)? = null,
) {
    val status = status.stateIn<SemanticWorkState?>(scope, SharingStarted.WhileSubscribed(5_000), null)
    private val _action = MutableStateFlow(SemanticSettingsAction.Idle)
    val action = _action.asStateFlow()

    fun enqueue() = submit(enqueue)
    fun cancel() = submit(cancel)
    fun delete() {
        delete?.let { submit(it) }
    }

    private fun submit(command: suspend () -> Unit) {
        if (_action.value == SemanticSettingsAction.Submitting) return
        _action.value = SemanticSettingsAction.Submitting
        scope.launch {
            try {
                command()
                _action.value = SemanticSettingsAction.Idle
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                _action.value = SemanticSettingsAction.Failed
            }
        }
    }
}

internal enum class SemanticSettingsAction { Idle, Submitting, Failed }
