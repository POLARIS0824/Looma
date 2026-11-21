package com.example.looma.data.search

import android.content.Context
import android.os.Process
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface SemanticWorkState {
    data object Unavailable : SemanticWorkState
    data object Idle : SemanticWorkState
    data object Queued : SemanticWorkState
    data class Running(val completed: Int, val total: Int) : SemanticWorkState
    data object Partial : SemanticWorkState
    data object Ready : SemanticWorkState
    data object Cancelled : SemanticWorkState
    data object Failed : SemanticWorkState
}

internal data class SemanticVaultTarget(val id: VaultId, val rootPath: String) {
    constructor(vault: Vault) : this(vault.id, vault.rootPath)

    fun matches(vault: Vault?): Boolean = vault != null && vault.id == id && vault.rootPath == rootPath
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class SemanticSearchWork(
    context: Context,
    private val vaults: VaultRepository,
    private val model: EmbeddingModelFile,
    private val cache: SemanticVectorCache,
) {
    private val scheduling = kotlinx.coroutines.sync.Mutex()
    private val manager by lazy { WorkManager.getInstance(context) }
    val supported get() = Process.is64Bit()

    val modelState: Flow<SemanticWorkState>
        get() = if (supported) {
            observe(MODEL_WORK_NAME).map { info ->
                val ready = (info == null || info.state.isFinished) && modelIsReady()
                if (ready) SemanticWorkState.Ready else info.state(false)
            }.distinctUntilChanged()
        } else {
            flowOf(SemanticWorkState.Unavailable)
        }

    val indexState: Flow<SemanticWorkState> = currentVaultWork(SemanticWorkState.Unavailable) { vault, info ->
        val state = info.state(cache.hasCompletedIndex(vault.id))
        if (state == SemanticWorkState.Ready && cache.partial(vault.id)) SemanticWorkState.Partial else state
    }

    val hasIndex: Flow<Boolean> = currentVaultWork(false) { vault, _ ->
        cache.hasSearchableIndex(vault.id)
    }

    suspend fun enqueueDownload() {
        check(supported)
        if (modelIsReady()) return
        val request = OneTimeWorkRequestBuilder<SemanticModelDownloadWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        manager.enqueueUniqueWork(MODEL_WORK_NAME, ExistingWorkPolicy.KEEP, request).await()
    }

    suspend fun cancelDownload() {
        manager.cancelUniqueWork(MODEL_WORK_NAME).await()
    }

    suspend fun enqueueIndex() {
        check(supported)
        val vault = vaults.requireCurrentVault()
        if (!modelIsReady()) throw SemanticModelMissingException()
        withContext(Dispatchers.IO) { cache.enable(vault.id) }
        val request = OneTimeWorkRequestBuilder<SemanticVaultIndexWorker>()
            .setInputData(workDataOf(VAULT_ID to vault.id.value, VAULT_PATH to vault.rootPath))
            .build()
        manager.enqueueUniqueWork(indexName(vault), ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
    }

    suspend fun enqueueUpdates(vault: Vault) = scheduling.withLock {
        val name = indexName(vault)
        val jobs = manager.getWorkInfosForUniqueWorkFlow(name).first()
        if (jobs.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return@withLock
        val request = OneTimeWorkRequestBuilder<SemanticVaultIndexWorker>()
            .setInputData(workDataOf(VAULT_ID to vault.id.value, VAULT_PATH to vault.rootPath, INCREMENTAL to true))
            .build()
        manager.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
        Unit
    }

    suspend fun cancelIndex() {
        val vault = vaults.getCurrentVault() ?: return
        manager.cancelUniqueWork(indexName(vault)).await()
    }

    private suspend fun modelIsReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            model.exists() && model.open() != null
        } catch (_: IOException) {
            false
        }
    }

    private fun <T> currentVaultWork(empty: T, read: (Vault, WorkInfo?) -> T): Flow<T> =
        vaults.currentVaultFlow.distinctUntilChanged().flatMapLatest { vault ->
            if (!supported || vault == null) return@flatMapLatest flowOf(empty)
            observe(indexName(vault)).map { info -> withContext(Dispatchers.IO) { read(vault, info) } }
        }.distinctUntilChanged()

    private fun observe(name: String): Flow<WorkInfo?> =
        manager.getWorkInfosForUniqueWorkFlow(name).map { jobs ->
            jobs.firstOrNull { !it.state.isFinished } ?: jobs.singleOrNull()
        }

    private fun WorkInfo?.state(ready: Boolean): SemanticWorkState = when (this?.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> SemanticWorkState.Queued
        WorkInfo.State.RUNNING -> SemanticWorkState.Running(
            completed = progress.getInt(COMPLETED, 0),
            total = progress.getInt(TOTAL, 0),
        )
        WorkInfo.State.FAILED -> if (outputData.getBoolean(VAULT_CHANGED, false)) {
            SemanticWorkState.Cancelled
        } else {
            SemanticWorkState.Failed
        }
        WorkInfo.State.CANCELLED -> SemanticWorkState.Cancelled
        else -> if (ready) SemanticWorkState.Ready else SemanticWorkState.Idle
    }

    private fun indexName(vault: Vault) = "semantic-index-${vault.id.value}"

    companion object {
        private const val MODEL_WORK_NAME = "semantic-embedding-model"
        const val INCREMENTAL = "incremental"
        const val VAULT_ID = "vault_id"
        const val VAULT_PATH = "vault_path"
        const val VAULT_CHANGED = "vault_changed"
        const val COMPLETED = "completed"
        const val TOTAL = "total"
    }
}
