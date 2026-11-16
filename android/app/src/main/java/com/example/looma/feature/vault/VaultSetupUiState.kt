package com.example.looma.feature.vault

import com.example.looma.domain.model.Vault

sealed interface VaultState {
    data object Checking : VaultState

    data object None : VaultState

    data class Ready(val vault: Vault) : VaultState

    data class Unavailable(val vault: Vault, val reason: String) : VaultState
}

enum class VaultSetupError {
    INVALID_FOLDER,
    STORAGE_SETTINGS,
    PERMISSION_ALL_FILES,
    PERMISSION_STORAGE,
    UNAVAILABLE,
    GENERIC,
}

data class VaultFolderChoice(val path: String, val name: String)

sealed interface VaultSelection {
    data object Idle : VaultSelection
    data class Pending(val folder: VaultFolderChoice) : VaultSelection
    data class Activating(val folder: VaultFolderChoice?) : VaultSelection
    class Activated(val vault: Vault) : VaultSelection
}

data class VaultSetupUiState(
    val vaultState: VaultState = VaultState.Checking,
    val selection: VaultSelection = VaultSelection.Idle,
    val actionError: VaultSetupError? = null,
) {
    val isBusy: Boolean get() = selection is VaultSelection.Activating

    val isInitializing: Boolean
        get() = vaultState is VaultState.Checking

    val currentFolderName: String?
        get() = (vaultState as? VaultState.Ready)?.vault?.name

    val currentFolderPath: String?
        get() = (vaultState as? VaultState.Ready)?.vault?.rootPath

    val selectedFolderName: String?
        get() = when (selection) {
            is VaultSelection.Pending -> selection.folder.name
            is VaultSelection.Activating -> selection.folder?.name
            else -> null
        }

    val error: VaultSetupError?
        get() = if (selectedFolderName != null) {
            actionError
        } else {
            if (vaultState is VaultState.Unavailable) VaultSetupError.UNAVAILABLE else actionError
        }
}
