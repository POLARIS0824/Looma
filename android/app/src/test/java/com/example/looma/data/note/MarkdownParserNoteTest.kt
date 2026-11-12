package com.example.looma.data.note

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.VaultId
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParserNoteTest {
    private val parser = MarkdownParser()

    @Test
    fun `opening preserves the title outside frontmatter and tags throughout the note`() {
        val stored = storedNote(
            " --- \r\n# Frontmatter title\r\n#frontmatter\r\n --- \r\n# Note title\r\n[[Link]] #body",
        )
        val note = parser.note(stored)

        assertEquals("Note title", note.title)
        assertEquals(setOf("frontmatter", "body"), note.tags)
        assertEquals(parser.savedNote(stored).toNote(), note)
    }

    @Test
    fun `opening keeps the filename fallback when the first heading is blank`() {
        val stored = storedNote("#  \n# Later heading")

        assertEquals("Filename", parser.note(stored).title)
        assertEquals(parser.savedNote(stored).toNote(), parser.note(stored))
    }

    private fun storedNote(content: String): StoredMarkdown {
        val root = File("vault")
        val file = File(root, "Filename.md")
        return StoredMarkdown(
            id = NoteId(VaultId("vault"), NotePath(file.name)),
            content = content,
            modifiedAt = Instant.EPOCH,
            root = root,
            file = file,
            revision = NoteRevision.of(content.toByteArray()),
        )
    }
}
