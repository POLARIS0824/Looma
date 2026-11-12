package com.example.looma.benchmark

import com.example.looma.domain.markdown.MarkdownDocument
import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.markdown.MathSpan
import com.example.looma.domain.markdown.WikiImageFlavour
import com.example.looma.domain.markdown.collectMathSpans
import com.example.looma.domain.markdown.preserveDisplayMathParagraphs
import com.example.looma.ui.markdown.PreparedMarkdown
import com.example.looma.ui.markdown.prepareMarkdownOnCurrentThread
import com.example.looma.ui.markdown.prepareMarkdownOpeningOnCurrentThread
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

internal class MarkdownWorkloads(private val profile: Profile, targetKiB: Int) {
    enum class Profile { MIXED, DISPLAY_MATH }

    private data class Input(val source: String, val rawBody: String, val sections: Int)

    data class Baseline(
        val document: MarkdownDocument,
        val state: State.Success,
        val mathSpans: List<MathSpan>,
    )

    private val input = makeInput(targetKiB)
    private val cancellation = CancellationToken.NonCancellable

    fun previewBaseline(): Baseline {
        val document = MarkdownIndex.index(input.source, FALLBACK_TITLE)
        val flavour = WikiImageFlavour()
        val content = preserveDisplayMathParagraphs(document.body, flavour)
        val state = parseMarkdown(
            content = content,
            flavour = flavour,
            parser = MarkdownParser(flavour, cancellationToken = cancellation),
        )
        check(state is State.Success) { "Baseline preview preparation failed: $state" }
        return Baseline(document, state, collectMathSpans(state.content, state.node))
    }

    fun previewSharedAst(): PreparedMarkdown.Ready {
        val prepared = prepareMarkdownOnCurrentThread(input.source, cancellation)
        check(prepared is PreparedMarkdown.Ready) { "Shared AST preview preparation failed" }
        return prepared
    }

    fun previewOpening(): PreparedMarkdown.Opening =
        checkNotNull(prepareMarkdownOpeningOnCurrentThread(input.source, cancellation))

    fun jetBrainsGfmAst(): ASTNode = parseRawBody(GFMFlavourDescriptor())

    fun jetBrainsWikiAst(): ASTNode = parseRawBody(WikiImageFlavour())

    fun verify() {
        val current = previewBaseline()
        val shared = previewSharedAst()
        val opening = previewOpening()
        val document = current.document
        check(shared.source == current.document.source)
        check(shared.index.bodyStart == document.bodyStart)
        check(shared.index.heading == document.heading)
        check(shared.index.checkboxes == document.checkboxes)
        check(opening.source == shared.source && opening.preview.index.heading == document.heading)
        check(shared.state.content.startsWith(opening.preview.state.content))
        check(current.state.content == shared.state.content) { "Preview render contents differ for $profile" }
        check(current.mathSpans == shared.mathSpans) { "Preview math spans differ for $profile" }
        check(document.bodyStart == FRONTMATTER.length && document.body == input.rawBody)
        check(document.title == TITLE && document.heading == TITLE)
        check(document.checkboxes.size == 2 * input.sections)
        check(document.checkboxes.all { input.source.substring(it.range) == if (it.checked) "[x]" else "[ ]" })
        check(document.links.size == 2 * input.sections)
        check(document.links.all { input.source.substring(it.targetRange) == it.target })
        check(document.links.first().let { it.target == "notes/topic-0" && it.heading == "背景" && it.alias == "相关笔记" })
        check(document.links[1].let { it.embedded && it.target == "assets/chart-0.png" })
        check(current.state.content.length == input.rawBody.length)
        check(current.mathSpans.count { !it.display } == input.sections)
        val displayMathCount = if (profile == Profile.DISPLAY_MATH) input.sections else 0
        check(current.mathSpans.count { it.display && it.block } == displayMathCount)
        check(current.state.content.count { it == '\u200B' } == displayMathCount)
        verifyReferences(current.state)
        verifyReferences(shared.state)
        verifyTree(jetBrainsGfmAst(), input.rawBody, wikiImages = false)
        verifyTree(jetBrainsWikiAst(), input.rawBody, wikiImages = true)
        verifyTree(current.state.node, current.state.content, wikiImages = true)
        verifyTree(shared.state.node, shared.state.content, wikiImages = true)
    }

    private fun verifyReferences(state: State.Success) {
        check(state.linksLookedUp)
        for (section in listOf(0, input.sections - 1)) {
            check(state.referenceLinkHandler.find("[ref-$section]") == "https://example.com/docs/$section") {
                "Reference definition for section $section did not resolve"
            }
        }
    }

    private fun verifyTree(tree: ASTNode, body: String, wikiImages: Boolean) {
        check(tree.type == MarkdownElementTypes.MARKDOWN_FILE && tree.startOffset == 0 && tree.endOffset == body.length)
        check(tree.children.count { it.type == GFMElementTypes.TABLE } == input.sections)
        check(tree.children.count { it.type == MarkdownElementTypes.LINK_DEFINITION } == input.sections)
        check(tree.children.count { it.type == MarkdownElementTypes.CODE_FENCE } == input.sections)
        if (wikiImages) {
            check(tree.children.count { paragraph -> paragraph.children.any { it.type == MarkdownElementTypes.IMAGE } } == input.sections)
        }
    }

    private fun parseRawBody(flavour: MarkdownFlavourDescriptor): ASTNode =
        MarkdownParser(flavour, cancellationToken = cancellation).buildMarkdownTreeFromString(input.rawBody as CharSequence)

    private fun makeInput(targetKiB: Int): Input {
        require(targetKiB in 1 until 2048)
        val body = StringBuilder("# $TITLE\n\n")
        var bytes = FRONTMATTER.toByteArray().size + body.toString().toByteArray().size
        var sections = 0
        while (bytes < targetKiB * 1024) {
            val section = makeSection(sections++)
            bytes += section.toByteArray().size
            body.append(section)
        }
        val rawBody = body.toString()
        return Input(FRONTMATTER + rawBody, rawBody, sections)
    }

    private fun makeSection(number: Int): String = buildString {
        appendLine("## 第 $number 节")
        appendLine()
        appendLine("这是一段用于验证 Markdown 预览的笔记正文，包含 **重点**、*术语* 和一段 `[[ignored-inline]]` 代码。")
        appendLine("段落继续记录版本、来源和下一步操作，并用 [参考资料][ref-$number] 标注出处。行内公式为 \$x^2+y^2=z^2\$。")
        appendLine()
        appendLine("- [ ] 记录解析结果")
        appendLine("- [x] 整理原始输入")
        appendLine()
        appendLine("| 项目 | 状态 |")
        appendLine("| --- | --- |")
        appendLine("| 解析 | 完成 |")
        appendLine("| 校验 | 待办 |")
        appendLine()
        appendLine("参见 [[notes/topic-$number#背景|相关笔记]]。")
        appendLine()
        appendLine("![[assets/chart-$number.png|320]]")
        appendLine()
        appendLine("```md")
        appendLine("- [ ] [[ignored-fence]]")
        appendLine("```")
        appendLine()
        if (profile == Profile.DISPLAY_MATH) {
            appendLine("\$\$")
            appendLine("\\begin{aligned}")
            appendLine("a &= b + $number \\\\")
            appendLine()
            appendLine("c &= d + $number")
            appendLine("\\end{aligned}")
            appendLine("\$\$")
            appendLine()
        }
        appendLine("[ref-$number]: https://example.com/docs/$number")
        appendLine()
    }

    private companion object {
        const val FRONTMATTER = "---\ntags: [benchmark, markdown]\n---\n"
        const val TITLE = "Markdown 解析记录"
        const val FALLBACK_TITLE = "Untitled benchmark note"
    }
}
