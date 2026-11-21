package com.example.looma.domain.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser

data class OutlineHeading(val title: String, val level: Int, val start: Int)

class MarkdownNavigation private constructor(
    val headings: List<OutlineHeading>,
    private val blocks: List<IntRange>,
) {
    fun passage(source: String, start: Int, end: Int): NotePassage? {
        if (start !in 0..source.length || end !in start..source.length) return null
        if (start == end) {
            val block = blocks.lastOrNull { it.first <= start } ?: return null
            return NotePassage(source, block, block.last + 1)
        }
        val last = end - 1
        val firstBlock = blocks.firstOrNull { it.last >= start } ?: return null
        val lastBlock = blocks.lastOrNull { it.first <= last } ?: return null
        if (lastBlock.last < firstBlock.first) return null
        val range = maxOf(start, firstBlock.first)..minOf(last, lastBlock.last)
        if (range.isEmpty() || range.all { source[it].isWhitespace() }) return null
        return NotePassage(source, range, lastBlock.last + 1)
    }

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
            val blocks = mutableListOf<IntRange>()
            for (node in tree.children) {
                if (node.endOffset <= node.startOffset || node.type == MarkdownTokenTypes.EOL ||
                    node.type == MarkdownTokenTypes.WHITE_SPACE) continue
                blocks += offset(node.startOffset) until offset(node.endOffset)
                heading(node, source, ::offset)?.let(headings::add)
            }
            return MarkdownNavigation(headings, blocks)
        }

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

class NotePassage(val source: String, val range: IntRange, val insertAt: Int) {
    fun excerpt(limit: Int): String {
        var end = range.first + minOf(range.last - range.first + 1, limit)
        if (end < source.length && end > range.first && source[end - 1].isHighSurrogate() && source[end].isLowSurrogate()) end--
        return source.substring(range.first, end)
    }
}
