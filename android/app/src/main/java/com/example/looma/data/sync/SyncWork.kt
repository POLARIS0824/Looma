package com.example.looma.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.sync.SyncPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class SyncVaultTarget(val id: VaultId, val rootPath: String) {
    constructor(vault: Vault) : this(vault.id, vault.rootPath)

    fun matches(vault: Vault?): Boolean = vault != null && vault.id == id && vault.rootPath == rootPath
}

internal sealed interface SyncActiveWork {
    data object Queued : SyncActiveWork
    data class Running(val phase: SyncPhase, val completed: Int, val total: Int) : SyncActiveWork
}

internal class SyncWork(context: Context) {
    private val manager by lazy { WorkManager.getInstance(context) }

    suspend fun enqueueSync(vault: Vault) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setInputData(workDataOf(VAULT_ID to vault.id.value, VAULT_PATH to vault.rootPath))
            .build()
        manager.enqueueUniqueWork(workName(vault), ExistingWorkPolicy.KEEP, request).await()
    }

    suspend fun cancelSync(vault: Vault) {
        manager.cancelUniqueWork(workName(vault)).await()
    }

    fun observeWork(vault: Vault): Flow<SyncActiveWork?> =
        manager.getWorkInfosForUniqueWorkFlow(workName(vault)).map { jobs ->
            val active = jobs.firstOrNull { !it.state.isFinished } ?: return@map null
            when (active.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> SyncActiveWork.Queued
                WorkInfo.State.RUNNING -> {
                    val phaseName = active.progress.getString(PHASE)
                    val phase = phaseName?.let { name ->
                        SyncPhase.entries.firstOrNull { it.name == name }
                    } ?: SyncPhase.SCANNING_LOCAL
                    SyncActiveWork.Running(
                        phase = phase,
                        completed = active.progress.getInt(COMPLETED, 0),
                        total = active.progress.getInt(TOTAL, 0),
                    )
                }
                else -> null
            }
        }.distinctUntilChanged()

    private fun workName(vault: Vault) = "vault-sync-${vault.id.value}"

    companion object {
        const val VAULT_ID = "vault_id"
        const val VAULT_PATH = "vault_path"
        const val VAULT_CHANGED = "vault_changed"
        const val PHASE = "phase"
        const val COMPLETED = "completed"
        const val TOTAL = "total"
    }
}
