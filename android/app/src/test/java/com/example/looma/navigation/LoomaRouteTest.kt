package com.example.looma.navigation

import androidx.navigation3.runtime.NavKey
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import org.junit.Assert.assertEquals
import org.junit.Test

class LoomaRouteTest {
    @Test
    fun `renaming a note retargets every visit in its vault without changing visit identity`() {
        val original = NoteId(VaultId("vault"), NotePath("folder/A.md"))
        val renamed = original.copy(path = NotePath("folder/Renamed.md"))
        val firstVisit = LoomaRoute.Note.from(original, isNew = true)
        val linkedNote = LoomaRoute.Note.from(original.copy(path = NotePath("other/A.md")))
        val secondVisit = LoomaRoute.Note.from(original)
        val otherVault = LoomaRoute.Note.from(original.copy(vaultId = VaultId("other")))
        val stack = mutableListOf<NavKey>(LoomaRoute.Home, firstVisit, linkedNote, secondVisit, otherVault)

        stack.retargetNoteVisits(original, renamed, secondVisit.visitId)

        assertEquals(
            listOf(
                LoomaRoute.Home,
                firstVisit.copy(relativePath = "folder/Renamed.md", isNew = false, retargetVersion = 1),
                linkedNote,
                secondVisit.copy(relativePath = "folder/Renamed.md"),
                otherVault,
            ),
            stack,
        )
    }
}
