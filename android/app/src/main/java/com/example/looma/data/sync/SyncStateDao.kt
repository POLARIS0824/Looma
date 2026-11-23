package com.example.looma.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SyncStateDao {

    @Query("SELECT * FROM sync_entry WHERE vaultId = :vaultId AND targetKey = :targetKey")
    suspend fun entriesFor(vaultId: String, targetKey: String): List<SyncEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: SyncEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<SyncEntryEntity>)

    @Query("DELETE FROM sync_entry WHERE vaultId = :vaultId AND targetKey = :targetKey AND path = :path")
    suspend fun deleteEntry(vaultId: String, targetKey: String, path: String)

    @Query("SELECT * FROM sync_vault_state WHERE vaultId = :vaultId AND targetKey = :targetKey")
    fun observeVaultState(vaultId: String, targetKey: String): Flow<SyncVaultStateEntity?>

    @Query("SELECT * FROM sync_vault_state WHERE vaultId = :vaultId AND targetKey = :targetKey")
    suspend fun getVaultState(vaultId: String, targetKey: String): SyncVaultStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVaultState(state: SyncVaultStateEntity)
}
