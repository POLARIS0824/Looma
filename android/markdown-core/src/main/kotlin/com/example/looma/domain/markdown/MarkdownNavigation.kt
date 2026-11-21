package com.example.looma.domain.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser

data class OutlineHeading(val title: String, val level: Int, val start: Int)

class MarkdownNavigation private constructor(
    val headings: List<OutlineHeading>,
    private val blocks: List<Block>,
) {
    fun passage(source: String, start: Int, end: Int): NotePassage? {
        if (start !in 0..source.length || end !in start..source.length) return null
        if (start == end) {
            val next = blocks.firstOrNull { start <= it.range.last }
            val block = if (next != null && (start in next.range ||
                    source.indexOf('\n', start).let { it < 0 || it >= next.range.first })) next
                else blocks.lastOrNull { it.range.first <= start } ?: return null
            return NotePassage(source, block.range, block.range.last + 1, block.inline)
        }
        val selected = blocks.filter { it.range.last >= start && it.range.first < end }
        val first = selected.firstOrNull() ?: return null
        val last = selected.last()
        val range = maxOf(start, first.range.first)..minOf(end - 1, last.range.last)
        if (range.all { source[it].isWhitespace() }) return null
        return NotePassage(source, range, last.range.last + 1, last.inline)
    }

    private data class Block(val range: IntRange, val inline: Boolean)

    companion object {
        fun scan(source: String, prepared: PreparedMarkdown.Ready? = null): MarkdownNavigation {
            val ready = prepared?.takeIf { it.source == source }
            val base = ready?.index?.bodyStart ?: frontmatterEnd(source)
            // The parser treats CR as content on Setext underlines and fence delimiters.
            // Parse LF and translate node boundaries back to the original editor offsets.
            val crlf = source.indexOf("\r\n", base) >= 0
            val lineEnds = if (crlf) {
                val offsets = mutableListOf<Int>()
                var at = source.indexOf("\r\n", base)
                while (at >= 0) {
                    offsets += at - base - offsets.size + 1
                    at = source.indexOf("\r\n", at + 2)
                }
                offsets.toIntArray()
            } else intArrayOf()
            fun offset(position: Int): Int {
                val found = lineEnds.binarySearch(position)
                return base + position + if (found >= 0) found + 1 else found.inv()
            }
            val tree = try {
                ready?.state?.node?.takeUnless { crlf } ?: run {
                    val body = source.substring(base)
                    MarkdownParser(WikiImageFlavour()).buildMarkdownTreeFromString(
                        (if (crlf) body.replace("\r\n", "\n") else body) as CharSequence,
                    )
                }
            } catch (_: StackOverflowError) {
                return MarkdownNavigation(emptyList(), emptyList())
            }
            val headings = mutableListOf<OutlineHeading>()
            val blocks = mutableListOf<Block>()
            fun addBlock(node: ASTNode, nested: Boolean) {
                if (node.endOffset <= node.startOffset) return
                if (node.type in CONTAINERS) {
                    val before = blocks.size
                    node.children.forEach { addBlock(it, true) }
                    if (blocks.size > before) return
                } else if (nested && node.type !in NESTED_BLOCKS) {
                    return
                } else if (node.type == MarkdownTokenTypes.EOL || node.type == MarkdownTokenTypes.WHITE_SPACE) {
                    return
                }
                val start = offset(node.startOffset)
                var end = offset(node.endOffset)
                while (end > start && (source[end - 1] == '\n' || source[end - 1] == '\r')) end--
                if (end > start) blocks += Block(start until end, nested && node.type == MarkdownElementTypes.PARAGRAPH)
                heading(node, source, ::offset)?.let(headings::add)
            }
            tree.children.forEach { addBlock(it, false) }
            return MarkdownNavigation(headings, blocks)
        }

        private val CONTAINERS = setOf(MarkdownElementTypes.ORDERED_LIST, MarkdownElementTypes.UNORDERED_LIST,
            MarkdownElementTypes.LIST_ITEM, MarkdownElementTypes.BLOCK_QUOTE)
        private val NESTED_BLOCKS = setOf(MarkdownElementTypes.PARAGRAPH, MarkdownElementTypes.CODE_BLOCK,
            MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2,
            MarkdownElementTypes.ATX_3, MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5,
            MarkdownElementTypes.ATX_6, MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2)

        private fun heading(node: ASTNode, source: String, offset: (Int) -> Int): OutlineHeading? {
            val level = when (node.type) {
                MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> 1
                MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> 2
                MarkdownElementTypes.ATX_3 -> 3
                MarkdownElementTypes.ATX_4 -> 4
                MarkdownElementTypes.ATX_5 -> 5
                MarkdownElementTypes.ATX_6 -> 6
                else -> return null
            }
            val title = node.children.firstOrNull {
                it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
            } ?: return null
            return OutlineHeading(source.substring(offset(title.startOffset), offset(title.endOffset)).trim(),
                level, offset(node.startOffset))
        }
    }
}

class NotePassage(val source: String, val range: IntRange, val insertAt: Int, val inline: Boolean = false) {
    fun insertionPreview(addition: String): String {
        val from = (insertAt - 64).coerceAtLeast(0)
        val to = (insertAt + 48).coerceAtMost(source.length)
        return source.substring(from, insertAt) + addition + source.substring(insertAt, to)
    }

    fun excerpt(limit: Int): String {
        var end = range.first + minOf(range.last - range.first + 1, limit)
        if (end < source.length && end > range.first && source[end - 1].isHighSurrogate() && source[end].isLowSurrogate()) end--
        return source.substring(range.first, end)
    }
}
