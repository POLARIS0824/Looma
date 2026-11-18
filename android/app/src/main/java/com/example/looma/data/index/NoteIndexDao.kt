package com.example.looma.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

internal data class NoteSignatureRow(
    val path: String,
    val signature: ByteArray?,
)

@Dao
internal interface NoteIndexDao {

    @Query("SELECT * FROM indexed_note WHERE vaultId = :vaultId")
    suspend fun notes(vaultId: String): List<IndexedNoteEntity>

    @Query("SELECT * FROM note_tag WHERE vaultId = :vaultId ORDER BY notePath, ordinal")
    suspend fun tags(vaultId: String): List<NoteTagEntity>

    @Query("SELECT * FROM note_link WHERE vaultId = :vaultId ORDER BY notePath, ordinal")
    suspend fun links(vaultId: String): List<NoteLinkEntity>

    @Query(
        """
        SELECT n.path AS path, s.signature AS signature
        FROM indexed_note n
        LEFT JOIN note_search_signature s
            ON n.vaultId = s.vaultId AND n.path = s.notePath
        WHERE n.vaultId = :vaultId
        """,
    )
    suspend fun signatures(vaultId: String): List<NoteSignatureRow>

    @Upsert
    suspend fun upsertNotes(notes: List<IndexedNoteEntity>)

    @Upsert
    suspend fun upsertSignatures(signatures: List<NoteSearchSignatureEntity>)

    @Query("DELETE FROM note_search_signature WHERE vaultId = :vaultId AND notePath IN (:paths)")
    suspend fun deleteSignatures(vaultId: String, paths: List<String>)

    @Query("DELETE FROM note_tag WHERE vaultId = :vaultId AND notePath IN (:paths)")
    suspend fun deleteTags(vaultId: String, paths: List<String>)

    @Query("DELETE FROM note_link WHERE vaultId = :vaultId AND notePath IN (:paths)")
    suspend fun deleteLinks(vaultId: String, paths: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<NoteTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<NoteLinkEntity>)

    @Query("DELETE FROM indexed_note WHERE vaultId = :vaultId AND path IN (:paths)")
    suspend fun deleteNotes(vaultId: String, paths: List<String>)
}
