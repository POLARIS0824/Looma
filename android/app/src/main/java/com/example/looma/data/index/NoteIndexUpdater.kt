package com.example.looma.data.index

import com.example.looma.domain.model.Vault
import java.io.File
import kotlinx.coroutines.CancellationException

internal class NoteIndexUpdater(
    private val index: NoteIndex,
    private val onChanged: suspend (Vault, IndexDelta) -> Unit = { _, _ -> },
) {

    suspend fun scan(vault: Vault, force: Boolean = false): IndexScan =
        VaultIndexer(File(vault.rootPath), vault, index, onChanged).scan(force)

    suspend fun apply(vault: Vault, delta: IndexDelta) {
        try {
            index.apply(delta)
            onChanged(vault, delta)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            rebuild(vault)
        }
    }

    private suspend fun rebuild(vault: Vault) {
        try {
            scan(vault)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }
}
