package com.example.looma.ui.markdown

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.ui.theme.LoomaPreviewTypography
import com.example.looma.ui.theme.LoomaTokens
import com.example.looma.ui.theme.LoomaTypography
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownExtendedSpans
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

@Immutable
data class LoomaMarkdownTheme(
    val colors: MarkdownColors,
    val typography: MarkdownTypography,
    val codeShape: Shape,
    val codeBorder: Color,
    val tableShape: Shape,
    val tableHeader: Color,
    val blockQuoteContainer: Color,
    val blockQuoteShape: Shape,
    val blockQuoteBar: Color,
    val listMarkerColor: Color,
    val extendedSpans: MarkdownExtendedSpans,
)

@Immutable
private data class StableMarkdownExtendedSpans(
    private val background: Color,
    private val corner: CornerSize,
) : MarkdownExtendedSpans {
    override val extendedSpans: @Composable () -> ExtendedSpans = {
        remember(background, corner) {
            ExtendedSpans(InlineCodeBackground(background, corner))
        }
    }
}

@Immutable
internal data class LoomaMarkdownScale(
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
    val code: TextStyle,
    val inlineCode: TextStyle,
)

internal fun loomaMarkdownScale(
    type: LoomaTypography,
    base: Typography,
    density: MarkdownDensity = MarkdownDensity.Comfortable,
    preview: LoomaPreviewTypography? = null,
): LoomaMarkdownScale {
    val compact = density == MarkdownDensity.Compact
    return LoomaMarkdownScale(
        h1 = if (compact) preview?.headings?.h1 ?: base.titleMedium else type.noteHeadings.h1,
        h2 = if (compact) preview?.headings?.h2 ?: base.titleSmall else type.noteHeadings.h2,
        h3 = if (compact) preview?.headings?.h3 ?: base.titleSmall else type.noteHeadings.h3,
        h4 = if (compact) preview?.headings?.h4 ?: base.titleSmall else type.noteHeadings.h4,
        h5 = if (compact) preview?.headings?.h5 ?: base.titleSmall else type.noteHeadings.h5,
        h6 = if (compact) preview?.headings?.h6 ?: base.titleSmall else type.noteHeadings.h6,
        code = if (compact) preview?.code ?: base.bodySmall.copy(fontFamily = type.noteCode.fontFamily) else type.noteCode,
        inlineCode = if (compact) preview?.code ?: base.bodySmall.copy(fontFamily = type.noteCode.fontFamily) else type.noteCode,
    )
}

@Composable
fun loomaMarkdownTheme(
    base: TextStyle,
    density: MarkdownDensity = MarkdownDensity.Comfortable,
    preview: LoomaPreviewTypography? = null,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    onSelectedContainer: Boolean = false,
): LoomaMarkdownTheme {
    val compact = density == MarkdownDensity.Compact
    val colors = LoomaTokens.colors
    val scheme = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val inlineCorner = shapes.extraSmall.topStart
    val selectedCompact = compact && onSelectedContainer
    val inset = when {
        selectedCompact -> colors.card
        compact -> colors.cardInset
        else -> scheme.surfaceContainer
    }
    val inlineBackground = when {
        selectedCompact -> colors.card
        compact -> colors.cardInset
        else -> scheme.surfaceContainerHigh
    }
    val type = LoomaTokens.type
    val baseType = MaterialTheme.typography
    val scale = remember(type, baseType, density, preview) { loomaMarkdownScale(type, baseType, density, preview) }
    val extendedSpans = remember(inlineBackground, inlineCorner) {
        StableMarkdownExtendedSpans(inlineBackground, inlineCorner)
    }
    return LoomaMarkdownTheme(
        colors = markdownColor(
            text = textColor,
            codeBackground = if (compact) inset else scheme.surfaceContainerLow,
            inlineCodeBackground = inlineBackground,
            dividerColor = scheme.outlineVariant,
            tableBackground = Color.Transparent,
            highlightText = if (selectedCompact) scheme.onPrimaryContainer else scheme.onSecondaryContainer,
            highlightBackground = if (selectedCompact) scheme.primaryContainer else scheme.secondaryContainer,
        ),
        typography = markdownTypography(
            text = base.copy(color = textColor),
            paragraph = base.copy(color = textColor),
            list = base.copy(color = textColor),
            ordered = base.copy(color = textColor),
            bullet = base.copy(color = textColor),
            quote = base.copy(
                fontStyle = FontStyle.Normal,
                color = if (selectedCompact) textColor else scheme.onSurfaceVariant,
            ),
            code = scale.code.copy(color = textColor),
            inlineCode = scale.inlineCode.copy(color = textColor),
            table = base.copy(color = textColor),
            h1 = scale.h1.copy(color = textColor),
            h2 = scale.h2.copy(color = textColor),
            h3 = scale.h3.copy(color = textColor),
            h4 = scale.h4.copy(color = textColor),
            h5 = scale.h5.copy(color = textColor),
            h6 = scale.h6.copy(color = scheme.onSurfaceVariant),
        ),
        codeShape = if (compact) shapes.medium else shapes.large,
        codeBorder = scheme.outlineVariant,
        tableShape = if (compact) shapes.small else shapes.medium,
        tableHeader = inset,
        blockQuoteContainer = if (compact) inset else scheme.surfaceContainerLow,
        blockQuoteShape = if (compact) shapes.small else shapes.medium,
        blockQuoteBar = when {
            selectedCompact -> textColor
            compact -> scheme.outline
            else -> scheme.primary
        },
        listMarkerColor = if (selectedCompact) textColor else scheme.onSurfaceVariant,
        extendedSpans = extendedSpans,
    )
}

@Composable
internal fun zeroMarkdownPadding(): MarkdownPadding {
    val padding = markdownPadding(
        block = 0.dp,
        list = 0.dp,
        listItemTop = 0.dp,
        listItemBottom = 0.dp,
        listIndent = 0.dp,
        codeBlock = PaddingValues(0.dp),
        blockQuote = PaddingValues(0.dp),
        blockQuoteText = PaddingValues(0.dp),
        blockQuoteBar = PaddingValues.Absolute(0.dp),
    )
    return remember(padding) { padding }
}

internal val loomaAnnotatorConfig = markdownAnnotatorConfig(
    eolAsNewLine = true,
    inlineImageAsBlock = false,
)

internal fun loomaMarkdownComponents(
    layout: MarkdownLayoutSpec,
    theme: LoomaMarkdownTheme,
    onCheckboxToggle: ((position: Int, checked: Boolean) -> Unit)? = null,
    bodyStart: Int = 0,
    showImages: Boolean = true,
    wiki: WikiContext? = null,
    onWikiLinkClick: (String) -> Unit = {},
    resolvedLink: SpanStyle = SpanStyle(),
    missingLink: SpanStyle = SpanStyle(),
    math: MathRenderState? = null,
    checkedText: Color? = null,
): MarkdownComponents {
    val links: WikiAnnotator? = wiki?.let { context ->
        val codeBackground = theme.colors.inlineCodeBackground
        { text: AnnotatedString ->
            text.withWikiLinks(
                codeBackground = codeBackground,
                isResolved = { target -> context.resolve(target) != null },
                onClick = onWikiLinkClick,
                resolvedStyle = resolvedLink,
                missingStyle = missingLink,
            )
        }
    }

    val code: @Composable (String, String?, TextStyle) -> Unit = { text, language, style ->
        MarkdownCodeBackground(
            color = theme.colors.codeBackground,
            shape = theme.codeShape,
            modifier = Modifier
                .fillMaxWidth()
                .border(layout.codeBlock.borderWidth, theme.codeBorder, theme.codeShape),
            language = language,
            code = text,
        ) {
            BasicText(
                text = text,
                style = style,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(layout.codeBlock.contentPadding),
            )
        }
    }

    return markdownComponents(
        table = { model -> LoomaMarkdownTable(model, theme, layout.table) },
        blockQuote = { model ->
            LoomaMarkdownBlockQuote(
                content = model.content,
                node = model.node,
                layout = layout,
                theme = theme,
            )
        },
        codeFence = { model -> MarkdownCodeFence(model.content, model.node, theme.typography.code, code) },
        codeBlock = { model -> MarkdownCodeBlock(model.content, model.node, theme.typography.code, code) },
        horizontalRule = {
            MarkdownDivider(
                thickness = layout.divider.thickness,
                color = theme.colors.dividerColor,
            )
        },
        heading1 = heading(theme.typography.h1, MarkdownTokenTypes.ATX_CONTENT, links, math),
        heading2 = heading(theme.typography.h2, MarkdownTokenTypes.ATX_CONTENT, links, math),
        heading3 = heading(theme.typography.h3, MarkdownTokenTypes.ATX_CONTENT, links, math),
        heading4 = heading(theme.typography.h4, MarkdownTokenTypes.ATX_CONTENT, links, math),
        heading5 = heading(theme.typography.h5, MarkdownTokenTypes.ATX_CONTENT, links, math),
        heading6 = heading(theme.typography.h6, MarkdownTokenTypes.ATX_CONTENT, links, math),
        setextHeading1 = heading(theme.typography.h1, MarkdownTokenTypes.SETEXT_CONTENT, links, math),
        setextHeading2 = heading(theme.typography.h2, MarkdownTokenTypes.SETEXT_CONTENT, links, math),
        unorderedList = { model ->
            LoomaMarkdownList(
                content = model.content,
                node = model.node,
                ordered = false,
                depth = 0,
                layout = layout,
                theme = theme,
                onCheckboxToggle = onCheckboxToggle,
                bodyStart = bodyStart,
            )
        },
        orderedList = { model ->
            LoomaMarkdownList(
                content = model.content,
                node = model.node,
                ordered = true,
                depth = 0,
                layout = layout,
                theme = theme,
                onCheckboxToggle = onCheckboxToggle,
                bodyStart = bodyStart,
            )
        },
        paragraph = { model ->
            LoomaMarkdownParagraph(
                model = model,
                mathPadding = layout.mathBlock.innerVerticalPadding,
                links = links,
                math = math,
                checkedText = checkedText,
                showImages = showImages,
            )
        },
        image = if (showImages) { model -> LoomaMarkdownImage(model) } else { _ -> },
        inlineImage = if (showImages) { model -> InlineMarkdownImage(model.content) } else { _ -> },
    )
}

@Composable
internal fun RenderPlannedMarkdownBlock(
    planned: PlannedMarkdownBlock,
    components: MarkdownComponents,
    content: String,
    math: MathRenderState? = null,
) {
    if (!planned.visible) return
    val modifier = if (planned.topSpacing > 0.dp) {
        Modifier.padding(top = planned.topSpacing)
    } else {
        Modifier
    }
    Box(modifier = modifier) {
        val node = planned.node
        val range = node.startOffset until node.endOffset
        if (math != null && math.hasInlineMath(range)) {
            val inline = rememberMathInlineContent(math, math.fontSize, range)
            CompositionLocalProvider(
                LocalMarkdownInlineContent provides markdownInlineContent(inline),
            ) {
                MarkdownElement(node, components, content, includeSpacer = false)
            }
        } else {
            MarkdownElement(node, components, content, includeSpacer = false)
        }
    }
}

private typealias WikiAnnotator = (AnnotatedString) -> AnnotatedString

private fun heading(
    style: TextStyle,
    contentChildType: IElementType,
    links: WikiAnnotator?,
    math: MathRenderState?,
): MarkdownComponent = { model ->
    val settings = annotatorSettings()
    val annotated = remember(model.content, model.node, settings, links, math) {
        val child = model.node.findChildOfType(contentChildType) ?: model.node
        buildAnnotatedString {
            buildMarkdownAnnotatedString(model.content, child, settings)
        }.withSynthItalic().let { links?.invoke(it) ?: it }
    }
    val headingFontSize = if (style.fontSize.isSpecified) style.fontSize else math?.fontSize
    val content: @Composable () -> Unit = {
        MarkdownText(
            content = annotated,
            node = model.node,
            style = style,
            sourceContent = model.content,
        )
    }
    MathInlineScope(
        math = math,
        fontSize = headingFontSize,
        range = model.node.startOffset until model.node.endOffset,
        content = content,
    )
}

@Composable
private fun MathInlineScope(
    math: MathRenderState?,
    fontSize: TextUnit?,
    range: IntRange,
    content: @Composable () -> Unit,
) {
    if (math == null || fontSize == null || !math.hasInlineMath(range)) {
        content()
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = if (maxWidth != Dp.Infinity) {
            with(LocalDensity.current) { maxWidth.toPx().toSp() }
        } else {
            null
        }
        val inline = rememberMathInlineContent(
            state = math,
            fontSize = fontSize,
            range = range,
            maxWidth = available,
        )
        if (inline.isEmpty()) {
            content()
        } else {
            CompositionLocalProvider(
                LocalMarkdownInlineContent provides markdownInlineContent(inline),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun LoomaMarkdownParagraph(
    model: MarkdownComponentModel,
    mathPadding: Dp,
    links: WikiAnnotator?,
    math: MathRenderState?,
    checkedText: Color?,
    showImages: Boolean,
) {
    val block = math?.blockDisplay(model.node)
    if (block != null) {
        MathBlock(block, math.fontSize, PaddingValues(vertical = mathPadding))
        return
    }
    val style = if (checkedText != null && model.node.inCheckedTask(model.content)) {
        model.typography.paragraph.copy(color = checkedText)
    } else {
        model.typography.paragraph
    }
    val parts = remember(model.node, showImages) {
        if (showImages) paragraphParts(model.node.children) else emptyList()
    }
    if (parts.isEmpty()) {
        ParagraphText(model, model.node.children, style, links, math)
        return
    }

    Column {
        for (part in parts) {
            key(part.startOffset) {
                when (part) {
                    is ParagraphPart.Text -> ParagraphText(model, part.children, style, links, math)
                    is ParagraphPart.Image -> {
                        val image = model.copy(node = part.node)
                        if (part.link == null) {
                            LoomaMarkdownImage(image)
                        } else {
                            LinkedMarkdownImage(image, part.link) {
                                ParagraphText(model, listOf(part.link), style, links, math)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkedMarkdownImage(
    image: MarkdownComponentModel,
    linkNode: ASTNode,
    fallback: @Composable () -> Unit,
) {
    val settings = annotatorSettings()
    val link = remember(image.content, linkNode, settings) {
        val annotated = buildAnnotatedString {
            buildMarkdownAnnotatedString(image.content, listOf(linkNode), settings)
        }
        annotated.getLinkAnnotations(0, annotated.length).singleOrNull()?.item
    }
    val listener = link?.linkInteractionListener
    if (link != null && listener != null) {
        LoomaMarkdownImage(image, Modifier.clickable { listener.onClick(link) })
    } else {
        fallback()
    }
}

@Composable
private fun ParagraphText(
    model: MarkdownComponentModel,
    children: List<ASTNode>,
    style: TextStyle,
    links: WikiAnnotator?,
    math: MathRenderState?,
) {
    if (children.isEmpty()) return
    val settings = annotatorSettings()
    val annotated = remember(model.content, children, settings, links, math) {
        buildAnnotatedString {
            buildMarkdownAnnotatedString(model.content, children, settings)
        }.withSynthItalic().let { links?.invoke(it) ?: it }
    }
    if (annotated.isEmpty()) return
    MathInlineScope(
        math = math,
        fontSize = math?.fontSize,
        range = children.first().startOffset until children.last().endOffset,
    ) {
        MarkdownText(
            content = annotated,
            node = model.node,
            style = style,
            sourceContent = model.content,
        )
    }
}

private sealed interface ParagraphPart {
    val startOffset: Int

    data class Text(val children: List<ASTNode>) : ParagraphPart {
        override val startOffset: Int get() = children.first().startOffset
    }

    data class Image(val node: ASTNode, val link: ASTNode? = null) : ParagraphPart {
        override val startOffset: Int get() = link?.startOffset ?: node.startOffset
    }
}

private fun paragraphParts(children: List<ASTNode>): List<ParagraphPart> = buildList {
    var textStart = 0
    children.forEachIndexed { index, child ->
        val image = child.asParagraphImage() ?: return@forEachIndexed
        var before = index - 1
        while (before >= textStart && children[before].isImageLinePadding()) before--
        if (before >= textStart && children[before].type != MarkdownTokenTypes.EOL) {
            return@forEachIndexed
        }
        var after = index + 1
        while (after < children.size && children[after].isImageLinePadding()) after++
        if (after < children.size && children[after].type != MarkdownTokenTypes.EOL) {
            return@forEachIndexed
        }
        val text = children.subList(textStart, maxOf(textStart, before))
            .dropLastWhile { it.type == MarkdownTokenTypes.HARD_LINE_BREAK }
        if (text.isNotEmpty()) add(ParagraphPart.Text(text))
        add(image)
        textStart = minOf(children.size, after + 1)
    }
    if (isNotEmpty() && textStart < children.size) {
        add(ParagraphPart.Text(children.subList(textStart, children.size)))
    }
}

private fun ASTNode.asParagraphImage(): ParagraphPart.Image? {
    if (type == MarkdownElementTypes.IMAGE) return ParagraphPart.Image(this)
    val labelType = when (type) {
        MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK -> MarkdownElementTypes.LINK_TEXT
        MarkdownElementTypes.SHORT_REFERENCE_LINK -> MarkdownElementTypes.LINK_LABEL
        else -> return null
    }
    val image = findChildOfType(labelType)?.children
        ?.drop(1)?.dropLast(1)?.filterNot { it.isImageLinePadding() }
        ?.singleOrNull()?.takeIf { it.type == MarkdownElementTypes.IMAGE } ?: return null
    return ParagraphPart.Image(image, link = this)
}

private fun ASTNode.isImageLinePadding(): Boolean = when (type) {
    MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.BLOCK_QUOTE, MarkdownTokenTypes.HARD_LINE_BREAK -> true
    else -> false
}

private fun ASTNode.inCheckedTask(content: String): Boolean {
    val item = parent?.takeIf { it.type == MarkdownElementTypes.LIST_ITEM } ?: return false
    val box = item.children.getOrNull(1)?.takeIf { it.type == GFMTokenTypes.CHECK_BOX } ?: return false
    return box.getTextInNode(content).contains("[x]", ignoreCase = true)
}
