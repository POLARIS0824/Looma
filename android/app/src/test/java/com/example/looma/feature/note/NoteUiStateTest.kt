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
    fun `a new note nobody wrote in is discarded when the setting is off`() {
        assertTrue(
            NoteUiState(content = "", isNewNote = true, keepEmptyNotes = false).willDiscardEmpty,
        )
    }

    @Test
    fun `a title on its own is content and is kept`() {
        assertFalse(
            NoteUiState(
                content = "# 周会\n\n",
                isNewNote = true,
                keepEmptyNotes = false,
            ).willDiscardEmpty,
        )
    }

    @Test
    fun `a body on its own is content and is kept`() {
        assertFalse(
            NoteUiState(
                content = "随手记一句",
                isNewNote = true,
                keepEmptyNotes = false,
            ).willDiscardEmpty,
        )
    }

    @Test
    fun `an existing blank note is never discarded`() {
        assertFalse(
            NoteUiState(content = "", isNewNote = false, keepEmptyNotes = false).willDiscardEmpty,
        )
    }

    @Test
    fun `the setting keeps a blank new note`() {
        assertFalse(
            NoteUiState(content = "", isNewNote = true, keepEmptyNotes = true).willDiscardEmpty,
        )
    }

    @Test
    fun `a conflict keeps the file on disk`() {
        assertFalse(
            NoteUiState(
                content = "",
                isNewNote = true,
                keepEmptyNotes = false,
                saveProblem = NoteSaveProblem.Conflict(revision("external")),
            ).willDiscardEmpty,
        )
    }

    @Test
    fun `a clean note adopts a different revision from disk`() {
        val state = NoteUiState(
            content = "旧内容",
            baseline = NoteBaseline("旧内容", revision("a")),
            isLoading = false,
        )

        assertTrue(state.canAdoptDiskRevision(revision("b")))
        assertFalse(state.canAdoptDiskRevision(revision("a")))
    }

    @Test
    fun `local edits are never replaced by the disk version`() {
        val state = NoteUiState(
            content = "新内容",
            baseline = NoteBaseline("旧内容", revision("a")),
            isLoading = false,
        )

        assertFalse(state.canAdoptDiskRevision(revision("b")))
    }

    @Test
    fun `save problems and completed leave attempts keep the editor buffer`() {
        val base = NoteUiState(
            content = "内容",
            baseline = NoteBaseline("内容", revision("a")),
            isLoading = false,
        )

        val diskRevision = revision("b")
        assertFalse(base.afterSaveFailure(diskRevision).whenSaveStarts().canAdoptDiskRevision(diskRevision))
        assertFalse(base.afterSaveFailure().canAdoptDiskRevision(diskRevision))
        assertFalse(base.copy(leave = NoteLeave.Blocked).canAdoptDiskRevision(diskRevision))
        assertFalse(base.copy(leave = NoteLeave.Ready).canAdoptDiskRevision(diskRevision))
    }

    @Test
    fun `editing clears a write failure and preserves a conflict`() {
        val failed = renameBase.afterSaveFailure()
        assertTrue(failed.showSaveBanner)
        assertFalse(failed.afterEdit("draft").showSaveBanner)

        val conflict = renameBase.afterSaveFailure(revision("external"))
        val editedConflict = conflict.afterEdit("draft")
        assertEquals(conflict.saveProblem, editedConflict.saveProblem)
        assertTrue(editedConflict.showSaveBanner)
    }

    @Test
    fun `an overwrite hides the banner and a write failure restores the current conflict`() {
        val externalRevision = revision("external")
        val conflict = renameBase.afterSaveFailure(externalRevision)
        val saving = conflict.whenSaveStarts().afterEdit("draft")

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
        val written = "written content"
        val writtenRevision = revision(written)
        val laterDraft = "edited during the write"
        val saving = renameBase.afterEdit(written).whenSaveStarts()
        val saved = saving.afterEdit(laterDraft).afterSave(written, writtenRevision)

        assertEquals(laterDraft, saved.content)
        assertEquals(NoteBaseline(written, writtenRevision), saved.baseline)
        assertTrue(saved.isDirty)
        assertFalse(saved.showSaveBanner)
        assertFalse(saved.afterSave(laterDraft, revision(laterDraft)).isDirty)
    }

    @Test
    fun `rename adopts the rewritten note as the editor and saved baseline`() {
        val renamed = note("[[Myself]]")
        val state = renameBase.afterRename(renamed, editorContent = "[[Self]]")

        assertEquals(renamed.content, state.content)
        assertEquals(NoteBaseline(renamed.content, renamed.revision), state.baseline)
        assertFalse(state.isDirty)
    }

    @Test
    fun `an edit made during rename stays pending when the disk text is unchanged`() {
        val draft = "[[Self]]\nAdded while renaming"
        val state = renameBase.afterRename(note("[[Self]]"), editorContent = draft)

        assertEquals(draft, state.content)
        assertTrue(state.isDirty)
        assertFalse(state.isConflict)
    }

    @Test
    fun `an edit made during a self-link rewrite keeps both versions and requires resolution`() {
        val renamed = note("[[Myself]]")
        val draft = "[[Self]]\nAdded while renaming"
        val state = renameBase.afterRename(renamed, editorContent = draft)

        assertEquals(draft, state.content)
        assertEquals(NoteBaseline(renamed.content, renamed.revision), state.baseline)
        assertEquals(NoteSaveProblem.Conflict(renamed.revision), state.saveProblem)
        assertTrue(state.isDirty)
        assertTrue(state.isConflict)
        assertTrue(state.showSaveBanner)
    }

    private val renameBase = NoteUiState(
        content = "[[Self]]",
        baseline = NoteBaseline("[[Self]]", revision("[[Self]]")),
        isLoading = false,
    )

    private fun note(content: String) = Note(
        id = NoteId(VaultId("vault"), NotePath("Myself.md")),
        title = "Myself",
        content = content,
        modifiedAt = Instant.EPOCH,
    )

    private fun revision(seed: String): NoteRevision =
        NoteRevision.of(seed.toByteArray(Charsets.UTF_8))
}
