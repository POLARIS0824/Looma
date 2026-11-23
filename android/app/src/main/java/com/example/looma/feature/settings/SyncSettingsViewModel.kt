package com.example.looma.feature.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.R
import com.example.looma.data.s3.S3ErrorKind
import com.example.looma.data.s3.S3Exception
import com.example.looma.data.settings.S3SyncConfig
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.data.sync.SyncNotConfiguredException
import com.example.looma.data.sync.SyncPreview
import com.example.looma.data.sync.SyncRepository
import com.example.looma.data.sync.SyncStalledRelocationException
import com.example.looma.data.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal sealed interface SyncTestState {
    data object Idle : SyncTestState
    data object Testing : SyncTestState
    data object Succeeded : SyncTestState
    data class Failed(@StringRes val messageRes: Int, val detail: String? = null) : SyncTestState
}

internal sealed interface SyncPreviewState {
    data object Closed : SyncPreviewState
    data object Loading : SyncPreviewState
    data class Ready(val preview: SyncPreview) : SyncPreviewState
    data class Failed(@StringRes val messageRes: Int, val detail: String? = null) : SyncPreviewState
}

internal enum class SyncActionState { Idle, Submitting, Failed }

internal data class SyncSettingsUiState(
    val vaultName: String,
    val status: SyncStatus?,
    val testState: SyncTestState,
    val previewState: SyncPreviewState,
    val actionState: SyncActionState,
)

internal class SyncSettingsViewModel(
    private val syncRepository: SyncRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val status = syncRepository.status.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )
    private val _testState = MutableStateFlow<SyncTestState>(SyncTestState.Idle)
    private val _previewState = MutableStateFlow<SyncPreviewState>(SyncPreviewState.Closed)
    private val _actionState = MutableStateFlow(SyncActionState.Idle)

    private var testJob: Job? = null
    private var previewJob: Job? = null

    val uiState: StateFlow<SyncSettingsUiState> = combine(
        status,
        _testState,
        _previewState,
        _actionState,
    ) { currentStatus, testState, previewState, actionState ->
        SyncSettingsUiState(
            vaultName = currentStatus?.vault?.name.orEmpty(),
            status = currentStatus,
            testState = testState,
            previewState = previewState,
            actionState = actionState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SyncSettingsUiState(
            vaultName = "",
            status = null,
            testState = SyncTestState.Idle,
            previewState = SyncPreviewState.Closed,
            actionState = SyncActionState.Idle,
        ),
    )

    suspend fun saveConfig(config: S3SyncConfig) {
        settingsRepository.setSyncConfig(config)
        testJob?.cancel()
        previewJob?.cancel()
        _testState.value = SyncTestState.Idle
        _previewState.value = SyncPreviewState.Closed
        _actionState.value = SyncActionState.Idle
    }

    fun testConnection(config: S3SyncConfig) {
        testJob?.cancel()
        _testState.value = SyncTestState.Testing
        testJob = viewModelScope.launch {
            val result = syncRepository.testConnection(config)
            _testState.value = result.fold(
                onSuccess = { SyncTestState.Succeeded },
                onFailure = { error ->
                    val messageRes = syncErrorMessageRes(error)
                    SyncTestState.Failed(
                        messageRes = messageRes,
                        detail = errorDetailFor(error, messageRes),
                    )
                },
            )
        }
    }

    fun resetTestState() {
        testJob?.cancel()
        _testState.value = SyncTestState.Idle
    }

    fun loadFirstSyncPreview() {
        previewJob?.cancel()
        _actionState.value = SyncActionState.Idle
        _previewState.value = SyncPreviewState.Loading
        previewJob = viewModelScope.launch {
            try {
                val preview = syncRepository.previewFirstSync()
                _previewState.value = SyncPreviewState.Ready(preview)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val messageRes = syncErrorMessageRes(e)
                _previewState.value = SyncPreviewState.Failed(
                    messageRes = messageRes,
                    detail = errorDetailFor(e, messageRes),
                )
            }
        }
    }

    fun dismissPreview() {
        previewJob?.cancel()
        _previewState.value = SyncPreviewState.Closed
        if (_actionState.value == SyncActionState.Failed) {
            _actionState.value = SyncActionState.Idle
        }
    }

    fun startSync() {
        if (_actionState.value == SyncActionState.Submitting) return
        _actionState.value = SyncActionState.Submitting
        viewModelScope.launch {
            try {
                syncRepository.enqueueSync()
                previewJob?.cancel()
                _previewState.value = SyncPreviewState.Closed
                _actionState.value = SyncActionState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _actionState.value = SyncActionState.Failed
            }
        }
    }

    fun cancelSync() {
        if (_actionState.value == SyncActionState.Submitting) return
        _actionState.value = SyncActionState.Submitting
        viewModelScope.launch {
            try {
                syncRepository.cancelSync()
                _actionState.value = SyncActionState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _actionState.value = SyncActionState.Failed
            }
        }
    }

    private fun errorDetailFor(error: Throwable, @StringRes messageRes: Int): String? =
        if (messageRes == R.string.settings_sync_err_generic) {
            error.message?.takeIf { it.isNotBlank() }
        } else {
            null
        }

    companion object {
        fun factory(
            syncRepository: SyncRepository,
            settingsRepository: SettingsRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SyncSettingsViewModel(syncRepository, settingsRepository) }
        }
    }
}

@StringRes
internal fun syncErrorMessageRes(error: Throwable): Int = when (error) {
    is SyncStalledRelocationException -> R.string.settings_sync_err_stalled_relocation
    is SyncNotConfiguredException -> R.string.settings_sync_err_invalid_config
    is S3Exception -> when (error.kind) {
        S3ErrorKind.InvalidConfig -> R.string.settings_sync_err_invalid_config
        S3ErrorKind.AuthFailed -> R.string.settings_sync_err_auth
        S3ErrorKind.BucketNotFound -> R.string.settings_sync_err_bucket_not_found
        S3ErrorKind.ClockSkew -> R.string.settings_sync_err_clock_skew
        S3ErrorKind.Network -> R.string.settings_sync_err_network
        else -> R.string.settings_sync_err_generic
    }
    is IllegalStateException, is IllegalArgumentException -> R.string.settings_sync_err_vault_unavailable
    else -> R.string.settings_sync_err_generic
}
