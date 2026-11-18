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
    fun `opening preserves the title outside frontmatter and combines yaml and body tags`() {
        val stored = storedNote(
            "\uFEFF---\r\n# Frontmatter comment\r\ntags:\r\n  - frontmatter\r\n...\r\n# Note title\r\n[[Link]] #body",
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

    @Test
    fun `tags parse yaml variants and ignore code, links, anchors, and numeric tokens`() {
        val text = """
            ---
            tags: [
              work/android,
              "#mobile",
              12345
            ]
            meta:
              tags: ignored-nested
            ---
            # Title

            Real #inline-tag and #v2 here.

            ```c
            #include <stdio.h>
            #define MAX 10
            ```

            ~~~css
            color: #ff00aa;
            ~~~

            Inline `#not-a-tag` and https://example.com/#section and [link](#anchor) and [[Note (1)#sec]] and #123.
        """.trimIndent()

        val parsed = parser.parse(text, "Fallback")
        assertEquals(setOf("work/android", "mobile", "inline-tag", "v2"), parsed.tags)
        assertEquals(parsed.tags, parser.parseForIndex(text, "Fallback").tags)
        assertEquals(parsed.tags, parser.tags(text))
    }

    @Test
    fun `excerpt and checklist exclude fenced code blocks`() {
        val text = """
            # Doc

            Real summary line.

            ```markdown
            code inside fence
            - [ ] fake task
            ```

            ~~~
            tilde code line
            - [x] fake tilde task
            ~~~

            - [ ] real task
        """.trimIndent()

        val parsed = parser.parse(text, "Doc")
        assertEquals("Real summary line.", parsed.excerpt)
        assertEquals(listOf(MarkdownChecklistItem("real task", checked = false)), parsed.checklist)
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
