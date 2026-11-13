package com.example.looma.domain.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

internal data class MarkdownReadingIndex(
    val bodyStart: Int,
    val heading: String?,
    val checkboxes: List<MarkdownCheckbox>,
)

object MarkdownIndex {

    fun index(source: String, fallbackTitle: String): MarkdownDocument {
        val bodyStart = frontmatterEnd(source)
        val body = source.substring(bodyStart)
        val tree = MarkdownParser(GFMFlavourDescriptor(), cancellationToken = CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(body as CharSequence)
        val facts = Facts(code = mutableListOf())
        walk(tree, body, bodyStart, facts)

        val heading = facts.heading?.takeIf { it.isNotBlank() }
        return MarkdownDocument(
            source = source,
            body = body,
            bodyStart = bodyStart,
            title = heading ?: fallbackTitle,
            heading = heading,
            checkboxes = facts.checkboxes,
            links = WikiLinks.occurrences(body, bodyStart, facts.code.orEmpty()),
        )
    }

    internal fun forReading(body: String, bodyStart: Int, tree: ASTNode): MarkdownReadingIndex {
        val facts = Facts(code = null)
        walk(tree, body, bodyStart, facts)
        return MarkdownReadingIndex(bodyStart, facts.heading?.takeIf { it.isNotBlank() }, facts.checkboxes)
    }

    private fun walk(node: ASTNode, text: String, base: Int, facts: Facts) {
        when (node.type) {
            MarkdownElementTypes.CODE_FENCE,
            MarkdownElementTypes.CODE_BLOCK,
            MarkdownElementTypes.CODE_SPAN,
            -> {
                facts.code?.add(node.startOffset until node.endOffset)
                return
            }

            MarkdownElementTypes.ATX_1,
            MarkdownElementTypes.SETEXT_1,
            -> if (facts.heading == null) facts.heading = headingText(node, text)

            MarkdownElementTypes.LIST_ITEM -> checkboxIn(node, text, base)?.let(facts.checkboxes::add)
        }
        if (facts.code == null && node.type in MarkdownParser.INLINE_CONTENT_TYPES) return
        node.children.forEach { walk(it, text, base, facts) }
    }

    private fun headingText(node: ASTNode, text: String): String = node.children
        .firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT }
        ?.getTextInNode(text)
        ?.toString()
        ?.trim()
        .orEmpty()

    private fun checkboxIn(item: ASTNode, text: String, base: Int): MarkdownCheckbox? {
        val marker = item.children.firstOrNull { child ->
            child.endOffset - child.startOffset <= 4 && MARKER.matches(child.getTextInNode(text))
        } ?: return null
        val start = marker.startOffset
        if (start + MARKER_LENGTH > text.length) return null
        return MarkdownCheckbox(
            range = (base + start) until (base + start + MARKER_LENGTH),
            checked = text.regionMatches(start, CHECKED, 0, MARKER_LENGTH, ignoreCase = true),
        )
    }

    private class Facts(val code: MutableList<IntRange>?) {
        var heading: String? = null
        val checkboxes = mutableListOf<MarkdownCheckbox>()
    }

    private val MARKER = Regex("""^\[[ xX]]\s*$""")
}
