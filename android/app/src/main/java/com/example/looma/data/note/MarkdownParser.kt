package com.example.looma.data.note

import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.markdown.forEachLineRange

data class MarkdownChecklistItem(
    val text: String,
    val checked: Boolean,
)

data class MarkdownLink(
    val target: String,
    val embedded: Boolean,
)

data class MarkdownMetadata(
    val title: String,
    val excerpt: String,
    val tags: Set<String>,
    val checklist: List<MarkdownChecklistItem>,
    val links: List<MarkdownLink>,
    val firstImageReference: String?,
    val imageReferences: List<String> = emptyList(),
)

internal class MarkdownParser {
    fun title(text: String, fallbackTitle: String): String {
        val ast = MarkdownIndex.extractMetadata(text)
        val visibleLines = nonCodeLines(text.substring(ast.bodyStart), ast.blockCodeRanges)
        return extractTitle(visibleLines.asSequence(), fallbackTitle)
    }

    fun tags(text: String): Set<String> = MarkdownIndex.extractMetadata(text).tags

    fun parseForIndex(text: String, fallbackTitle: String): MarkdownMetadata {
        val ast = MarkdownIndex.extractMetadata(text)
        val visibleLines = nonCodeLines(text.substring(ast.bodyStart), ast.blockCodeRanges)
        val links = MarkdownIndex.index(text, fallbackTitle).links
            .map { MarkdownLink(target = it.target, embedded = it.embedded) }
        return MarkdownMetadata(
            title = extractTitle(visibleLines.asSequence(), fallbackTitle),
            excerpt = "",
            tags = ast.tags,
            checklist = emptyList(),
            links = links,
            firstImageReference = ast.imageReferences.firstOrNull(),
            imageReferences = ast.imageReferences,
        )
    }

    fun parse(text: String, fallbackTitle: String): MarkdownMetadata {
        val ast = MarkdownIndex.extractMetadata(text)
        val visibleLines = nonCodeLines(text.substring(ast.bodyStart), ast.blockCodeRanges)
        val excerpt = visibleLines.asSequence()
            .filterNot { it.matches(HEADING) || it.matches(CHECKLIST) }
            .filterNot {
                it.trim().startsWith("![[") ||
                    it.trim().startsWith("![")
            }
            .map { stripInlineMarkdown(it.trim().replace(LIST_MARKER, "")) }
            .filter { it.isNotBlank() }
            .take(15)
            .joinToString("\n")
            .take(360)

        val doc = MarkdownIndex.index(text, fallbackTitle)
        val checklist = doc.checkboxes.mapNotNull { box ->
            val taskStart = box.range.last + 1
            if (taskStart >= text.length) return@mapNotNull null
            var taskEnd = taskStart
            while (taskEnd < text.length && text[taskEnd] != '\n' && text[taskEnd] != '\r') {
                taskEnd++
            }
            val itemText = text.substring(taskStart, taskEnd).trim()
            if (itemText.isEmpty()) return@mapNotNull null
            MarkdownChecklistItem(text = itemText, checked = box.checked)
        }

        val links = doc.links
            .map { MarkdownLink(target = it.target, embedded = it.embedded) }

        return MarkdownMetadata(
            title = extractTitle(visibleLines.asSequence(), fallbackTitle),
            excerpt = excerpt,
            tags = ast.tags,
            checklist = checklist,
            links = links,
            firstImageReference = ast.imageReferences.firstOrNull(),
            imageReferences = ast.imageReferences,
        )
    }

    private fun stripInlineMarkdown(line: String): String = line
        .replace(WIKI_IMAGE, "")
        .replace(WIKI_LINK, "$1")
        .replace(MARKDOWN_IMAGE, "")
        .replace(MARKDOWN_LINK, "$1")
        .replace(EMPHASIS, "")
        .replace(CODE_SPAN, "$1")

    private fun nonCodeLines(body: String, blockCodeRanges: List<IntRange>): List<String> {
        val lines = mutableListOf<String>()
        var rangeIndex = 0
        forEachLineRange(body, 0) { start, end, _ ->
            while (rangeIndex < blockCodeRanges.size && blockCodeRanges[rangeIndex].last < start) {
                rangeIndex++
            }
            val lineEnd = if (end > start) end - 1 else start
            val insideCode = rangeIndex < blockCodeRanges.size &&
                blockCodeRanges[rangeIndex].let { range ->
                    if (start == end) start in range else range.first <= lineEnd && range.last >= start
                }
            if (!insideCode) {
                lines += body.substring(start, end)
            }
            true
        }
        return lines
    }

    private fun extractTitle(lines: Sequence<String>, fallback: String): String =
        lines.firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim()?.takeIf { it.isNotBlank() }
            ?: fallback

    private companion object {
        val LIST_MARKER = Regex("""^[-*]\s+""")
        val HEADING = Regex("""^#{1,6}\s+.+""")
        val CHECKLIST = Regex("""^\s*[-*+]\s+\[([ xX])\]\s+(.+)$""")
        val MARKDOWN_IMAGE = Regex("""!\[[^]]*]\(([^)]+)\)""")
        val WIKI_IMAGE = Regex("""!\[\[([^]]+)]]""")
        val WIKI_LINK = Regex("""(?<!!)\[\[([^]]+)]]""")
        val MARKDOWN_LINK = Regex("""\[([^]]*)]\(([^)]*)\)""")
        val EMPHASIS = Regex("""\*\*|\*|__|~~""")
        val CODE_SPAN = Regex("""`([^`]*)`""")
    }
}
