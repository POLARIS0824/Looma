package com.example.looma.feature.home

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteProperties
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.VaultId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePropertiesTest {
    @Test
    fun `only the current properties request can open the dialog or show an error`() {
        val oldRequest = HomeFileDialog.LoadingProperties(id)
        val currentRequest = HomeFileDialog.LoadingProperties(id)
        val state = selected.copy(dialog = currentRequest)

        assertEquals(state, state.finishProperties(oldRequest, properties))
        assertEquals(state, state.finishProperties(oldRequest, null))
        assertEquals(HomeFileDialog.Properties(properties), state.finishProperties(currentRequest, properties).dialog)
        assertEquals(HomeNotice.ActionFailed, state.finishProperties(currentRequest, null).message?.notice)
    }

    @Test
    fun `a result does not reopen properties after the dialog is dismissed or selection is lost`() {
        val request = HomeFileDialog.LoadingProperties(id)

        assertNull(selected.finishProperties(request, properties).dialog)
        val cleared = selected.copy(dialog = request, selection = HomeSelection.None)
        assertNull(cleared.finishProperties(request, properties).dialog)
        assertNull(cleared.finishProperties(request, null).message)
    }

    private val id = NoteId(VaultId("vault"), NotePath("A.md"))
    private val properties = NoteProperties(id.path, 10, Instant.EPOCH, 2, 8, 0, 0)
    private val note = HomeNoteItem(NoteSummary(id, "A", Instant.EPOCH))
    private val selected = HomeUiState(
        vaultId = id.vaultId,
        notes = listOf(note),
        selection = HomeSelection.Notes.of(setOf(note.listKey)),
    )
}
