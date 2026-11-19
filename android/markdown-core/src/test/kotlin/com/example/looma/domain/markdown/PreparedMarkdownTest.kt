package com.example.looma.domain.markdown

import org.intellij.markdown.parser.CancellationToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedMarkdownTest {
    @Test
    fun `paragraph separators retain their item indexes and are skipped as anchors`() {
        val source = "First paragraph\n\nSecond paragraph"
        val preview = prepareMarkdownOnCurrentThread(source, CancellationToken.NonCancellable) as PreparedMarkdown.Ready

        assertEquals(listOf(true, false, false, true), preview.blocks.map { it.hasContent })
        assertEquals(
            listOf("First paragraph", "\n", "\n", "Second paragraph"),
            preview.blocks.map { source.substring(it.start, it.end) },
        )
    }

    @Test
    fun `opening preview omits a fence that crosses the preview boundary`() {
        val source = "# Large note\n\n- [ ] First task\n\nExample follows.\n\n```text\n" +
            "contents of the fence\n".repeat(400) + "```\n"
        val opening = checkNotNull(prepareMarkdownOpeningOnCurrentThread(source, CancellationToken.NonCancellable))

        assertEquals(source, opening.source)
        assertEquals("Large note", opening.preview.index.heading)
        assertEquals(listOf("[ ]"), opening.preview.index.checkboxes.map { source.substring(it.range) })
        assertTrue("First task" in opening.preview.state.content)
        assertTrue("contents of the fence" !in opening.preview.state.content)
    }

    @Test
    fun `display math changes render boundaries while reading offsets still use source`() {
        val source = """
            ---
            tags: [math]
            ---
            $$

            # Source heading

            - [ ] source task

            $$

            [docs][ref]

            - [x] visible task

            [ref]: https://example.com/docs
        """.trimIndent()
        val raw = MarkdownIndex.index(source, "Fallback")
        val preview = prepareMarkdownOnCurrentThread(source, CancellationToken.NonCancellable) as PreparedMarkdown.Ready

        assertEquals("Source heading", preview.index.heading)
        assertEquals(raw.bodyStart, preview.index.bodyStart)
        assertEquals(listOf("[ ]", "[x]"), preview.index.checkboxes.map { source.substring(it.range) })
        assertEquals(raw.body.length, preview.state.content.length)
        assertTrue('\u200B' in preview.state.content)
        assertEquals("https://example.com/docs", preview.state.referenceLinkHandler.find("[ref]"))
    }

    @Test
    fun `bom frontmatter strips metadata cleanly instead of rendering yaml as a heading`() {
        val source = "\uFEFF---\ntitle: Meta\n---\n# Real Title\nBody"
        val preview = prepareMarkdownOnCurrentThread(source, CancellationToken.NonCancellable) as PreparedMarkdown.Ready

        assertEquals("Real Title", preview.index.heading)
        assertEquals("# Real Title\nBody", preview.state.content)
    }
}
