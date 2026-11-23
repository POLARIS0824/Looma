package com.example.looma.domain.sync

@JvmInline
value class SyncRelativePath(val value: String) {
    companion object {
        fun parseOrNull(raw: String): SyncRelativePath? =
            if (SyncFileFilter.isValidSyncFile(raw)) SyncRelativePath(raw) else null
    }
}

@JvmInline
value class RemotePrefix(val value: String) {
    fun toObjectKey(path: SyncRelativePath): String = value + path.value

    fun fromObjectKey(objectKey: String): SyncRelativePath? {
        if (!objectKey.startsWith(value)) return null
        val relative = objectKey.removePrefix(value)
        if (relative.isEmpty() || relative.endsWith('/')) return null
        return SyncRelativePath.parseOrNull(relative)
    }

    fun isUnderHiddenDirectory(objectKey: String): Boolean {
        if (!objectKey.startsWith(value)) return false
        val relative = objectKey.removePrefix(value)
        if (relative.isEmpty()) return false
        return relative.split('/').any { it.startsWith('.') }
    }

    companion object {
        private val MULTIPLE_SLASHES = Regex("/+")

        fun normalizeOrNull(raw: String): RemotePrefix? {
            if (raw.any { it.isISOControl() }) return null
            val cleaned = raw.trim()
                .replace('\\', '/')
                .replace(MULTIPLE_SLASHES, "/")
                .trim('/')
            if (cleaned.isEmpty()) return RemotePrefix("")
            if (cleaned.split('/').any { it == ".." }) return null
            return RemotePrefix("$cleaned/")
        }
    }
}

data class LocalFileSnapshot(
    val path: SyncRelativePath,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val sha256: String? = null,
    val md5: String? = null,
)

data class RemoteObjectSnapshot(
    val path: SyncRelativePath,
    val objectKey: String,
    val etag: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
) {
    val isPlainMd5Etag: Boolean
        get() = etag.length == 32 && etag.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

data class SyncBaseline(
    val path: SyncRelativePath,
    val localSha256: String,
    val localSizeBytes: Long,
    val localModifiedAtMillis: Long,
    val remoteEtag: String,
    val remoteSizeBytes: Long,
    val remoteModifiedAtMillis: Long,
    val syncedAtMillis: Long,
)

enum class SyncSkipReason {
    InvalidPath,
    CaseCollision,
    FileTooLarge,
}

data class SyncSkippedItem(
    val rawPath: String,
    val reason: SyncSkipReason,
)

sealed interface SyncPlanItem {
    val path: SyncRelativePath

    data class UploadNew(
        override val path: SyncRelativePath,
        val sizeBytes: Long,
    ) : SyncPlanItem

    data class UploadUpdate(
        override val path: SyncRelativePath,
        val sizeBytes: Long,
        val expectedRemoteEtag: String,
    ) : SyncPlanItem

    data class DownloadNew(
        override val path: SyncRelativePath,
        val remote: RemoteObjectSnapshot,
    ) : SyncPlanItem

    data class DownloadUpdate(
        override val path: SyncRelativePath,
        val remote: RemoteObjectSnapshot,
        val expectedLocalSha256: String,
    ) : SyncPlanItem

    data class DeleteLocal(
        override val path: SyncRelativePath,
        val expectedLocalSha256: String,
    ) : SyncPlanItem

    data class DeleteRemote(
        override val path: SyncRelativePath,
        val objectKey: String,
        val expectedRemoteEtag: String,
    ) : SyncPlanItem

    data class AdoptMatched(
        override val path: SyncRelativePath,
        val local: LocalFileSnapshot,
        val remote: RemoteObjectSnapshot,
    ) : SyncPlanItem

    data class ResolveConflict(
        override val path: SyncRelativePath,
        val local: LocalFileSnapshot,
        val remote: RemoteObjectSnapshot,
        val baseline: SyncBaseline?,
    ) : SyncPlanItem

    data class ClearBaseline(
        override val path: SyncRelativePath,
    ) : SyncPlanItem

    data class RefreshBaselineStats(
        override val path: SyncRelativePath,
        val local: LocalFileSnapshot,
        val baseline: SyncBaseline,
    ) : SyncPlanItem
}

data class SyncPlan(
    val isInitialSync: Boolean,
    val items: List<SyncPlanItem>,
    val skipped: List<SyncSkippedItem>,
    val unchangedCount: Int,
) {
    val uploadCount: Int
        get() = items.count { it is SyncPlanItem.UploadNew || it is SyncPlanItem.UploadUpdate }

    val downloadCount: Int
        get() = items.count { it is SyncPlanItem.DownloadNew || it is SyncPlanItem.DownloadUpdate }

    val deleteLocalCount: Int
        get() = items.count { it is SyncPlanItem.DeleteLocal }

    val deleteRemoteCount: Int
        get() = items.count { it is SyncPlanItem.DeleteRemote }

    val conflictCount: Int
        get() = items.count { it is SyncPlanItem.ResolveConflict }

    val matchedCount: Int
        get() = items.count { it is SyncPlanItem.AdoptMatched }

    val totalUploadBytes: Long
        get() = items.sumOf {
            when (it) {
                is SyncPlanItem.UploadNew -> it.sizeBytes
                is SyncPlanItem.UploadUpdate -> it.sizeBytes
                else -> 0L
            }
        }

    val totalDownloadBytes: Long
        get() = items.sumOf {
            when (it) {
                is SyncPlanItem.DownloadNew -> it.remote.sizeBytes
                is SyncPlanItem.DownloadUpdate -> it.remote.sizeBytes
                else -> 0L
            }
        }
}

enum class SyncRunOutcome {
    SUCCEEDED,
    SUCCEEDED_WITH_CONFLICTS,
    PARTIAL,
    FAILED,
    CANCELLED,
}

enum class SyncPhase {
    SCANNING_LOCAL,
    LISTING_REMOTE,
    SYNCING,
    UPDATING_INDEX,
}
