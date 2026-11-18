package com.example.looma.feature.home

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.VaultId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSearchTest {

    private val notes = listOf(
        note("周会记录", "讨论了下周的发布计划"),
        note("读书笔记", "kotlin coroutines"),
        note("随记", "购物清单", tags = setOf("生活")),
    )

    @Test
    fun `a blank query matches nothing`() {
        assertTrue(searchNotes(notes, emptyMap(), "  ").isEmpty())
    }

    @Test
    fun `the title matches`() {
        assertEquals(listOf("周会记录"), searchNotes(notes, emptyMap(), "周会").map { it.note.summary.title })
    }

    @Test
    fun `the full text matches, not just the snippet`() {
        val texts = mapOf(notes[1].id to "第一行\n第二行里有 coroutines\n最后一行")

        assertEquals(listOf("读书笔记"), searchNotes(notes, texts, "第二行").map { it.note.summary.title })
    }

    @Test
    fun `tags match`() {
        assertEquals(listOf("随记"), searchNotes(notes, emptyMap(), "生活").map { it.note.summary.title })
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(listOf("读书笔记"), searchNotes(notes, emptyMap(), "KOTLIN").map { it.note.summary.title })
    }

    @Test
    fun `several matches in one note stay in one result`() {
        val texts = mapOf(notes[0].id to "发布计划 A\n发布计划 B")

        val result = searchNotes(notes, texts, "发布计划").single()

        assertEquals("周会记录", result.note.summary.title)
        assertEquals(2, result.matches.size)
    }

    @Test
    fun `the same note appears once and same-title files remain separate`() {
        val first = note("Daily", "shared body")
        val second = first.copy(summary = first.summary.copy(id = first.id.copy(path = NotePath("Work/Daily.md"))))

        val results = searchNotes(listOf(first, first, second), emptyMap(), "shared")

        assertEquals(listOf(first.id, second.id), results.map { it.id })
    }

    private fun note(
        title: String,
        text: String,
        tags: Set<String> = emptySet(),
    ): HomeNoteItem = HomeNoteItem(
        summary = NoteSummary(
            id = NoteId(VaultId("vault"), NotePath("$title.md")),
            title = title,
            modifiedAt = Instant.EPOCH,
            tags = tags,
        ),
        markdownSnippet = text,
    )
}
