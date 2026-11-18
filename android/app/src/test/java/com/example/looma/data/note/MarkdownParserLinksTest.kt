package com.example.looma.data.note

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParserLinksTest {

    private val parser = MarkdownParser()

    @Test
    fun `wiki links keep source order, duplicates and embedded flag`() {
        val metadata = parser.parse("[[A]] ![[B|别名]] [[C#标题]] [[A]]", "笔记")

        assertEquals(listOf("A", "B", "C", "A"), metadata.links.map { it.target })
        assertEquals(listOf(false, true, false, false), metadata.links.map { it.embedded })
    }

    @Test
    fun `link with only an alias or anchor is skipped`() {
        val metadata = parser.parse("[[|别名]] [[#标题]]", "笔记")

        assertEquals(emptyList<MarkdownLink>(), metadata.links)
    }

    @Test
    fun `image references keep document order and skip code blocks`() {
        val text = """
            ```md
            ![code](code.png)
            ![[code-wiki.png]]
            ```

            `![inline](inline.png)`

            ![[cover.png]]

            ![图](pics/photo.jpg)
        """.trimIndent()
        val metadata = parser.parse(text, "笔记")

        assertEquals("cover.png", metadata.firstImageReference)
        assertEquals(listOf("cover.png", "pics/photo.jpg"), metadata.imageReferences)
        assertEquals(listOf("cover.png", "pics/photo.jpg"), parser.parseForIndex(text, "笔记").imageReferences)
    }

    @Test
    fun `first image reference falls back to a wiki image`() {
        val metadata = parser.parse("![[cover.png]]", "笔记")

        assertEquals("cover.png", metadata.firstImageReference)
    }

    @Test
    fun `extracts link-wrapped images, reference images, and prefers image destination over alt link`() {
        val text = """
            [![badge](cover.png)](https://example.com)
            [![[wiki-cover.png]]](https://example.com)
            [![ref-badge](ref-cover.png)][jump]
            ![alt][img-ref]
            ![collapsed][]
            ![shortcut]
            ![photo by [Alice](https://example.com)](local.png)

            [jump]: https://example.com/jump
            [img-ref]: ref-img.png
            [collapsed]: collapsed.png
            [shortcut]: shortcut.png
        """.trimIndent()

        val expected = listOf(
            "cover.png",
            "wiki-cover.png",
            "ref-cover.png",
            "ref-img.png",
            "collapsed.png",
            "shortcut.png",
            "local.png",
        )
        val parsed = parser.parse(text, "笔记")
        val forIndex = parser.parseForIndex(text, "笔记")

        assertEquals("cover.png", parsed.firstImageReference)
        assertEquals(expected, parsed.imageReferences)
        assertEquals("cover.png", forIndex.firstImageReference)
        assertEquals(expected, forIndex.imageReferences)
    }
}
