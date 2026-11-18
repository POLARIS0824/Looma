package com.example.looma.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.search.SemanticSearchRepository
import com.example.looma.data.search.SemanticWorkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class SemanticSearchSettingsViewModel(repository: SemanticSearchRepository) : ViewModel() {
    val supported = repository.supported
    val model = SemanticSettingsOperation(
        viewModelScope,
        repository.modelState,
        enqueue = repository::enqueueModelDownload,
        cancel = repository::cancelModelDownload,
    )
    val index = SemanticSettingsOperation(
        viewModelScope,
        repository.indexState,
        enqueue = repository::enqueueIndex,
        cancel = repository::cancelIndex,
    )

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
) {
    val status = status.stateIn<SemanticWorkState?>(scope, SharingStarted.WhileSubscribed(5_000), null)
    private val _action = MutableStateFlow(SemanticSettingsAction.Idle)
    val action = _action.asStateFlow()

    fun enqueue() = submit(enqueue)
    fun cancel() = submit(cancel)

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
