package com.example.looma.data.note

import com.example.looma.domain.markdown.InlineTagSpan
import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.markdown.findInlineTags
import com.example.looma.domain.markdown.frontmatterEnd

internal fun markdownSnippetOf(
    source: String,
    title: String,
    maxChars: Int = CARD_MARKDOWN_CHARS,
): String {
    val body = source.substring(frontmatterEnd(source)).trimStart('\r', '\n')
    val withoutTitle = body.lineSequence()
        .dropWhile { it.isBlank() }
        .toList()
        .let { lines ->
            val first = lines.firstOrNull()
            if (first != null && first.startsWith("# ") && first.removePrefix("# ").trim() == title.trim()) {
                lines.drop(1)
            } else {
                lines
            }
        }
        .joinToString("\n")
        .trimBlankLines()
        .withoutInlineTags()
        .trimBlankLines()
    if (withoutTitle.length <= maxChars) return withoutTitle
    val clipped = withoutTitle.take(maxChars)
    val lastLineBreak = clipped.lastIndexOf('\n')
    return if (lastLineBreak > maxChars / 2) clipped.substring(0, lastLineBreak).trimEnd() else clipped.trimEnd()
}

private fun String.trimBlankLines(): String =
    lines()
        .dropWhile { it.isBlank() }
        .dropLastWhile { it.isBlank() }
        .joinToString("\n")
        .trimEnd()

private fun String.withoutInlineTags(): String {
    if ('#' !in this) return this
    val excluded = MarkdownIndex.excludedTagRanges(this)
    val spans = findInlineTags(this, excluded)
    if (spans.isEmpty()) return this

    val spansByLine = ArrayList<MutableList<InlineTagSpan>>()
    val lines = split('\n')
    var lineOffset = 0
    var spanIndex = 0
    for (line in lines) {
        val lineEnd = lineOffset + line.length
        val lineSpans = mutableListOf<InlineTagSpan>()
        while (spanIndex < spans.size && spans[spanIndex].range.last < lineEnd) {
            if (spans[spanIndex].range.first >= lineOffset) {
                val localStart = spans[spanIndex].range.first - lineOffset
                val localEnd = spans[spanIndex].range.last - lineOffset
                lineSpans += InlineTagSpan(localStart..localEnd, spans[spanIndex].tag)
            }
            spanIndex++
        }
        spansByLine += lineSpans
        lineOffset = lineEnd + 1
    }

    return lines.mapIndexedNotNull { index, line ->
        val lineSpans = spansByLine[index]
        if (lineSpans.isEmpty()) return@mapIndexedNotNull line
        val sb = StringBuilder(line)
        for (span in lineSpans.sortedByDescending { it.range.first }) {
            val start = span.range.first
            val end = span.range.last
            val removeStart = if (
                start > 0 &&
                sb[start - 1] == ' ' &&
                (end + 1 == sb.length || sb[end + 1] == ' ')
            ) {
                start - 1
            } else {
                start
            }
            val removeEndExclusive = if (
                removeStart == 0 &&
                end + 1 < sb.length &&
                sb[end + 1] == ' '
            ) {
                end + 2
            } else {
                end + 1
            }
            sb.delete(removeStart, removeEndExclusive)
        }
        val cleaned = sb.toString()
        if (cleaned.isBlank() && line.isNotBlank()) null else cleaned
    }.joinToString("\n")
}

internal const val CARD_MARKDOWN_CHARS = 600
