package com.example.looma.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSearchTest {

    @Test
    fun `the title reports every match range`() {
        val hit = searchNote(title = "Kotlin kotlin", tags = emptySet(), text = "", query = "kotlin")

        assertEquals(listOf("Kotlin", "kotlin"), hit!!.titleMatch!!.highlightedText())
        assertTrue(hit.matches.isEmpty())
    }

    @Test
    fun `a long title includes the matching text in its excerpt`() {
        val title = "前".repeat(200) + "标题关键词"
        val hit = searchNote(title = title, tags = emptySet(), text = "", query = "标题关键词")

        val match = hit!!.titleMatch!!
        assertTrue(match.text.startsWith("\u2026"))
        assertEquals(listOf("标题关键词"), match.highlightedText())
    }

    @Test
    fun `tag excerpts include the tag marker and highlight the query`() {
        val tags = setOf("生活/运动", "杂项")
        val plain = searchNote(title = "随记", tags = tags, text = "", query = "生活")
        val prefixed = searchNote(title = "随记", tags = tags, text = "", query = "#生活")

        assertEquals("#生活/运动", plain!!.tagMatches.single().text)
        assertEquals(listOf("生活"), plain.tagMatches.single().highlightedText())
        assertEquals("#生活/运动", prefixed!!.tagMatches.single().text)
        assertEquals(listOf("#生活"), prefixed.tagMatches.single().highlightedText())
    }

    @Test
    fun `a missing keyword returns null`() {
        assertNull(searchNote(title = "标题", tags = emptySet(), text = "正文".repeat(20), query = "missing"))
    }

    @Test
    fun `a blank query returns null`() {
        assertNull(searchNote(title = "标题", tags = emptySet(), text = "正文", query = "   "))
    }

    @Test
    fun `body matches expose the surrounding line`() {
        val hit = searchNote(
            title = "笔记",
            tags = emptySet(),
            text = "第一行\ntransport 层先建立连接\n第三行",
            query = "transport",
        )

        val match = hit!!.matches.single()
        assertEquals("transport 层先建立连接", match.text)
        assertEquals("transport", match.text.substring(match.ranges.single().first, match.ranges.single().last + 1))
    }

    @Test
    fun `repeated matches on one line stay in one match`() {
        val hit = searchNote(title = "笔记", tags = emptySet(), text = "abc ABC abc", query = "abc")

        val match = hit!!.matches.single()
        assertEquals(3, match.ranges.size)
        assertEquals(listOf("abc", "ABC", "abc"), match.ranges.map { match.text.substring(it.first, it.last + 1) })
    }

    @Test
    fun `matches on different lines stay in one hit`() {
        val hit = searchNote(
            title = "笔记",
            tags = emptySet(),
            text = "alpha\nbeta\nalpha again",
            query = "alpha",
        )

        assertEquals(2, hit!!.matches.size)
    }

    @Test
    fun `matches are capped`() {
        val text = (1..5).joinToString("\n") { "match $it" }
        val hit = searchNote(title = "笔记", tags = emptySet(), text = text, query = "match", maxMatches = 2)

        assertEquals(2, hit!!.matches.size)
    }

    @Test
    fun `a long line keeps a window around the first match`() {
        val line = "前".repeat(120) + "目标关键词" + "后".repeat(120)
        val hit = searchNote(title = "笔记", tags = emptySet(), text = line, query = "目标关键词")

        val match = hit!!.matches.single()
        assertTrue(match.text.startsWith("\u2026"))
        assertTrue(match.text.endsWith("\u2026"))
        assertTrue(match.text.length <= 142)
        assertEquals(listOf("目标关键词"), match.highlightedText())
    }

    @Test
    fun `a keyword longer than the context still appears in full`() {
        val keyword = "目标".repeat(80)
        val line = "前".repeat(120) + keyword + "后".repeat(120)
        val hit = searchNote(title = "笔记", tags = emptySet(), text = line, query = keyword)

        assertEquals(listOf(keyword), hit!!.matches.single().highlightedText())
    }

    @Test
    fun `a match cut off by the excerpt boundary is not highlighted`() {
        val line = "needle" + " ".repeat(130) + "needle and trailing text"
        val hit = searchNote(title = "笔记", tags = emptySet(), text = line, query = "needle")

        assertEquals(listOf("needle"), hit!!.matches.single().highlightedText())
    }

    @Test
    fun `excerpt boundaries preserve surrogate pairs`() {
        val line = "😀" + "前".repeat(27) + "目标" + "后".repeat(110) + "🚀" + "后".repeat(10)
        val hit = searchNote(title = "笔记", tags = emptySet(), text = line, query = "目标")

        val match = hit!!.matches.single()
        assertTrue(match.text.codePoints().noneMatch { it in 0xD800..0xDFFF })
        assertEquals(listOf("目标"), match.highlightedText())
    }

    @Test
    fun `frontmatter is not searched`() {
        val hit = searchNote(
            title = "笔记",
            tags = emptySet(),
            text = "---\ntags: hidden-token\n---\nbody only",
            query = "hidden-token",
        )

        assertNull(hit)
    }

    @Test
    fun `markdown markers are stripped from the context`() {
        val hit = searchNote(
            title = "笔记",
            tags = emptySet(),
            text = "# 标题\n- [ ] **记得** review\n> 引用 review",
            query = "review",
        )

        val texts = hit!!.matches.map { it.text }
        assertEquals(listOf("记得 review", "引用 review"), texts)
        assertTrue(texts.none { it.contains("*") || it.contains("[") || it.contains(">") })
    }

    @Test
    fun `a query can match across inline formatting`() {
        val hit = searchNote(title = "笔记", tags = emptySet(), text = "read **more** here", query = "read more")

        val match = hit!!.matches.single()
        assertEquals("read more here", match.text)
        assertEquals(listOf("read more"), match.highlightedText())
    }

    @Test
    fun `a match removed by cleaning is shown in its source context`() {
        val hit = searchNote(
            title = "笔记",
            tags = emptySet(),
            text = "Visit [the site](https://needle.example)",
            query = "needle",
        )

        val match = hit!!.matches.single()
        assertTrue(match.text.contains("https://needle.example"))
        assertEquals(listOf("needle"), match.highlightedText())
    }

    @Test
    fun `a wiki alias retains its hash text in the excerpt`() {
        val hit = searchNote(
            title = "笔记",
            tags = emptySet(),
            text = "Read [[file#heading|Topic #label]]",
            query = "#label",
        )

        val match = hit!!.matches.single()
        assertEquals("Read Topic #label", match.text)
        assertEquals(listOf("#label"), match.highlightedText())
    }

    @Test
    fun `a malformed long line still produces a bounded match excerpt`() {
        val line = "[".repeat(20_000) + "needle"
        val hit = searchNote(title = "笔记", tags = emptySet(), text = line, query = "needle")

        val match = hit!!.matches.single()
        assertTrue(match.text.startsWith("\u2026"))
        assertTrue(match.text.length <= 142)
        assertEquals(listOf("needle"), match.highlightedText())
    }

    @Test
    fun `a formatted heading equal to the title is not repeated as context`() {
        val hit = searchNote(
            title = "**Transport**",
            tags = emptySet(),
            text = "# **Transport**\nTCP body",
            query = "Transport",
        )

        assertEquals(listOf("Transport"), hit!!.titleMatch!!.highlightedText())
        assertTrue(hit.matches.isEmpty())
    }

    @Test
    fun `a body paragraph equal to the title is included in the matches`() {
        val hit = searchNote(
            title = "Transport",
            tags = emptySet(),
            text = "# Transport\n\nTransport",
            query = "Transport",
        )

        assertEquals("Transport", hit!!.matches.single().text)
    }

    @Test
    fun `duplicate context lines appear once`() {
        val hit = searchNote(
            title = "笔记",
            tags = emptySet(),
            text = "token line\ntoken line",
            query = "token",
        )

        assertEquals(1, hit!!.matches.size)
    }

    @Test
    fun `streaming reader matches string search across frontmatter and inline formatting`() {
        val text = """
            ---
            tags: hidden-token
            ---
            # 笔记
            read **more** here
            Visit [the site](https://needle.example)
        """.trimIndent()

        assertNull(searchNote("笔记", emptySet(), text.reader().buffered(), "hidden-token"))
        val formatted = searchNote("笔记", emptySet(), text.reader().buffered(), "read more")
        assertEquals(searchNote("笔记", emptySet(), text, "read more"), formatted)
        val rawUrl = searchNote("笔记", emptySet(), text.reader().buffered(), "needle")
        assertEquals(searchNote("笔记", emptySet(), text, "needle"), rawUrl)
    }

    @Test
    fun `streaming reader treats unclosed frontmatter fence as body content`() {
        val text = "---\nunclosed fence token\nsecond line"

        val hit = searchNote("笔记", emptySet(), text.reader().buffered(), "unclosed fence")

        assertEquals(searchNote("笔记", emptySet(), text, "unclosed fence"), hit)
        assertEquals("unclosed fence token", hit!!.matches.single().text)
    }

    @Test
    fun `mixed line endings keep the first line after frontmatter searchable`() {
        val text = "---\rmetadata\r---\nneedle\n---\n"
        val expected = NoteSearchHit(null, emptyList(), listOf(SearchMatch("needle", listOf(0..5))))

        assertEquals(expected, searchNote("Note", emptySet(), text, "needle"))
        assertEquals(expected, searchNote("Note", emptySet(), text.reader().buffered(), "needle"))
        assertTrue(SearchSignature.mayContain(SearchSignature.fromBody(text), "needle"))
    }

    @Test
    fun `bom and yaml dot-end frontmatter stay out of string and reader search`() {
        val text = "\uFEFF---\ntags: hidden-token\n...\nbody only"

        assertNull(searchNote("Note", emptySet(), text, "hidden-token"))
        assertNull(searchNote("Note", emptySet(), text.reader().buffered(), "hidden-token"))
        assertEquals("body only", searchNote("Note", emptySet(), text.reader().buffered(), "body")!!.matches.single().text)
    }

    private fun SearchMatch.highlightedText(): List<String> = ranges.map { text.substring(it) }
}
