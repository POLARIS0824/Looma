package com.example.looma.data.note

import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.markdown.filterOutFencedLines
import com.example.looma.domain.markdown.frontmatterEnd

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
    fun title(text: String, fallbackTitle: String): String = extractTitle(bodyLines(text), fallbackTitle)

    fun tags(text: String): Set<String> = MarkdownIndex.extractMetadata(text).tags

    fun parseForIndex(text: String, fallbackTitle: String): MarkdownMetadata {
        val ast = MarkdownIndex.extractMetadata(text)
        val links = MarkdownIndex.index(text, fallbackTitle).links
            .map { MarkdownLink(target = it.target, embedded = it.embedded) }
        return MarkdownMetadata(
            title = extractTitle(bodyLines(text, ast.bodyStart), fallbackTitle),
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
        val body = bodyLines(text, ast.bodyStart).toList()
        val nonFencedBody = filterOutFencedLines(body)
        val excerpt = nonFencedBody.asSequence()
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

        val checklist = nonFencedBody.mapNotNull { line ->
            val match = CHECKLIST.matchEntire(line) ?: return@mapNotNull null
            MarkdownChecklistItem(
                text = match.groupValues[2],
                checked = match.groupValues[1].equals("x", ignoreCase = true),
            )
        }

        val links = MarkdownIndex.index(text, fallbackTitle).links
            .map { MarkdownLink(target = it.target, embedded = it.embedded) }

        return MarkdownMetadata(
            title = extractTitle(body.asSequence(), fallbackTitle),
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

    private fun bodyLines(text: String, bodyStart: Int = frontmatterEnd(text)): Sequence<String> =
        text.substring(bodyStart).lineSequence()

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
