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
                isConflict = true,
            ).willDiscardEmpty,
        )
    }

    @Test
    fun `a clean note adopts a different revision from disk`() {
        val state = NoteUiState(
            content = "旧内容",
            savedContent = "旧内容",
            isLoading = false,
            revision = revision("a"),
        )

        assertTrue(state.canAdoptDiskRevision(revision("b")))
        assertFalse(state.canAdoptDiskRevision(revision("a")))
    }

    @Test
    fun `local edits are never replaced by the disk version`() {
        val state = NoteUiState(
            content = "新内容",
            savedContent = "旧内容",
            isLoading = false,
            revision = revision("a"),
        )

        assertFalse(state.canAdoptDiskRevision(revision("b")))
    }

    @Test
    fun `a conflict or pending save keeps the editor buffer`() {
        val base = NoteUiState(
            content = "内容",
            savedContent = "内容",
            isLoading = false,
            revision = revision("a"),
        )

        assertFalse(base.copy(isConflict = true).canAdoptDiskRevision(revision("b")))
        assertFalse(base.copy(saveFailed = true).canAdoptDiskRevision(revision("b")))
        assertFalse(base.copy(saveState = NoteSaveState.Saving).canAdoptDiskRevision(revision("b")))
    }

    @Test
    fun `rename adopts the rewritten note as the editor and saved baseline`() {
        val renamed = note("[[Myself]]")
        val state = renameBase.afterRename(renamed, editorContent = "[[Self]]")

        assertEquals(renamed.content, state.content)
        assertEquals(renamed.content, state.savedContent)
        assertEquals(renamed.revision, state.revision)
        assertFalse(state.isDirty)
    }

    @Test
    fun `an edit made during rename stays pending when the disk text is unchanged`() {
        val draft = "[[Self]]\nAdded while renaming"
        val state = renameBase.afterRename(note("[[Self]]"), editorContent = draft)

        assertEquals(draft, state.content)
        assertEquals(NoteSaveState.Pending, state.saveState)
        assertTrue(state.isDirty)
        assertFalse(state.isConflict)
    }

    @Test
    fun `an edit made during a self-link rewrite keeps both versions and requires resolution`() {
        val renamed = note("[[Myself]]")
        val draft = "[[Self]]\nAdded while renaming"
        val state = renameBase.afterRename(renamed, editorContent = draft)

        assertEquals(draft, state.content)
        assertEquals(renamed.content, state.savedContent)
        assertEquals(renamed.revision, state.conflictRevision)
        assertTrue(state.isDirty)
        assertTrue(state.isConflict)
        assertTrue(state.saveFailed)
    }

    private val renameBase = NoteUiState(
        content = "[[Self]]",
        savedContent = "[[Self]]",
        revision = revision("[[Self]]"),
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
