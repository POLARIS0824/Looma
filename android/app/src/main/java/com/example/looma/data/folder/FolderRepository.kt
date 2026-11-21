package com.example.looma.data.folder

import com.example.looma.data.note.NoteRepository
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.model.VaultFolder

class FolderRepository(private val notes: NoteRepository) {
    suspend fun createFolder(parent: FolderPath, name: String): VaultFolder = notes.createFolder(parent, name)

    suspend fun renameFolder(path: FolderPath, newName: String): VaultFolder = notes.renameFolder(path, newName)

    suspend fun moveFolder(path: FolderPath, destination: FolderPath): VaultFolder = notes.moveFolder(path, destination)

    internal suspend fun folderNotePaths(path: FolderPath, vaultId: VaultId) = notes.folderNotePaths(path, vaultId)

    internal suspend fun deleteFolderWithOutcome(path: FolderPath, vaultId: VaultId, expected: List<NotePath>) =
        notes.deleteFolderWithOutcome(path, vaultId, expected)

    internal suspend fun deleteEmptyFolder(path: FolderPath, vaultId: VaultId) =
        notes.deleteEmptyFolder(path, vaultId)
}
