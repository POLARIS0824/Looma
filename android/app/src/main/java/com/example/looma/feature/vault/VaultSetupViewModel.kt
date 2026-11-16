package com.example.looma.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VaultSetupViewModel internal constructor(
    private val storedVaults: Flow<Vault?>,
    private val accessError: suspend (Vault) -> String?,
    private val activateVault: suspend (String) -> Vault,
    private val requireCurrentVault: suspend () -> Vault,
) : ViewModel() {

    constructor(vaultRepository: VaultRepository) : this(
        storedVaults = vaultRepository.currentVaultFlow,
        accessError = vaultRepository::vaultAccessError,
        activateVault = vaultRepository::setCurrentVault,
        requireCurrentVault = vaultRepository::requireCurrentVault,
    )

    private val _uiState = MutableStateFlow(VaultSetupUiState())
    private var validation: Job? = null

    val uiState: StateFlow<VaultSetupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            storedVaults.collect { refreshVaultStatus() }
        }
    }

    fun refreshVaultStatus() {
        if (_uiState.value.isBusy) return
        validation?.cancel()
        validation = viewModelScope.launch {
            val vault = storedVaults.first()
            val status = if (vault == null) {
                VaultState.None
            } else {
                val reason = accessError(vault)
                if (reason == null) VaultState.Ready(vault) else VaultState.Unavailable(vault, reason)
            }
            ensureActive()
            _uiState.update { state ->
                val activation = state.selection as? VaultSelection.Activated
                state.copy(
                    vaultState = status,
                    selection = if (activation != null && activation.vault != vault) {
                        VaultSelection.Idle
                    } else {
                        state.selection
                    },
                    actionError = if (status is VaultState.Ready && state.vaultState is VaultState.Unavailable) {
                        null
                    } else {
                        state.actionError
                    },
                )
            }
        }
    }

    fun onFolderSelected(
        path: String,
        folderName: String,
    ) {
        _uiState.update { state ->
            if (state.isBusy || state.selection is VaultSelection.Activated) state else state.copy(
                selection = VaultSelection.Pending(VaultFolderChoice(path, folderName)),
                actionError = null,
            )
        }
    }

    fun confirmSelection() {
        val currentState = _uiState.value
        val pending = currentState.selection as? VaultSelection.Pending
        val currentVault = (currentState.vaultState as? VaultState.Ready)?.vault
        if (currentState.selection != VaultSelection.Idle && pending == null) return
        if (pending == null && currentVault == null) return

        validation?.cancel()
        _uiState.update {
            it.copy(
                selection = VaultSelection.Activating(pending?.folder),
                actionError = null,
            )
        }

        viewModelScope.launch {
            try {
                val vault = if (pending != null) {
                    activateVault(pending.folder.path)
                } else {
                    requireCurrentVault().also { check(it == currentVault) }
                }

                _uiState.update {
                    it.copy(
                        vaultState = VaultState.Ready(vault),
                        selection = VaultSelection.Activated(vault),
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(selection = pending ?: VaultSelection.Idle)
                }
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(selection = pending ?: VaultSelection.Idle, actionError = VaultSetupError.GENERIC)
                }
            } finally {
                refreshVaultStatus()
            }
        }
    }

    fun showError(error: VaultSetupError) {
        _uiState.update { state ->
            if (state.isBusy || state.selection is VaultSelection.Activated) state
            else state.copy(actionError = error)
        }
    }

    fun clearError() {
        _uiState.update {
            it.copy(actionError = null)
        }
    }

    fun onActivationHandled(activation: VaultSelection.Activated) {
        _uiState.update { state ->
            if (state.selection === activation) state.copy(selection = VaultSelection.Idle) else state
        }
    }

    companion object {
        fun factory(
            vaultRepository: VaultRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                VaultSetupViewModel(vaultRepository)
            }
        }
    }
}
