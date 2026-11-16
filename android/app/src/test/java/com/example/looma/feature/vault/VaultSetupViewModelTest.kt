package com.example.looma.feature.vault

import androidx.lifecycle.viewModelScope
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultSetupViewModelTest {
    @Test
    fun `a late access check cannot override the activated vault`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val stored = MutableStateFlow<Vault?>(oldVault)
        val activated = CompletableDeferred<Vault>()
        var holdOldCheck = false
        lateinit var oldCheck: Continuation<String?>
        val viewModel = VaultSetupViewModel(
            storedVaults = stored,
            accessError = { vault ->
                if (vault == oldVault && holdOldCheck) suspendCoroutine { oldCheck = it } else null
            },
            activateVault = { activated.await().also { stored.value = it } },
            requireCurrentVault = { error("Expected to activate a picked folder") },
        )
        try {
            runCurrent()
            assertEquals(VaultState.Ready(oldVault), viewModel.uiState.value.vaultState)

            holdOldCheck = true
            viewModel.refreshVaultStatus()
            runCurrent()
            viewModel.onFolderSelected(newVault.rootPath, newVault.name)
            viewModel.confirmSelection()
            runCurrent()
            assertTrue(viewModel.uiState.value.isBusy)

            activated.complete(newVault)
            runCurrent()
            val receipt = viewModel.uiState.value.selection as VaultSelection.Activated
            assertEquals(newVault, receipt.vault)

            oldCheck.resume("the old folder was removed")
            runCurrent()
            assertEquals(VaultState.Ready(newVault), viewModel.uiState.value.vaultState)
            assertSame(receipt, viewModel.uiState.value.selection)
            viewModel.onActivationHandled(VaultSelection.Activated(newVault))
            assertSame(receipt, viewModel.uiState.value.selection)
            viewModel.onActivationHandled(receipt)
            assertEquals(VaultSelection.Idle, viewModel.uiState.value.selection)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed activation keeps the picked folder available for retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val stored = MutableStateFlow<Vault?>(oldVault)
        var unavailable = true
        val viewModel = VaultSetupViewModel(
            storedVaults = stored,
            accessError = { null },
            activateVault = {
                assertEquals(newVault.rootPath, it)
                check(!unavailable)
                newVault.also { stored.value = it }
            },
            requireCurrentVault = { error("Expected to activate a picked folder") },
        )
        try {
            runCurrent()
            viewModel.onFolderSelected(newVault.rootPath, newVault.name)
            viewModel.confirmSelection()
            runCurrent()
            assertEquals(VaultSetupError.GENERIC, viewModel.uiState.value.error)
            assertEquals(newVault.name, viewModel.uiState.value.selectedFolderName)
            assertFalse(viewModel.uiState.value.isBusy)
            assertEquals(VaultState.Ready(oldVault), viewModel.uiState.value.vaultState)

            unavailable = false
            viewModel.confirmSelection()
            runCurrent()
            assertEquals(VaultState.Ready(newVault), viewModel.uiState.value.vaultState)
            assertEquals(null, viewModel.uiState.value.error)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private val oldVault = Vault(VaultId("old"), "Old vault", "/vault/old")
    private val newVault = Vault(VaultId("new"), "New vault", "/vault/new")
}
