package com.example.looma.data.sync

import androidx.room.Entity

@Entity(tableName = "sync_entry", primaryKeys = ["vaultId", "targetKey", "path"])
internal data class SyncEntryEntity(
    val vaultId: String,
    val targetKey: String,
    val path: String,
    val localSha256: String,
    val localSizeBytes: Long,
    val localModifiedAtMillis: Long,
    val remoteEtag: String,
    val remoteSizeBytes: Long,
    val remoteModifiedAtMillis: Long,
    val syncedAtMillis: Long,
)

@Entity(tableName = "sync_vault_state", primaryKeys = ["vaultId", "targetKey"])
internal data class SyncVaultStateEntity(
    val vaultId: String,
    val targetKey: String,
    val hasCompletedInitialSync: Boolean,
    val lastSyncAtMillis: Long?,
    val lastOutcome: String?,
    val lastUploaded: Int,
    val lastDownloaded: Int,
    val lastDeletedLocal: Int,
    val lastDeletedRemote: Int,
    val lastConflicts: Int,
    val lastFailed: Int,
    val lastSkipped: Int,
    val lastErrorMessage: String?,
)
