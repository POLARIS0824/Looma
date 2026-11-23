package com.example.looma.domain.sync

import java.util.Locale

object SyncPlanner {
    const val RACILY_CLEAN_WINDOW_MILLIS = 2_000L

    fun needsLocalHash(
        local: LocalFileSnapshot,
        remote: RemoteObjectSnapshot?,
        baseline: SyncBaseline?,
    ): Boolean {
        if (baseline == null) {
            return remote != null && remote.isPlainMd5Etag && local.sizeBytes == remote.sizeBytes
        }
        if (local.sizeBytes != baseline.localSizeBytes) return true
        if (local.modifiedAtMillis != baseline.localModifiedAtMillis) return true
        if (baseline.syncedAtMillis - local.modifiedAtMillis < RACILY_CLEAN_WINDOW_MILLIS) return true
        return remote != null &&
            remote.etag != baseline.remoteEtag &&
            remote.isPlainMd5Etag &&
            local.sizeBytes == remote.sizeBytes
    }

    fun plan(
        isInitialSync: Boolean,
        localFiles: Map<SyncRelativePath, LocalFileSnapshot>,
        remoteObjects: Map<SyncRelativePath, RemoteObjectSnapshot>,
        baselines: Map<SyncRelativePath, SyncBaseline>,
        extraSkipped: List<SyncSkippedItem> = emptyList(),
    ): SyncPlan {
        val activePaths = LinkedHashSet<SyncRelativePath>(localFiles.size + remoteObjects.size).apply {
            addAll(localFiles.keys)
            addAll(remoteObjects.keys)
        }
        val collisionGroups = activePaths.groupBy { it.value.lowercase(Locale.ROOT) }
        val collidedPaths = HashSet<SyncRelativePath>()
        val collisionSkipped = mutableListOf<SyncSkippedItem>()
        for (paths in collisionGroups.values) {
            if (paths.size > 1) {
                for (path in paths) {
                    collidedPaths += path
                    collisionSkipped += SyncSkippedItem(path.value, SyncSkipReason.CaseCollision)
                }
            }
        }

        val allPaths = (localFiles.keys + remoteObjects.keys + baselines.keys)
            .asSequence()
            .filter { it !in collidedPaths }
            .distinct()
            .sortedBy { it.value }
            .toList()

        val items = mutableListOf<SyncPlanItem>()
        var unchangedCount = 0

        for (path in allPaths) {
            val local = localFiles[path]
            val remote = remoteObjects[path]
            val baseline = baselines[path]

            when {
                local == null && remote == null && baseline != null -> {
                    items += SyncPlanItem.ClearBaseline(path)
                }
                local != null && remote == null && baseline == null -> {
                    items += SyncPlanItem.UploadNew(path, local.sizeBytes)
                }
                local == null && remote != null && baseline == null -> {
                    items += SyncPlanItem.DownloadNew(path, remote)
                }
                local != null && remote != null && baseline == null -> {
                    items += if (hasIdenticalContent(local, remote)) {
                        SyncPlanItem.AdoptMatched(path, local, remote)
                    } else {
                        SyncPlanItem.ResolveConflict(path, local, remote, baseline = null)
                    }
                }
                local != null && remote == null && baseline != null -> {
                    items += if (isInitialSync || isLocalChanged(local, baseline)) {
                        SyncPlanItem.UploadNew(path, local.sizeBytes)
                    } else {
                        SyncPlanItem.DeleteLocal(path, expectedLocalSha256 = baseline.localSha256)
                    }
                }
                local == null && remote != null && baseline != null -> {
                    items += if (isInitialSync || isRemoteChanged(remote, baseline)) {
                        SyncPlanItem.DownloadNew(path, remote)
                    } else {
                        SyncPlanItem.DeleteRemote(
                            path = path,
                            objectKey = remote.objectKey,
                            expectedRemoteEtag = baseline.remoteEtag,
                        )
                    }
                }
                local != null && remote != null && baseline != null -> {
                    val localChanged = isLocalChanged(local, baseline)
                    val remoteChanged = isRemoteChanged(remote, baseline)
                    when {
                        !localChanged && !remoteChanged -> {
                            if (local.sizeBytes != baseline.localSizeBytes ||
                                local.modifiedAtMillis != baseline.localModifiedAtMillis
                            ) {
                                items += SyncPlanItem.RefreshBaselineStats(path, local, baseline)
                            } else {
                                unchangedCount++
                            }
                        }
                        localChanged && !remoteChanged -> {
                            items += if (hasIdenticalContent(local, remote)) {
                                SyncPlanItem.AdoptMatched(path, local, remote)
                            } else {
                                SyncPlanItem.UploadUpdate(
                                    path = path,
                                    sizeBytes = local.sizeBytes,
                                    expectedRemoteEtag = baseline.remoteEtag,
                                )
                            }
                        }
                        !localChanged && remoteChanged -> {
                            items += if (hasIdenticalContent(local, remote)) {
                                SyncPlanItem.AdoptMatched(path, local, remote)
                            } else {
                                SyncPlanItem.DownloadUpdate(
                                    path = path,
                                    remote = remote,
                                    expectedLocalSha256 = baseline.localSha256,
                                )
                            }
                        }
                        else -> {
                            items += if (hasIdenticalContent(local, remote)) {
                                SyncPlanItem.AdoptMatched(path, local, remote)
                            } else {
                                SyncPlanItem.ResolveConflict(path, local, remote, baseline)
                            }
                        }
                    }
                }
            }
        }

        val allSkipped = extraSkipped + collisionSkipped.sortedBy { it.rawPath }
        return SyncPlan(
            isInitialSync = isInitialSync,
            items = items,
            skipped = allSkipped,
            unchangedCount = unchangedCount,
        )
    }

    private fun isLocalChanged(local: LocalFileSnapshot, baseline: SyncBaseline): Boolean {
        if (local.sha256 != null) {
            return !local.sha256.equals(baseline.localSha256, ignoreCase = true)
        }
        if (local.sizeBytes != baseline.localSizeBytes) return true
        if (local.modifiedAtMillis != baseline.localModifiedAtMillis) return true
        return baseline.syncedAtMillis - local.modifiedAtMillis < RACILY_CLEAN_WINDOW_MILLIS
    }

    private fun isRemoteChanged(remote: RemoteObjectSnapshot, baseline: SyncBaseline): Boolean =
        remote.etag != baseline.remoteEtag || remote.sizeBytes != baseline.remoteSizeBytes

    private fun hasIdenticalContent(local: LocalFileSnapshot, remote: RemoteObjectSnapshot): Boolean =
        remote.isPlainMd5Etag &&
            local.sizeBytes == remote.sizeBytes &&
            local.md5 != null &&
            local.md5.equals(remote.etag, ignoreCase = true)
}
