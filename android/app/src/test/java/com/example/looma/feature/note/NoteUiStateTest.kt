package com.example.looma.feature.note

import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.VaultId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NoteUiStateTest {

    @Test
    fun `blank notes are discarded only when new and the setting is off`() {
        val newNote = NoteUiState(isNewNote = true, keepEmptyNotes = false)
        assertTrue(newNote.shouldDiscardEmpty(""))
        assertFalse(newNote.shouldDiscardEmpty("# 周会\n\n"))
        assertFalse(newNote.shouldDiscardEmpty("随手记一句"))
        assertFalse(newNote.copy(isNewNote = false).shouldDiscardEmpty(""))
        assertFalse(newNote.copy(keepEmptyNotes = true).shouldDiscardEmpty(""))
        assertFalse(newNote.afterSaveFailure(revision("external")).shouldDiscardEmpty(""))
    }

    @Test
    fun `a draft becomes dirty as soon as the editor changes`() {
        val draft = loadedDraft()
        assertTrue(ready.canRefreshDraft(draft.isDirty))

        draft.editor.append("Local edit")

        assertFalse(ready.canRefreshDraft(draft.isDirty))
        assertTrue(draft.content.endsWith("Local edit"))
    }

    @Test
    fun `save problems and completed leave attempts keep the editor buffer`() {
        assertFalse(ready.afterSaveFailure(revision("external")).whenSaveStarts().canRefreshDraft(false))
        assertFalse(ready.afterSaveFailure().canRefreshDraft(false))
        assertFalse(ready.copy(leave = NoteLeave.Blocked).canRefreshDraft(false))
        assertFalse(ready.copy(leave = NoteLeave.Ready).canRefreshDraft(false))
    }

    @Test
    fun `editing clears a write failure and preserves a conflict`() {
        val failed = ready.afterSaveFailure()
        assertTrue(failed.showSaveBanner)
        assertFalse(failed.afterEditorEdit().showSaveBanner)

        val conflict = ready.afterSaveFailure(revision("external"))
        val editedConflict = conflict.afterEditorEdit()
        assertEquals(conflict.saveProblem, editedConflict.saveProblem)
        assertTrue(editedConflict.showSaveBanner)
    }

    @Test
    fun `an overwrite hides the banner and a write failure restores the current conflict`() {
        val externalRevision = revision("external")
        val conflict = ready.afterSaveFailure(externalRevision)
        val saving = conflict.whenSaveStarts().afterEditorEdit()

        assertTrue(saving.isConflict)
        assertFalse(saving.showSaveBanner)
        val failed = saving.afterSaveFailure()
        assertEquals(NoteSaveProblem.Conflict(externalRevision), failed.saveProblem)
        assertTrue(failed.showSaveBanner)

        val newerRevision = revision("newer external")
        assertEquals(NoteSaveProblem.Conflict(newerRevision), saving.afterSaveFailure(newerRevision).saveProblem)
    }

    @Test
    fun `completing an older write keeps the later draft dirty against the saved request`() {
        val draft = loadedDraft()
        val written = draft.content
        val laterDraft = "edited during the write"
        draft.editor.replaceRange(0, draft.editor.length, laterDraft)
        val savedRevision = revision(written)
        draft.markWritten(written, savedRevision)

        assertEquals(laterDraft, draft.content)
        assertEquals(savedRevision, draft.revision)
        assertTrue(draft.isDirty)

        draft.markWritten(laterDraft, revision(laterDraft))
        assertFalse(draft.isDirty)
    }

    @Test
    fun `rename adopts the rewritten note as the editor and saved baseline`() {
        val draft = loadedDraft()
        val renamed = note("[[Myself]]")

        assertEquals(null, draft.rebaseAfterRename(renamed))
        assertEquals(renamed.content, draft.content)
        assertEquals(renamed.revision, draft.revision)
        assertFalse(draft.isDirty)
    }

    @Test
    fun `an edit made during rename stays pending when the disk text is unchanged`() {
        val draft = loadedDraft()
        draft.editor.append("\nAdded while renaming")

        assertEquals(null, draft.rebaseAfterRename(note("[[Self]]")))
        assertEquals("[[Self]]\nAdded while renaming", draft.content)
        assertTrue(draft.isDirty)
    }

    @Test
    fun `an edit made during a self-link rewrite keeps both versions and requires resolution`() {
        val renamed = note("[[Myself]]")
        val draft = loadedDraft()
        draft.editor.append("\nAdded while renaming")

        assertEquals(NoteSaveProblem.Conflict(renamed.revision), draft.rebaseAfterRename(renamed))
        assertEquals("[[Self]]\nAdded while renaming", draft.content)
        assertEquals(renamed.revision, draft.revision)
        assertTrue(draft.isDirty)
    }

    private val ready = NoteUiState(isLoading = false)

    private fun loadedDraft() = NoteDraft().apply { replaceFromDisk(note("[[Self]]")) }

    private fun note(content: String) = Note(
        id = NoteId(VaultId("vault"), NotePath("Myself.md")),
        title = "Myself",
        content = content,
        modifiedAt = Instant.EPOCH,
        revision = revision(content),
    )

    private fun revision(seed: String): NoteRevision =
        NoteRevision.of(seed.toByteArray(Charsets.UTF_8))
}
