package com.example.looma.data.note

import com.example.looma.data.index.toIndexedNote
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.VaultId
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SavedNoteTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `one parse feeds the note and the index row`() {
        val root = temporaryFolder.newFolder("vault")
        val file = File(root, "note.md").apply {
            writeText("# 标题\n\n#项目\n\n正文 [[另一篇]]")
        }
        val bytes = file.readBytes()
        val stored = StoredMarkdown(
            id = NoteId(VaultId("vault"), NotePath("note.md")),
            content = bytes.toString(Charsets.UTF_8),
            modifiedAt = Instant.ofEpochMilli(file.lastModified()),
            root = root,
            file = file,
            revision = NoteRevision.of(bytes),
        )

        val saved = MarkdownParser().savedNote(stored)
        val note = saved.toNote()
        val indexed = saved.toIndexedNote()

        assertEquals(note.id, indexed.id)
        assertEquals(note.title, indexed.title)
        assertEquals(note.tags.toList(), indexed.tags)
        assertEquals(note.modifiedAt, indexed.modifiedAt)
        assertEquals(saved.stored.revision, note.revision)
    }
}
