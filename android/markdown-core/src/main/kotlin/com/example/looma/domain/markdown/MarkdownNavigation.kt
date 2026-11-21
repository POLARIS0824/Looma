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
        val last = if (end > start) end - 1 else start
        val firstBlock = blocks.firstOrNull { it.last >= start } ?: return null
        val lastBlock = blocks.lastOrNull { it.first <= last } ?: return null
        if (lastBlock.last < firstBlock.first) return null
        if (start == end) return NotePassage(source, firstBlock, firstBlock.last + 1)
        val range = maxOf(start, firstBlock.first)..minOf(last, lastBlock.last)
        if (range.isEmpty() || source.substring(range).isBlank()) return null
        return NotePassage(source, range, lastBlock.last + 1)
    }

    companion object {
        fun scan(source: String, prepared: PreparedMarkdown.Ready? = null): MarkdownNavigation {
            val ready = prepared?.takeIf { it.source == source }
            val base = ready?.index?.bodyStart ?: frontmatterEnd(source)
            val tree = ready?.state?.node ?: MarkdownParser(WikiImageFlavour())
                .buildMarkdownTreeFromString(source.substring(base) as CharSequence)
            val headings = mutableListOf<OutlineHeading>()
            val blocks = mutableListOf<IntRange>()
            for (node in tree.children) {
                if (node.endOffset <= node.startOffset || node.type == MarkdownTokenTypes.EOL ||
                    node.type == MarkdownTokenTypes.WHITE_SPACE) continue
                blocks += (base + node.startOffset) until (base + node.endOffset)
                heading(node, source, base)?.let(headings::add)
            }
            return MarkdownNavigation(headings, blocks)
        }

        private fun heading(node: ASTNode, source: String, base: Int): OutlineHeading? {
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
            return OutlineHeading(source.substring(base + title.startOffset, base + title.endOffset).trim(),
                level, base + node.startOffset)
        }
    }
}

class NotePassage(val source: String, val range: IntRange, val insertAt: Int)
