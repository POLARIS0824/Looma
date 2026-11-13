package com.example.looma.navigation

import androidx.navigation3.runtime.NavKey
import com.example.looma.domain.model.NoteId
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
sealed interface LoomaRoute : NavKey {
    @Serializable
    data object VaultSetup : LoomaRoute

    @Serializable
    data object Home : LoomaRoute

    @Serializable
    data object Trash : LoomaRoute

    @Serializable
    data object Settings : LoomaRoute

    @Serializable
    data class Note(
        val vaultId: String,
        val relativePath: String,
        val isNew: Boolean = false,
        val visitId: String = UUID.randomUUID().toString(),
        val retargetVersion: Long = 0,
    ) : LoomaRoute {
        companion object {
            fun from(id: NoteId, isNew: Boolean = false): Note = Note(
                vaultId = id.vaultId.value,
                relativePath = id.path.relativePath,
                isNew = isNew,
            )
        }
    }
}

internal fun MutableList<NavKey>.retargetNoteVisits(from: NoteId, to: NoteId, originatingVisitId: String) {
    if (from == to) return
    for (index in indices) {
        val entry = this[index] as? LoomaRoute.Note ?: continue
        if (entry.vaultId == from.vaultId.value && entry.relativePath == from.path.relativePath) {
            this[index] = entry.copy(
                vaultId = to.vaultId.value,
                relativePath = to.path.relativePath,
                isNew = false,
                retargetVersion = if (entry.visitId == originatingVisitId) {
                    entry.retargetVersion
                } else {
                    entry.retargetVersion + 1
                },
            )
        }
    }
}
