package com.example.looma.data.sync

import com.example.looma.data.note.NoteRepository
import com.example.looma.data.note.invalidateAttachmentIndex
import com.example.looma.data.s3.S3Client
import com.example.looma.data.s3.S3EndpointConfig
import com.example.looma.data.s3.S3ErrorKind
import com.example.looma.data.s3.S3Exception
import com.example.looma.data.s3.S3PutCondition
import com.example.looma.data.settings.S3SyncConfig
import com.example.looma.data.vault.VaultWriteCoordinator
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.sync.LocalFileSnapshot
import com.example.looma.domain.sync.RemoteObjectSnapshot
import com.example.looma.domain.sync.RemotePrefix
import com.example.looma.domain.sync.SyncBaseline
import com.example.looma.domain.sync.SyncFileFilter
import com.example.looma.domain.sync.SyncPhase
import com.example.looma.domain.sync.SyncPlan
import com.example.looma.domain.sync.SyncPlanItem
import com.example.looma.domain.sync.SyncPlanner
import com.example.looma.domain.sync.SyncRelativePath
import com.example.looma.domain.sync.SyncRunOutcome
import com.example.looma.domain.sync.SyncSkipReason
import com.example.looma.domain.sync.SyncSkippedItem
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class SyncPreview(
    val vaultId: VaultId,
    val targetKey: String,
    val plan: SyncPlan,
)

internal data class SyncRunSummary(
    val outcome: SyncRunOutcome,
    val uploaded: Int,
    val downloaded: Int,
    val deletedLocal: Int,
    val deletedRemote: Int,
    val conflicts: Int,
    val failed: Int,
    val skipped: Int,
    val mutatedLocal: Boolean,
    val errorMessage: String?,
)

internal class SyncStalledRelocationException(
    val stalled: StalledRelocation,
) : IOException("Stalled relocation prevents sync: ${stalled.journalId}")

internal class SyncVaultChangedException : IOException("Active vault changed during sync")

internal class SyncNotConfiguredException : IOException("Vault sync is not configured")

internal fun S3SyncConfig.toEndpointConfig(): S3EndpointConfig = S3EndpointConfig(
    endpoint = endpoint,
    region = region,
    bucket = bucket,
    accessKey = accessKey,
    secretKey = secretKey,
    pathStyle = pathStyle,
)

internal class SyncEngine(
    private val s3Client: S3Client,
    private val syncStateDao: SyncStateDao,
    private val noteRepository: NoteRepository,
) {
    private data class PlannedSync(
        val targetKey: String,
        val endpointConfig: S3EndpointConfig,
        val prefix: RemotePrefix,
        val root: File,
        val previousState: SyncVaultStateEntity?,
        val plan: SyncPlan,
    )

    suspend fun preview(
        vault: Vault,
        config: S3SyncConfig,
        checkActive: () -> Unit,
    ): SyncPreview = withContext(Dispatchers.IO) {
        val planned = preparePlan(
            vault = vault,
            config = config,
            cleanTemp = false,
            checkActiveSync = {
                coroutineContext.ensureActive()
                checkActive()
            },
            checkActiveBlocking = checkActive,
            onPhase = {},
        )
        SyncPreview(
            vaultId = vault.id,
            targetKey = planned.targetKey,
            plan = planned.plan,
        )
    }

    suspend fun sync(
        vault: Vault,
        config: S3SyncConfig,
        checkVaultStillActive: suspend () -> Unit,
        onProgress: suspend (SyncPhase, Int, Int) -> Unit,
        onLocalMutated: () -> Unit = {},
        formatError: (Throwable) -> String = { it.message?.takeIf { msg -> msg.isNotBlank() } ?: it::class.simpleName.orEmpty() },
    ): SyncRunSummary = withContext(Dispatchers.IO) {
        val callerContext = coroutineContext
        val planned = preparePlan(
            vault = vault,
            config = config,
            cleanTemp = true,
            checkActiveSync = checkVaultStillActive,
            checkActiveBlocking = { callerContext.ensureActive() },
            onPhase = { phase -> onProgress(phase, 0, 0) },
        )

        val root = planned.root
        val targetKey = planned.targetKey
        val endpointConfig = planned.endpointConfig
        val prefix = planned.prefix
        val plan = planned.plan

        var uploaded = 0
        var downloaded = 0
        var deletedLocal = 0
        var deletedRemote = 0
        var conflicts = 0
        var failed = 0
        var consecutiveTransientErrors = 0
        var firstErrorMessage: String? = null
        val changedLocalPaths = LinkedHashSet<SyncRelativePath>()
        val removedLocalPaths = LinkedHashSet<SyncRelativePath>()
        var mutatedNonMarkdown = false
        var aborted = true

        fun markLocalChanged(path: SyncRelativePath) {
            if (SyncFileFilter.isMarkdownPath(path)) {
                removedLocalPaths.remove(path)
                changedLocalPaths += path
            } else {
                mutatedNonMarkdown = true
            }
        }

        fun markLocalRemoved(path: SyncRelativePath) {
            if (SyncFileFilter.isMarkdownPath(path)) {
                changedLocalPaths.remove(path)
                removedLocalPaths += path
            } else {
                mutatedNonMarkdown = true
            }
        }

        fun recordFailure(message: String) {
            failed++
            if (firstErrorMessage == null) {
                firstErrorMessage = message
            }
        }

        try {
            val totalItems = plan.items.size
            onProgress(SyncPhase.SYNCING, 0, totalItems)
            for ((index, item) in plan.items.withIndex()) {
                checkVaultStillActive()
                try {
                    executePlanItem(
                        item = item,
                        vault = vault,
                        root = root,
                        targetKey = targetKey,
                        endpointConfig = endpointConfig,
                        prefix = prefix,
                        checkActive = { callerContext.ensureActive() },
                        markLocalChanged = ::markLocalChanged,
                        markLocalRemoved = ::markLocalRemoved,
                        onUploaded = { uploaded++ },
                        onDownloaded = { downloaded++ },
                        onDeletedLocal = { deletedLocal++ },
                        onDeletedRemote = { deletedRemote++ },
                        onConflict = { conflicts++ },
                        onFailed = ::recordFailure,
                    )
                    consecutiveTransientErrors = 0
                } catch (t: Throwable) {
                    if (isFatalError(t)) throw t
                    if (t is IOException) {
                        val isTransient = t !is S3Exception || t.kind in TRANSIENT_S3_ERROR_KINDS
                        if (isTransient) {
                            consecutiveTransientErrors++
                            if (consecutiveTransientErrors >= MAX_CONSECUTIVE_TRANSIENT_ERRORS) {
                                throw t
                            }
                        }
                        recordFailure(formatError(t))
                    } else {
                        throw t
                    }
                }
                onProgress(SyncPhase.SYNCING, index + 1, totalItems)
            }

            aborted = false
        } finally {
            val mutatedLocal = changedLocalPaths.isNotEmpty() || removedLocalPaths.isNotEmpty() || mutatedNonMarkdown
            if (mutatedLocal) {
                withContext(NonCancellable) {
                    val vaultStillMatches = runCatching {
                        checkVaultStillActive()
                        true
                    }.getOrDefault(false)
                    if (vaultStillMatches) {
                        if (!aborted) {
                            runCatching { onProgress(SyncPhase.UPDATING_INDEX, 0, 0) }
                        }
                        val changedNotes = changedLocalPaths.map { NotePath(it.value) }
                        val removedNotes = removedLocalPaths.map { NotePath(it.value) }
                        runCatching {
                            noteRepository.applyExternalNoteChanges(vault, changedNotes, removedNotes)
                        }
                        if (mutatedNonMarkdown) {
                            invalidateAttachmentIndex(root)
                        }
                        onLocalMutated()
                    }
                }
            }
        }

        val mutatedLocal = changedLocalPaths.isNotEmpty() || removedLocalPaths.isNotEmpty() || mutatedNonMarkdown
        val anyActionableTransferred = uploaded > 0 || downloaded > 0 || deletedLocal > 0 || deletedRemote > 0 || conflicts > 0
        val hasActionableItems = plan.items.any {
            it !is SyncPlanItem.ClearBaseline &&
                it !is SyncPlanItem.RefreshBaselineStats &&
                it !is SyncPlanItem.AdoptMatched
        }
        val outcome = when {
            failed > 0 && !anyActionableTransferred && hasActionableItems -> SyncRunOutcome.FAILED
            failed > 0 -> SyncRunOutcome.PARTIAL
            conflicts > 0 -> SyncRunOutcome.SUCCEEDED_WITH_CONFLICTS
            else -> SyncRunOutcome.SUCCEEDED
        }
        val previousCompleted = planned.previousState?.hasCompletedInitialSync == true
        syncStateDao.upsertVaultState(
            SyncVaultStateEntity(
                vaultId = vault.id.value,
                targetKey = targetKey,
                hasCompletedInitialSync = previousCompleted || (failed == 0),
                lastSyncAtMillis = System.currentTimeMillis(),
                lastOutcome = outcome.name,
                lastUploaded = uploaded,
                lastDownloaded = downloaded,
                lastDeletedLocal = deletedLocal,
                lastDeletedRemote = deletedRemote,
                lastConflicts = conflicts,
                lastFailed = failed,
                lastSkipped = plan.skipped.size,
                lastErrorMessage = firstErrorMessage,
            ),
        )

        SyncRunSummary(
            outcome = outcome,
            uploaded = uploaded,
            downloaded = downloaded,
            deletedLocal = deletedLocal,
            deletedRemote = deletedRemote,
            conflicts = conflicts,
            failed = failed,
            skipped = plan.skipped.size,
            mutatedLocal = mutatedLocal,
            errorMessage = firstErrorMessage,
        )
    }

    private suspend fun preparePlan(
        vault: Vault,
        config: S3SyncConfig,
        cleanTemp: Boolean,
        checkActiveSync: suspend () -> Unit,
        checkActiveBlocking: () -> Unit,
        onPhase: suspend (SyncPhase) -> Unit,
    ): PlannedSync {
        if (!config.isConfigured) throw SyncNotConfiguredException()
        val prefix = RemotePrefix.normalizeOrNull(config.remotePrefix)
            ?: throw S3Exception(kind = S3ErrorKind.InvalidConfig, message = "Invalid remote prefix")
        val endpointConfig = config.toEndpointConfig()
        val targetKey = endpointConfig.targetKey(prefix)

        checkActiveSync()
        val stalled = noteRepository.prepareForSync(vault)
        if (stalled != null) throw SyncStalledRelocationException(stalled)

        val root = File(vault.rootPath).canonicalFile
        if (cleanTemp) {
            VaultWriteCoordinator.write(vault) {
                SyncLocalStore.cleanStaleTempFiles(root)
            }
        }
        checkActiveSync()

        onPhase(SyncPhase.SCANNING_LOCAL)
        val localScan = SyncLocalStore.scanLocalFiles(root, checkActiveBlocking)
        if (!localScan.complete) {
            throw IOException("Local vault scan did not complete")
        }

        val baselines = syncStateDao.entriesFor(vault.id.value, targetKey).associate { entity ->
            val path = SyncRelativePath(entity.path)
            path to entity.toBaseline()
        }
        val previousState = syncStateDao.getVaultState(vault.id.value, targetKey)
        val isInitialSync = previousState?.hasCompletedInitialSync != true

        checkActiveSync()
        onPhase(SyncPhase.LISTING_REMOTE)
        val rawRemoteObjects = s3Client.listAllObjects(endpointConfig, prefix)
        checkActiveSync()

        val remoteObjects = LinkedHashMap<SyncRelativePath, RemoteObjectSnapshot>(rawRemoteObjects.size)
        val remoteSkipped = mutableListOf<SyncSkippedItem>()
        for (summary in rawRemoteObjects) {
            checkActiveBlocking()
            val key = summary.key
            if (!key.startsWith(prefix.value)) continue
            val relativeRaw = key.removePrefix(prefix.value)
            if (relativeRaw.isEmpty() || relativeRaw.endsWith('/')) continue
            if (prefix.isUnderHiddenDirectory(key)) continue
            val relPath = prefix.fromObjectKey(key)
            if (relPath == null) {
                remoteSkipped += SyncSkippedItem(relativeRaw, SyncSkipReason.InvalidPath)
                continue
            }
            if (summary.sizeBytes > SyncFileFilter.MAX_SYNC_FILE_BYTES) {
                remoteSkipped += SyncSkippedItem(relPath.value, SyncSkipReason.FileTooLarge)
                continue
            }
            remoteObjects[relPath] = RemoteObjectSnapshot(
                path = relPath,
                objectKey = key,
                etag = summary.etag,
                sizeBytes = summary.sizeBytes,
                lastModifiedMillis = summary.lastModifiedMillis,
            )
        }

        val hashedLocalFiles = LinkedHashMap<SyncRelativePath, LocalFileSnapshot>(localScan.files.size)
        for ((path, local) in localScan.files) {
            checkActiveBlocking()
            val remote = remoteObjects[path]
            val baseline = baselines[path]
            if (SyncPlanner.needsLocalHash(local, remote, baseline)) {
                val targetFile = SyncLocalStore.resolveSafeTarget(root, path)
                val digests = SyncLocalStore.computeFileDigests(targetFile, checkActiveBlocking)
                hashedLocalFiles[path] = local.copy(
                    sizeBytes = digests.sizeBytes,
                    modifiedAtMillis = digests.modifiedAtMillis,
                    sha256 = digests.sha256,
                    md5 = digests.md5,
                )
            } else {
                hashedLocalFiles[path] = local
            }
        }

        val extraSkipped = (localScan.skipped + remoteSkipped).sortedBy { it.rawPath }
        val plan = SyncPlanner.plan(
            isInitialSync = isInitialSync,
            localFiles = hashedLocalFiles,
            remoteObjects = remoteObjects,
            baselines = baselines,
            extraSkipped = extraSkipped,
        )

        return PlannedSync(
            targetKey = targetKey,
            endpointConfig = endpointConfig,
            prefix = prefix,
            root = root,
            previousState = previousState,
            plan = plan,
        )
    }

    private suspend fun downloadAndCommit(
        vault: Vault,
        root: File,
        targetKey: String,
        endpointConfig: S3EndpointConfig,
        prefix: RemotePrefix,
        path: SyncRelativePath,
        remote: RemoteObjectSnapshot,
        commit: (stagedFile: File, downloadedSha256: String) -> LocalCommitResult,
        markLocalChanged: (SyncRelativePath) -> Unit,
        onDownloaded: () -> Unit,
        onConflict: () -> Unit,
        onConflictUploaded: () -> Unit,
    ) {
        val stagedFile = VaultWriteCoordinator.write(vault) {
            SyncLocalStore.createStagingFile(root)
        }
        try {
            val download = s3Client.downloadToFile(
                config = endpointConfig,
                objectKey = remote.objectKey,
                destinationFile = stagedFile,
                expectedEtag = remote.etag,
            )
            val commitResult = VaultWriteCoordinator.write(vault) {
                commit(stagedFile, download.sha256)
            }
            handleCommitResult(
                vault = vault,
                targetKey = targetKey,
                endpointConfig = endpointConfig,
                prefix = prefix,
                path = path,
                remote = remote,
                downloadSha256 = download.sha256,
                downloadEtag = download.etag,
                downloadSizeBytes = download.sizeBytes,
                downloadLastModifiedMillis = download.lastModifiedMillis,
                commitResult = commitResult,
                markLocalChanged = markLocalChanged,
                onDownloaded = onDownloaded,
                onConflict = onConflict,
                onConflictUploaded = onConflictUploaded,
            )
        } finally {
            withContext(NonCancellable) {
                runCatching { Files.deleteIfExists(stagedFile.toPath()) }
            }
        }
    }

    private suspend fun handleCommitResult(
        vault: Vault,
        targetKey: String,
        endpointConfig: S3EndpointConfig,
        prefix: RemotePrefix,
        path: SyncRelativePath,
        remote: RemoteObjectSnapshot,
        downloadSha256: String,
        downloadEtag: String,
        downloadSizeBytes: Long,
        downloadLastModifiedMillis: Long,
        commitResult: LocalCommitResult,
        markLocalChanged: (SyncRelativePath) -> Unit,
        onDownloaded: () -> Unit,
        onConflict: () -> Unit,
        onConflictUploaded: () -> Unit,
    ) {
        val effectiveEtag = downloadEtag.ifBlank { remote.etag }
        val effectiveRemoteModified = if (downloadLastModifiedMillis > 0L) downloadLastModifiedMillis else remote.lastModifiedMillis
        when (commitResult) {
            is LocalCommitResult.Committed -> {
                markLocalChanged(path)
                onDownloaded()
                if (effectiveEtag.isNotBlank()) {
                    saveBaseline(
                        vaultId = vault.id,
                        targetKey = targetKey,
                        path = path,
                        localSha256 = downloadSha256,
                        localSizeBytes = commitResult.sizeBytes,
                        localModifiedAtMillis = commitResult.modifiedAtMillis,
                        remoteEtag = effectiveEtag,
                        remoteSizeBytes = downloadSizeBytes,
                        remoteModifiedAtMillis = effectiveRemoteModified,
                    )
                }
            }
            is LocalCommitResult.AlreadyIdentical -> {
                if (effectiveEtag.isNotBlank()) {
                    saveBaseline(
                        vaultId = vault.id,
                        targetKey = targetKey,
                        path = path,
                        localSha256 = downloadSha256,
                        localSizeBytes = commitResult.sizeBytes,
                        localModifiedAtMillis = commitResult.modifiedAtMillis,
                        remoteEtag = effectiveEtag,
                        remoteSizeBytes = downloadSizeBytes,
                        remoteModifiedAtMillis = effectiveRemoteModified,
                    )
                }
            }
            is LocalCommitResult.CommittedWithConflictCopy -> {
                markLocalChanged(path)
                markLocalChanged(commitResult.conflictPath)
                onDownloaded()
                onConflict()
                if (effectiveEtag.isNotBlank()) {
                    saveBaseline(
                        vaultId = vault.id,
                        targetKey = targetKey,
                        path = path,
                        localSha256 = downloadSha256,
                        localSizeBytes = commitResult.sizeBytes,
                        localModifiedAtMillis = commitResult.modifiedAtMillis,
                        remoteEtag = effectiveEtag,
                        remoteSizeBytes = downloadSizeBytes,
                        remoteModifiedAtMillis = effectiveRemoteModified,
                    )
                }
                if (bestEffortUploadConflictCopy(
                        vaultId = vault.id,
                        targetKey = targetKey,
                        endpointConfig = endpointConfig,
                        prefix = prefix,
                        conflictPath = commitResult.conflictPath,
                        conflictFile = commitResult.conflictFile,
                    )
                ) {
                    onConflictUploaded()
                }
            }
        }
    }

    private suspend fun bestEffortUploadConflictCopy(
        vaultId: VaultId,
        targetKey: String,
        endpointConfig: S3EndpointConfig,
        prefix: RemotePrefix,
        conflictPath: SyncRelativePath,
        conflictFile: File,
    ): Boolean {
        val callerContext = coroutineContext
        return try {
            val digests = SyncLocalStore.computeFileDigests(conflictFile) { callerContext.ensureActive() }
            if (digests.sizeBytes > SyncFileFilter.MAX_SYNC_FILE_BYTES) return false
            val putResult = s3Client.uploadFile(
                config = endpointConfig,
                objectKey = prefix.toObjectKey(conflictPath),
                file = conflictFile,
                contentType = SyncFileFilter.inferContentType(conflictPath),
                condition = S3PutCondition.IfNotExists,
                mtimeMillis = digests.modifiedAtMillis,
            )
            val changedDuringUpload = !conflictFile.isFile ||
                conflictFile.length() != digests.sizeBytes ||
                conflictFile.lastModified() != digests.modifiedAtMillis ||
                putResult.sizeBytes != digests.sizeBytes ||
                !putResult.sha256.equals(digests.sha256, ignoreCase = true)
            val persistedModified = if (changedDuringUpload) 0L else digests.modifiedAtMillis
            if (putResult.etag.isNotBlank()) {
                saveBaseline(
                    vaultId = vaultId,
                    targetKey = targetKey,
                    path = conflictPath,
                    localSha256 = putResult.sha256,
                    localSizeBytes = putResult.sizeBytes,
                    localModifiedAtMillis = persistedModified,
                    remoteEtag = putResult.etag,
                    remoteSizeBytes = putResult.sizeBytes,
                    remoteModifiedAtMillis = System.currentTimeMillis(),
                )
            }
            true
        } catch (t: Throwable) {
            if (isFatalError(t)) throw t
            false
        }
    }

    private suspend fun saveBaseline(
        vaultId: VaultId,
        targetKey: String,
        path: SyncRelativePath,
        localSha256: String,
        localSizeBytes: Long,
        localModifiedAtMillis: Long,
        remoteEtag: String,
        remoteSizeBytes: Long,
        remoteModifiedAtMillis: Long,
    ) {
        syncStateDao.upsertEntry(
            SyncEntryEntity(
                vaultId = vaultId.value,
                targetKey = targetKey,
                path = path.value,
                localSha256 = localSha256,
                localSizeBytes = localSizeBytes,
                localModifiedAtMillis = localModifiedAtMillis,
                remoteEtag = remoteEtag,
                remoteSizeBytes = remoteSizeBytes,
                remoteModifiedAtMillis = remoteModifiedAtMillis,
                syncedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun SyncEntryEntity.toBaseline(): SyncBaseline = SyncBaseline(
        path = SyncRelativePath(path),
        localSha256 = localSha256,
        localSizeBytes = localSizeBytes,
        localModifiedAtMillis = localModifiedAtMillis,
        remoteEtag = remoteEtag,
        remoteSizeBytes = remoteSizeBytes,
        remoteModifiedAtMillis = remoteModifiedAtMillis,
        syncedAtMillis = syncedAtMillis,
    )

    private suspend fun executePlanItem(
        item: SyncPlanItem,
        vault: Vault,
        root: File,
        targetKey: String,
        endpointConfig: S3EndpointConfig,
        prefix: RemotePrefix,
        checkActive: () -> Unit,
        markLocalChanged: (SyncRelativePath) -> Unit,
        markLocalRemoved: (SyncRelativePath) -> Unit,
        onUploaded: () -> Unit,
        onDownloaded: () -> Unit,
        onDeletedLocal: () -> Unit,
        onDeletedRemote: () -> Unit,
        onConflict: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        when (item) {
            is SyncPlanItem.ClearBaseline -> {
                syncStateDao.deleteEntry(vault.id.value, targetKey, item.path.value)
            }
            is SyncPlanItem.RefreshBaselineStats -> {
                val sha256 = item.local.sha256 ?: item.baseline.localSha256
                saveBaseline(
                    vaultId = vault.id,
                    targetKey = targetKey,
                    path = item.path,
                    localSha256 = sha256,
                    localSizeBytes = item.local.sizeBytes,
                    localModifiedAtMillis = item.local.modifiedAtMillis,
                    remoteEtag = item.baseline.remoteEtag,
                    remoteSizeBytes = item.baseline.remoteSizeBytes,
                    remoteModifiedAtMillis = item.baseline.remoteModifiedAtMillis,
                )
            }
            is SyncPlanItem.AdoptMatched -> {
                val sha256 = item.local.sha256 ?: SyncLocalStore.computeFileDigests(
                    SyncLocalStore.resolveSafeTarget(root, item.path),
                ) { checkActive() }.sha256
                saveBaseline(
                    vaultId = vault.id,
                    targetKey = targetKey,
                    path = item.path,
                    localSha256 = sha256,
                    localSizeBytes = item.local.sizeBytes,
                    localModifiedAtMillis = item.local.modifiedAtMillis,
                    remoteEtag = item.remote.etag,
                    remoteSizeBytes = item.remote.sizeBytes,
                    remoteModifiedAtMillis = item.remote.lastModifiedMillis,
                )
            }
            is SyncPlanItem.UploadNew, is SyncPlanItem.UploadUpdate -> {
                val localFile = SyncLocalStore.resolveSafeTarget(root, item.path)
                val beforeDigests = SyncLocalStore.computeFileDigests(localFile) { checkActive() }
                if (beforeDigests.sizeBytes > SyncFileFilter.MAX_SYNC_FILE_BYTES) {
                    throw S3Exception(
                        kind = S3ErrorKind.FileTooLarge,
                        message = "File exceeds max sync size (${SyncFileFilter.MAX_SYNC_FILE_BYTES} bytes)",
                    )
                }
                val condition = when (item) {
                    is SyncPlanItem.UploadNew -> S3PutCondition.IfNotExists
                    is SyncPlanItem.UploadUpdate -> S3PutCondition.IfMatch(item.expectedRemoteEtag)
                }
                val putResult = s3Client.uploadFile(
                    config = endpointConfig,
                    objectKey = prefix.toObjectKey(item.path),
                    file = localFile,
                    contentType = SyncFileFilter.inferContentType(item.path),
                    condition = condition,
                    mtimeMillis = beforeDigests.modifiedAtMillis,
                )
                val changedDuringUpload = !localFile.isFile ||
                    localFile.length() != beforeDigests.sizeBytes ||
                    localFile.lastModified() != beforeDigests.modifiedAtMillis ||
                    putResult.sizeBytes != beforeDigests.sizeBytes ||
                    !putResult.sha256.equals(beforeDigests.sha256, ignoreCase = true)
                val persistedModified = if (changedDuringUpload) 0L else beforeDigests.modifiedAtMillis
                onUploaded()
                if (putResult.etag.isNotBlank()) {
                    saveBaseline(
                        vaultId = vault.id,
                        targetKey = targetKey,
                        path = item.path,
                        localSha256 = putResult.sha256,
                        localSizeBytes = putResult.sizeBytes,
                        localModifiedAtMillis = persistedModified,
                        remoteEtag = putResult.etag,
                        remoteSizeBytes = putResult.sizeBytes,
                        remoteModifiedAtMillis = System.currentTimeMillis(),
                    )
                }
            }
            is SyncPlanItem.DownloadNew -> {
                downloadAndCommit(
                    vault = vault,
                    root = root,
                    targetKey = targetKey,
                    endpointConfig = endpointConfig,
                    prefix = prefix,
                    path = item.path,
                    remote = item.remote,
                    commit = { stagedFile, downloadedSha256 ->
                        SyncLocalStore.commitDownloadedNew(
                            root = root,
                            path = item.path,
                            stagedFile = stagedFile,
                            downloadedSha256 = downloadedSha256,
                        )
                    },
                    markLocalChanged = markLocalChanged,
                    onDownloaded = onDownloaded,
                    onConflict = onConflict,
                    onConflictUploaded = onUploaded,
                )
            }
            is SyncPlanItem.DownloadUpdate -> {
                downloadAndCommit(
                    vault = vault,
                    root = root,
                    targetKey = targetKey,
                    endpointConfig = endpointConfig,
                    prefix = prefix,
                    path = item.path,
                    remote = item.remote,
                    commit = { stagedFile, downloadedSha256 ->
                        SyncLocalStore.commitDownloadedUpdate(
                            root = root,
                            path = item.path,
                            stagedFile = stagedFile,
                            downloadedSha256 = downloadedSha256,
                            expectedLocalSha256 = item.expectedLocalSha256,
                        )
                    },
                    markLocalChanged = markLocalChanged,
                    onDownloaded = onDownloaded,
                    onConflict = onConflict,
                    onConflictUploaded = onUploaded,
                )
            }
            is SyncPlanItem.ResolveConflict -> {
                downloadAndCommit(
                    vault = vault,
                    root = root,
                    targetKey = targetKey,
                    endpointConfig = endpointConfig,
                    prefix = prefix,
                    path = item.path,
                    remote = item.remote,
                    commit = { stagedFile, downloadedSha256 ->
                        SyncLocalStore.commitConflictResolution(
                            root = root,
                            path = item.path,
                            stagedRemoteFile = stagedFile,
                            downloadedSha256 = downloadedSha256,
                        )
                    },
                    markLocalChanged = markLocalChanged,
                    onDownloaded = onDownloaded,
                    onConflict = onConflict,
                    onConflictUploaded = onUploaded,
                )
            }
            is SyncPlanItem.DeleteLocal -> {
                val deleteResult = VaultWriteCoordinator.write(vault) {
                    SyncLocalStore.deleteLocalIfUnchanged(
                        root = root,
                        path = item.path,
                        expectedLocalSha256 = item.expectedLocalSha256,
                    )
                }
                when (deleteResult) {
                    LocalDeleteResult.Deleted -> {
                        markLocalRemoved(item.path)
                        onDeletedLocal()
                        syncStateDao.deleteEntry(vault.id.value, targetKey, item.path.value)
                    }
                    LocalDeleteResult.AlreadyDeleted -> {
                        syncStateDao.deleteEntry(vault.id.value, targetKey, item.path.value)
                    }
                    LocalDeleteResult.LocalModifiedAbort -> {
                        onFailed("Local file modified before delete: ${item.path.value}")
                    }
                }
            }
            is SyncPlanItem.DeleteRemote -> {
                val localTarget = SyncLocalStore.resolveSafeTarget(root, item.path)
                val stillAbsent = !Files.exists(localTarget.toPath(), NOFOLLOW_LINKS)
                if (!stillAbsent) {
                    onFailed("Local file reappeared before remote delete: ${item.path.value}")
                } else {
                    s3Client.deleteObject(
                        config = endpointConfig,
                        objectKey = item.objectKey,
                        expectedEtag = item.expectedRemoteEtag,
                    )
                    onDeletedRemote()
                    syncStateDao.deleteEntry(vault.id.value, targetKey, item.path.value)
                }
            }
        }
    }

    private fun isFatalError(error: Throwable): Boolean = when (error) {
        is CancellationException,
        is SyncVaultChangedException,
        is SyncStalledRelocationException -> true
        is S3Exception -> error.kind == S3ErrorKind.InvalidConfig ||
            error.kind == S3ErrorKind.AuthFailed ||
            error.kind == S3ErrorKind.BucketNotFound ||
            error.kind == S3ErrorKind.ClockSkew
        else -> false
    }

    companion object {
        private const val MAX_CONSECUTIVE_TRANSIENT_ERRORS = 3
        private val TRANSIENT_S3_ERROR_KINDS = setOf(
            S3ErrorKind.Network,
            S3ErrorKind.RateLimited,
            S3ErrorKind.ServerError,
        )
    }
}
