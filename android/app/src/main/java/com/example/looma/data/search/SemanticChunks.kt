package com.example.looma.data.search

import com.example.looma.domain.markdown.WikiImageFlavour
import com.example.looma.domain.markdown.frontmatterEnd
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

internal data class SemanticChunk(val start: Int, val end: Int, val section: String)

internal fun semanticChunks(source: String, checkActive: () -> Unit = {}): List<SemanticChunk> {
    val bodyStart = frontmatterEnd(source)
    val body = source.substring(bodyStart)
    val parser = MarkdownParser(
        WikiImageFlavour(),
        cancellationToken = object : CancellationToken {
            override fun checkCancelled() = checkActive()
        },
    )
    val tree = parser.buildMarkdownTreeFromString(body as CharSequence)
    val headings = sortedMapOf<Int, String>()
    val chunks = mutableListOf<SemanticChunk>()
    var section = ""
    var start = -1
    var end = -1

    fun flush() {
        if (start >= 0) chunks += SemanticChunk(start, end, section)
        start = -1
    }

    fun appendRange(untrimmedStart: Int, untrimmedEnd: Int) {
        var blockStart = untrimmedStart
        var blockEnd = untrimmedEnd
        while (blockStart < blockEnd && source[blockStart].isWhitespace()) blockStart++
        while (blockEnd > blockStart && source[blockEnd - 1].isWhitespace()) blockEnd--
        if (blockStart == blockEnd) return
        if (start >= 0 && blockEnd - start > CHUNK_LENGTH) flush()

        while (blockEnd - blockStart > CHUNK_LENGTH) {
            checkActive()
            var split = source.codePointBoundary(blockStart + CHUNK_LENGTH)
            val space = (split - 1 downTo blockStart + CHUNK_LENGTH / 2).firstOrNull { source[it] in BREAK_CHARACTERS }
            if (space != null) split = space + 1
            var trimmedEnd = split
            while (trimmedEnd > blockStart && source[trimmedEnd - 1].isWhitespace()) trimmedEnd--
            if (trimmedEnd > blockStart) chunks += SemanticChunk(blockStart, trimmedEnd, section)
            blockStart = split
            while (blockStart < blockEnd && source[blockStart].isWhitespace()) blockStart++
        }
        if (blockStart < blockEnd) {
            if (start < 0) start = blockStart
            end = blockEnd
        }
    }

    for (node in tree.children) {
        checkActive()
        val level = HEADING_LEVELS[node.type]
        if (level != null) {
            flush()
            headings.tailMap(level).clear()
            val heading = node.children.firstOrNull {
                it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
            }?.getTextInNode(body)?.toString()?.trim().orEmpty()
            if (heading.isNotEmpty()) headings[level] = heading.cappedForModel(128)
            section = headings.values.joinToString(" / ").cappedForModel(256)
        } else if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
            flush()
        } else if (node.type != MarkdownTokenTypes.EOL && node.type != MarkdownTokenTypes.WHITE_SPACE) {
            appendRange(bodyStart + node.startOffset, bodyStart + node.endOffset)
        }
    }
    flush()
    if (chunks.isEmpty()) appendRange(bodyStart, source.length)
    flush()
    return chunks
}

internal fun String.cappedForModel(limit: Int): String = substring(0, codePointBoundary(minOf(length, limit)))

private fun String.codePointBoundary(offset: Int): Int =
    if (offset > 0 && offset < length && this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate()) {
        offset - 1
    } else {
        offset
    }

private const val CHUNK_LENGTH = 512
private val BREAK_CHARACTERS = charArrayOf(' ', '\t', '\r', '\n', '。', '！', '？')
private val HEADING_LEVELS = mapOf(
    MarkdownElementTypes.ATX_1 to 1,
    MarkdownElementTypes.ATX_2 to 2,
    MarkdownElementTypes.ATX_3 to 3,
    MarkdownElementTypes.ATX_4 to 4,
    MarkdownElementTypes.ATX_5 to 5,
    MarkdownElementTypes.ATX_6 to 6,
    MarkdownElementTypes.SETEXT_1 to 1,
    MarkdownElementTypes.SETEXT_2 to 2,
)
