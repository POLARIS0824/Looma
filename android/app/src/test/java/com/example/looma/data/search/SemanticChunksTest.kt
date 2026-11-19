package com.example.looma.data.search

import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.search.PassageAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticChunksTest {
    @Test
    fun sectionsKeepExactOffsetsIntoOriginalMarkdown() {
        val source = "---\r\ntags: [metadata-only]\r\n---\r\n" +
            "# Overview\r\n\r\n第一段 😀\r\n\r\n" +
            "## Repair\r\n\r\nReplace the seal.\r\n\r\n" +
            "# Scheduling\r\n\r\nBook an appointment.\r\n"

        val chunks = semanticChunks(source)

        assertEquals(listOf("Overview", "Overview / Repair", "Scheduling"), chunks.map { it.section })
        assertEquals(
            listOf("第一段 😀", "Replace the seal.", "Book an appointment."),
            chunks.map { source.substring(it.start, it.end) },
        )
        assertEquals(source.indexOf("第一段"), chunks.first().start)
    }

    @Test
    fun splittingALongUnbrokenBlockPreservesSurrogatePairsAndCoverage() {
        val text = "a" + "🛶".repeat(550)
        val source = "# Route\n\n$text"
        val revision = NoteRevision.of(source.toByteArray()) as NoteRevision.Known
        val chunks = semanticChunks(source)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { PassageAnchor(revision.sha256, it.start, it.end).matches(revision, source) })
        assertEquals(text, chunks.joinToString("") { source.substring(it.start, it.end) })
    }

    @Test
    fun anchorsRejectAnotherRevisionEvenWhenTheOffsetsFit() {
        val source = "First 😀 paragraph"
        val changed = "Other 😀 paragraph"
        val revision = NoteRevision.of(source.toByteArray()) as NoteRevision.Known
        val start = source.indexOf("😀")
        val anchor = PassageAnchor(revision.sha256, start, start + 2)

        assertTrue(anchor.matches(revision, source))
        assertFalse(anchor.matches(NoteRevision.of(changed.toByteArray()), changed))
        assertFalse(anchor.copy(start = start + 1).matches(revision, source))
        assertFalse(anchor.copy(end = start + 1).matches(revision, source))
        assertFalse(anchor.copy(end = source.length + 1).matches(revision, source))
    }
}
