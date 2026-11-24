package com.example.looma.data.sync

import android.content.Context
import com.example.looma.R
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.s3.S3Client
import com.example.looma.data.s3.S3ErrorKind
import com.example.looma.data.s3.S3Exception
import com.example.looma.data.settings.S3SyncConfig
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.sync.RemotePrefix
import com.example.looma.domain.sync.SyncPhase
import com.example.looma.domain.sync.SyncRunOutcome
import io.ktor.client.HttpClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SyncStatus(
    val vault: Vault?,
    val config: S3SyncConfig,
    val credentialsUnavailable: Boolean,
    val targetKey: String?,
    val hasCompletedInitialSync: Boolean,
    val activeWork: SyncActiveWork?,
    val lastState: SyncVaultStateEntity?,
)

class SyncRepository internal constructor(
    private val context: Context,
    private val vaultRepository: VaultRepository,
    private val settingsRepository: SettingsRepository,
    private val noteRepository: NoteRepository,
    private val syncStateDao: SyncStateDao,
    private val httpClient: HttpClient,
) {
    private val s3Client = S3Client(httpClient)
    private val engine = SyncEngine(
        s3Client = s3Client,
        syncStateDao = syncStateDao,
        noteRepository = noteRepository,
    )
    private val work = SyncWork(context)
    private val vaultMutexes = ConcurrentHashMap<VaultId, Mutex>()

    private val _vaultMutatedEvents = MutableSharedFlow<VaultId>(extraBufferCapacity = 8)
    internal val vaultMutatedEvents: SharedFlow<VaultId> = _vaultMutatedEvents.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    internal val status: Flow<SyncStatus> = combine(
        vaultRepository.currentVaultFlow.distinctUntilChanged(),
        settingsRepository.settings.distinctUntilChanged(),
    ) { vault, settings ->
        vault to settings
    }.flatMapLatest { (vault, settings) ->
        val config = settings.syncConfig
        val credentialsUnavailable = settings.syncCredentialsUnavailable
        val targetKey = targetKeyFor(config, credentialsUnavailable)
        if (vault == null) {
            flowOf(
                SyncStatus(
                    vault = null,
                    config = config,
                    credentialsUnavailable = credentialsUnavailable,
                    targetKey = targetKey,
                    hasCompletedInitialSync = false,
                    activeWork = null,
                    lastState = null,
                ),
            )
        } else if (targetKey == null) {
            work.observeWork(vault).map { activeWork ->
                SyncStatus(
                    vault = vault,
                    config = config,
                    credentialsUnavailable = credentialsUnavailable,
                    targetKey = null,
                    hasCompletedInitialSync = false,
                    activeWork = activeWork,
                    lastState = null,
                )
            }
        } else {
            combine(
                work.observeWork(vault),
                syncStateDao.observeVaultState(vault.id.value, targetKey),
            ) { activeWork, lastState ->
                SyncStatus(
                    vault = vault,
                    config = config,
                    credentialsUnavailable = credentialsUnavailable,
                    targetKey = targetKey,
                    hasCompletedInitialSync = lastState?.hasCompletedInitialSync == true,
                    activeWork = activeWork,
                    lastState = lastState,
                )
            }
        }
    }.distinctUntilChanged()

    internal suspend fun testConnection(config: S3SyncConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!config.isConfigured) throw SyncNotConfiguredException()
            val prefix = RemotePrefix.normalizeOrNull(config.remotePrefix)
                ?: throw S3Exception(kind = S3ErrorKind.InvalidConfig, message = "Invalid remote prefix")
            s3Client.testConnection(config.toEndpointConfig(), prefix)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal suspend fun previewFirstSync(): SyncPreview = withContext(Dispatchers.IO) {
        val vault = vaultRepository.requireCurrentVault()
        val target = SyncVaultTarget(vault)
        val appSettings = settingsRepository.settings.first()
        if (appSettings.syncCredentialsUnavailable || !appSettings.syncConfig.isConfigured) {
            throw SyncNotConfiguredException()
        }
        val config = appSettings.syncConfig
        coroutineScope {
            val watcher = launch {
                vaultRepository.currentVaultFlow.first { !target.matches(it) }
                throw SyncVaultChangedException()
            }
            try {
                mutexFor(vault.id).withLock {
                    val callerContext = coroutineContext
                    val currentVault = vaultRepository.requireCurrentVault()
                    if (!target.matches(currentVault)) throw SyncVaultChangedException()
                    engine.preview(currentVault, config) {
                        callerContext.ensureActive()
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    internal suspend fun enqueueSync() {
        val vault = vaultRepository.requireCurrentVault()
        val appSettings = settingsRepository.settings.first()
        if (appSettings.syncCredentialsUnavailable || !appSettings.syncConfig.isConfigured) {
            throw SyncNotConfiguredException()
        }
        if (RemotePrefix.normalizeOrNull(appSettings.syncConfig.remotePrefix) == null) {
            throw S3Exception(kind = S3ErrorKind.InvalidConfig, message = "Invalid remote prefix")
        }
        work.enqueueSync(vault)
    }

    internal suspend fun cancelSync() {
        val vault = vaultRepository.getCurrentVault() ?: return
        work.cancelSync(vault)
    }

    internal suspend fun runSync(
        target: SyncVaultTarget,
        onProgress: suspend (SyncPhase, Int, Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var resolvedTargetKey: String? = null
        try {
            coroutineScope {
                val watcher = launch {
                    vaultRepository.currentVaultFlow.first { !target.matches(it) }
                    throw SyncVaultChangedException()
                }
                try {
                    mutexFor(target.id).withLock {
                        val currentVault = vaultRepository.requireCurrentVault()
                        if (!target.matches(currentVault)) throw SyncVaultChangedException()

                        val appSettings = settingsRepository.settings.first()
                        if (appSettings.syncCredentialsUnavailable || !appSettings.syncConfig.isConfigured) {
                            throw SyncNotConfiguredException()
                        }
                        val config = appSettings.syncConfig
                        val prefix = RemotePrefix.normalizeOrNull(config.remotePrefix)
                            ?: throw S3Exception(kind = S3ErrorKind.InvalidConfig, message = "Invalid remote prefix")
                        resolvedTargetKey = config.toEndpointConfig().targetKey(prefix)

                        engine.sync(
                            vault = currentVault,
                            config = config,
                            checkVaultStillActive = {
                                coroutineContext.ensureActive()
                                if (!target.matches(vaultRepository.getCurrentVault())) {
                                    throw SyncVaultChangedException()
                                }
                            },
                            onProgress = onProgress,
                            onLocalMutated = { _vaultMutatedEvents.tryEmit(currentVault.id) },
                            formatError = ::mapErrorMessage,
                        )
                    }
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                recordTerminalState(
                    vaultId = target.id,
                    targetKey = resolvedTargetKey,
                    outcome = SyncRunOutcome.CANCELLED,
                    errorMessage = null,
                )
            }
            throw e
        } catch (e: SyncVaultChangedException) {
            withContext(NonCancellable) {
                recordTerminalState(
                    vaultId = target.id,
                    targetKey = resolvedTargetKey,
                    outcome = SyncRunOutcome.CANCELLED,
                    errorMessage = null,
                )
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                recordTerminalState(
                    vaultId = target.id,
                    targetKey = resolvedTargetKey,
                    outcome = SyncRunOutcome.FAILED,
                    errorMessage = mapErrorMessage(e),
                )
            }
            throw e
        }
    }

    private suspend fun recordTerminalState(
        vaultId: VaultId,
        targetKey: String?,
        outcome: SyncRunOutcome,
        errorMessage: String?,
    ) {
        val effectiveTargetKey = targetKey ?: runCatching {
            val appSettings = settingsRepository.settings.first()
            targetKeyFor(appSettings.syncConfig, appSettings.syncCredentialsUnavailable)
        }.getOrNull() ?: return
        val previous = syncStateDao.getVaultState(vaultId.value, effectiveTargetKey)
        syncStateDao.upsertVaultState(
            SyncVaultStateEntity(
                vaultId = vaultId.value,
                targetKey = effectiveTargetKey,
                hasCompletedInitialSync = previous?.hasCompletedInitialSync == true,
                lastSyncAtMillis = System.currentTimeMillis(),
                lastOutcome = outcome.name,
                lastUploaded = 0,
                lastDownloaded = 0,
                lastDeletedLocal = 0,
                lastDeletedRemote = 0,
                lastConflicts = 0,
                lastFailed = if (outcome == SyncRunOutcome.FAILED) 1 else 0,
                lastSkipped = 0,
                lastErrorMessage = errorMessage,
            ),
        )
    }

    private fun mutexFor(vaultId: VaultId): Mutex =
        vaultMutexes.computeIfAbsent(vaultId) { Mutex() }

    private fun targetKeyFor(config: S3SyncConfig, credentialsUnavailable: Boolean): String? {
        if (credentialsUnavailable || !config.isConfigured) return null
        val prefix = RemotePrefix.normalizeOrNull(config.remotePrefix) ?: return null
        return config.toEndpointConfig().targetKey(prefix)
    }

    private fun mapErrorMessage(error: Throwable): String = when (error) {
        is SyncStalledRelocationException -> context.getString(R.string.settings_sync_err_stalled_relocation)
        is SyncNotConfiguredException -> context.getString(R.string.settings_sync_err_invalid_config)
        is S3Exception -> when (error.kind) {
            S3ErrorKind.InvalidConfig -> context.getString(R.string.settings_sync_err_invalid_config)
            S3ErrorKind.AuthFailed -> context.getString(R.string.settings_sync_err_auth)
            S3ErrorKind.BucketNotFound -> context.getString(R.string.settings_sync_err_bucket_not_found)
            S3ErrorKind.ClockSkew -> context.getString(R.string.settings_sync_err_clock_skew)
            S3ErrorKind.Network -> context.getString(R.string.settings_sync_err_network)
            else -> error.message.takeIf { it.isNotBlank() } ?: context.getString(R.string.settings_sync_err_generic)
        }
        is IllegalStateException, is IllegalArgumentException ->
            context.getString(R.string.settings_sync_err_vault_unavailable)
        else -> error.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.settings_sync_err_generic)
    }
}
