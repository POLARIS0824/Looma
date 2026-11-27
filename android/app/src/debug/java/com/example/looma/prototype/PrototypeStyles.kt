package com.example.looma.prototype

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.looma.R
import com.example.looma.ui.markdown.InlineCodeBackground
import com.example.looma.ui.markdown.LoomaMarkdownTable
import com.example.looma.ui.markdown.LoomaMarkdownTheme
import com.example.looma.ui.markdown.loomaAnnotatorConfig
import com.example.looma.ui.markdown.loomaMarkdownComponents
import com.example.looma.ui.markdown.loomaMarkdownTheme
import com.example.looma.ui.markdown.withSynthItalic
import com.example.looma.ui.theme.LoomaTokens
import com.mikepenz.markdown.annotator.AnnotatorSettings
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownPadding
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

internal enum class ProtoStyle(val key: String, val label: String) {
    CURRENT("current", "1. 当前"),
    MIAOYAN("miaoyan", "2. 妙言"),
    M3E("m3e", "3. M3E"),
    HYBRID("hybrid", "4. 融合");

    companion object {
        fun fromKey(key: String?): ProtoStyle = entries.firstOrNull { it.key == key } ?: HYBRID
    }
}

internal enum class ProtoView(val key: String, val label: String) {
    PREVIEW("preview", "正文"),
    HOME("home", "首页卡片 & 最近");

    companion object {
        fun fromKey(key: String?): ProtoView = entries.firstOrNull { it.key == key } ?: PREVIEW
    }
}

internal enum class CompactTier { NONE, SUMMARY, RECENT }

internal data class ProtoSurfacePalette(
    val pageBg: Color,
    val canvasBg: Color,
    val cardBg: Color,
    val cardInsetBg: Color,
    val titleColor: Color,
    val bodyColor: Color,
    val mutedColor: Color,
    val accentColor: Color,
)

@Composable
internal fun rememberProtoSurfacePalette(style: ProtoStyle, dark: Boolean): ProtoSurfacePalette {
    val scheme = MaterialTheme.colorScheme
    val looma = LoomaTokens.colors
    return when (style) {
        ProtoStyle.MIAOYAN -> if (dark) {
            ProtoSurfacePalette(
                pageBg = Color(0xFF181715),
                canvasBg = Color(0xFF1E1D1B),
                cardBg = Color(0xFF252421),
                cardInsetBg = Color(0xFF2E2C28),
                titleColor = Color(0xFFE5E0D5),
                bodyColor = Color(0xFFD6D1C6),
                mutedColor = Color(0xFFB5B1A7),
                accentColor = Color(0xFF54C59F),
            )
        } else {
            ProtoSurfacePalette(
                pageBg = Color(0xFFF6F4EE),
                canvasBg = Color(0xFFFDFCF9),
                cardBg = Color(0xFFFDFCF9),
                cardInsetBg = Color(0xFFF5F4ED),
                titleColor = Color(0xFF1F1F1F),
                bodyColor = Color(0xFF262626),
                mutedColor = Color(0xFF6B6A64),
                accentColor = Color(0xFF1C5D33),
            )
        }
        else -> ProtoSurfacePalette(
            pageBg = looma.page,
            canvasBg = looma.noteCanvas,
            cardBg = looma.card,
            cardInsetBg = looma.cardInset,
            titleColor = scheme.onSurface,
            bodyColor = scheme.onSurfaceVariant,
            mutedColor = scheme.onSurfaceVariant,
            accentColor = scheme.primary,
        )
    }
}

internal fun ASTNode.isHeadingNode(): Boolean = when (type) {
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
    -> true
    else -> false
}

/**
 * Computes inter-block vertical spacing for styles that separate
 * "heading -> following body" (tight) from "body -> next heading" (generous).
 */
internal fun blockGapAfter(
    prev: ASTNode,
    next: ASTNode,
    style: ProtoStyle,
    tier: CompactTier,
): Dp {
    val compact = tier != CompactTier.NONE
    return when (style) {
        ProtoStyle.MIAOYAN, ProtoStyle.HYBRID -> when {
            prev.isHeadingNode() && !next.isHeadingNode() -> if (compact) 3.dp else 6.dp
            !prev.isHeadingNode() && next.isHeadingNode() -> if (compact) 10.dp else 22.dp
            prev.isHeadingNode() && next.isHeadingNode() -> if (compact) 4.dp else 8.dp
            else -> if (compact) 6.dp else 14.dp
        }
        ProtoStyle.M3E -> when {
            prev.isHeadingNode() && !next.isHeadingNode() -> if (compact) 4.dp else 8.dp
            !prev.isHeadingNode() && next.isHeadingNode() -> if (compact) 12.dp else 24.dp
            else -> if (compact) 8.dp else 12.dp
        }
        ProtoStyle.CURRENT -> if (compact) 4.dp else 10.dp
    }
}

@Composable
internal fun rememberProtoMarkdownTheme(
    style: ProtoStyle,
    dark: Boolean,
    tier: CompactTier,
): LoomaMarkdownTheme {
    val compact = tier != CompactTier.NONE
    val palette = rememberProtoSurfacePalette(style, dark)
    val scheme = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val type = LoomaTokens.type
    val baseType = MaterialTheme.typography

    if (style == ProtoStyle.CURRENT) {
        // Reproduce exact current behavior: Recent uses bodySmall (12sp/16sp = 1.33x),
        // Summary uses homeNotePreview.body (16sp/24sp), Preview uses noteBody (16sp/26sp).
        val currentBase = when (tier) {
            CompactTier.RECENT -> baseType.bodySmall
            CompactTier.SUMMARY -> type.homeNotePreview.body
            CompactTier.NONE -> type.noteBody
        }
        return loomaMarkdownTheme(
            base = currentBase,
            compact = compact,
            preview = if (tier == CompactTier.SUMMARY) type.homeNotePreview else null,
            textColor = if (compact) palette.bodyColor else scheme.onSurface,
        )
    }

    val bodyText = when (style) {
        ProtoStyle.MIAOYAN, ProtoStyle.HYBRID -> when (tier) {
            CompactTier.NONE -> type.noteBody.copy(lineHeight = 26.24.sp, letterSpacing = 0.02.em)
            CompactTier.SUMMARY -> baseType.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 22.sp, letterSpacing = 0.015.em)
            CompactTier.RECENT -> baseType.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 20.sp, letterSpacing = 0.015.em)
        }
        ProtoStyle.M3E -> when (tier) {
            CompactTier.NONE -> baseType.bodyLarge.copy(lineHeight = 24.sp)
            CompactTier.SUMMARY -> baseType.bodyMedium.copy(lineHeight = 21.sp)
            CompactTier.RECENT -> baseType.bodySmall.copy(lineHeight = 18.sp)
        }
        ProtoStyle.CURRENT -> type.noteBody
    }

    val textColor = if (compact) palette.bodyColor else palette.titleColor
    val linkUnderline = style != ProtoStyle.MIAOYAN
    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = palette.accentColor,
            textDecoration = if (linkUnderline) TextDecoration.Underline else TextDecoration.None,
        ),
    )

    val inlineBg = when (style) {
        ProtoStyle.MIAOYAN -> if (dark) Color(0xFF282724) else Color(0xFFF5F4ED)
        ProtoStyle.M3E -> scheme.secondaryContainer
        ProtoStyle.HYBRID -> if (compact) palette.cardInsetBg else scheme.surfaceContainer
        ProtoStyle.CURRENT -> scheme.surfaceContainerHigh
    }
    val inlineText = if (style == ProtoStyle.M3E) scheme.onSecondaryContainer else textColor
    val codeBlockBg = when (style) {
        ProtoStyle.MIAOYAN -> if (dark) Color(0xFF282724) else Color(0xFFF5F4ED)
        ProtoStyle.M3E -> scheme.surfaceContainer
        ProtoStyle.HYBRID -> if (compact) palette.cardInsetBg else scheme.surfaceContainerLow
        ProtoStyle.CURRENT -> scheme.surfaceContainerLow
    }
    val inlineCorner = when (style) {
        ProtoStyle.MIAOYAN -> RoundedCornerShape(4.dp).topStart
        ProtoStyle.M3E -> shapes.small.topStart
        ProtoStyle.HYBRID -> shapes.extraSmall.topStart
        ProtoStyle.CURRENT -> shapes.extraSmall.topStart
    }
    val extendedSpans = markdownExtendedSpans {
        remember(inlineBg, inlineCorner) {
            ExtendedSpans(InlineCodeBackground(inlineBg, inlineCorner))
        }
    }

    val h1Style = when (style) {
        ProtoStyle.MIAOYAN -> if (compact) baseType.titleSmallEmphasized.copy(lineHeight = 20.sp) else type.noteHeadings.h1.copy(fontSize = 28.sp, lineHeight = 38.sp, letterSpacing = 0.02.em)
        ProtoStyle.M3E -> if (compact) baseType.titleMediumEmphasized else type.noteHeadings.h1
        ProtoStyle.HYBRID -> if (compact) baseType.titleSmallEmphasized.copy(lineHeight = 20.sp) else type.noteHeadings.h1.copy(lineHeight = 36.sp, letterSpacing = 0.015.em)
        ProtoStyle.CURRENT -> type.noteHeadings.h1
    }
    val h2Style = when (style) {
        ProtoStyle.MIAOYAN -> if (compact) baseType.bodyLargeEmphasized.copy(lineHeight = 20.sp) else type.noteHeadings.h2.copy(fontSize = 22.5.sp, lineHeight = 31.sp, letterSpacing = 0.02.em)
        ProtoStyle.M3E -> if (compact) baseType.titleSmallEmphasized else type.noteHeadings.h2
        ProtoStyle.HYBRID -> if (compact) baseType.bodyLargeEmphasized.copy(lineHeight = 20.sp) else type.noteHeadings.h2.copy(lineHeight = 31.sp, letterSpacing = 0.015.em)
        ProtoStyle.CURRENT -> type.noteHeadings.h2
    }
    val h3Style = when (style) {
        ProtoStyle.MIAOYAN -> when (tier) {
            CompactTier.RECENT -> baseType.labelLargeEmphasized.copy(lineHeight = 18.sp)
            CompactTier.SUMMARY -> baseType.bodyMediumEmphasized.copy(lineHeight = 20.sp)
            CompactTier.NONE -> type.noteHeadings.h3.copy(fontSize = 19.sp, lineHeight = 27.sp, letterSpacing = 0.02.em)
        }
        ProtoStyle.M3E -> if (compact) baseType.bodyLargeEmphasized else type.noteHeadings.h3
        ProtoStyle.HYBRID -> when (tier) {
            CompactTier.RECENT -> baseType.labelLargeEmphasized.copy(lineHeight = 18.sp)
            CompactTier.SUMMARY -> baseType.bodyMediumEmphasized.copy(lineHeight = 20.sp)
            CompactTier.NONE -> type.noteHeadings.h3.copy(lineHeight = 27.sp, letterSpacing = 0.015.em)
        }
        ProtoStyle.CURRENT -> type.noteHeadings.h3
    }

    val quoteStyle = when (style) {
        ProtoStyle.MIAOYAN, ProtoStyle.HYBRID -> bodyText.copy(
            fontSize = when (tier) {
                CompactTier.RECENT -> 12.sp
                CompactTier.SUMMARY -> 13.sp
                CompactTier.NONE -> 15.sp
            },
            lineHeight = when (tier) {
                CompactTier.RECENT -> 19.sp
                CompactTier.SUMMARY -> 21.sp
                CompactTier.NONE -> 24.6.sp
            },
            fontStyle = FontStyle.Normal,
            color = palette.mutedColor,
        )
        ProtoStyle.M3E -> bodyText.copy(fontStyle = FontStyle.Normal, color = scheme.onSurfaceVariant)
        ProtoStyle.CURRENT -> bodyText.copy(fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant)
    }

    val inlineCodeStyle = when (style) {
        ProtoStyle.MIAOYAN -> bodyText.copy(fontFamily = bodyText.fontFamily, color = inlineText)
        else -> (if (compact) baseType.bodySmall else type.noteCode).copy(
            fontFamily = FontFamily.Monospace,
            color = inlineText,
        )
    }

    return LoomaMarkdownTheme(
        colors = markdownColor(
            text = textColor,
            codeBackground = codeBlockBg,
            inlineCodeBackground = inlineBg,
            dividerColor = if (style == ProtoStyle.MIAOYAN) {
                if (dark) Color(0xFF3A3936) else Color(0xFFE8E6DC)
            } else {
                scheme.outlineVariant
            },
            tableBackground = Color.Transparent,
            highlightText = scheme.onSecondaryContainer,
            highlightBackground = scheme.secondaryContainer,
        ),
        typography = markdownTypography(
            text = bodyText.copy(color = textColor),
            paragraph = bodyText.copy(color = textColor),
            list = bodyText.copy(color = textColor),
            ordered = bodyText.copy(color = textColor),
            bullet = bodyText.copy(color = textColor),
            quote = quoteStyle,
            code = type.noteCode.copy(
                fontSize = if (compact) 12.sp else 13.5.sp,
                lineHeight = if (compact) 18.sp else 21.5.sp,
                color = textColor,
            ),
            inlineCode = inlineCodeStyle,
            table = bodyText.copy(color = textColor),
            h1 = h1Style.copy(color = textColor),
            h2 = h2Style.copy(color = textColor),
            h3 = h3Style.copy(color = textColor),
            h4 = type.noteHeadings.h4.copy(color = textColor),
            h5 = type.noteHeadings.h5.copy(color = textColor),
            h6 = type.noteHeadings.h6.copy(color = palette.mutedColor),
            textLink = linkStyle,
        ),
        padding = markdownPadding(
            block = 0.dp,
            list = if (compact) 3.dp else 6.dp,
            listItemTop = 0.dp,
            listItemBottom = 0.dp,
            listIndent = if (compact) 12.dp else 16.dp,
            codeBlock = PaddingValues(if (compact) 8.dp else 14.dp),
            blockQuote = PaddingValues(0.dp),
        ),
        codeShape = when (style) {
            ProtoStyle.MIAOYAN -> RoundedCornerShape(8.dp)
            ProtoStyle.M3E -> if (compact) shapes.medium else shapes.large
            ProtoStyle.HYBRID -> if (compact) shapes.small else shapes.medium
            ProtoStyle.CURRENT -> if (compact) shapes.medium else shapes.large
        },
        tableShape = if (compact) shapes.small else shapes.medium,
        tableHeader = if (compact) palette.cardInsetBg else scheme.surfaceContainer,
        extendedSpans = extendedSpans,
    )
}

@Composable
internal fun rememberProtoMarkdownComponents(
    style: ProtoStyle,
    theme: LoomaMarkdownTheme,
    dark: Boolean,
    tier: CompactTier,
): MarkdownComponents {
    val compact = tier != CompactTier.NONE
    if (style == ProtoStyle.CURRENT) {
        return remember(theme, compact) {
            loomaMarkdownComponents(
                theme = theme,
                compact = compact,
                showImages = false,
            )
        }
    }

    val scheme = MaterialTheme.colorScheme
    val palette = rememberProtoSurfacePalette(style, dark)
    val quoteBarColor = when (style) {
        ProtoStyle.MIAOYAN -> if (dark) Color(0xFF4A4843) else Color(0xFFDDD9CD)
        ProtoStyle.M3E, ProtoStyle.HYBRID -> scheme.tertiary
        ProtoStyle.CURRENT -> scheme.outline
    }
    val markerColor = when (style) {
        ProtoStyle.M3E -> scheme.primary
        ProtoStyle.HYBRID -> scheme.onSurfaceVariant
        ProtoStyle.MIAOYAN -> palette.mutedColor
        ProtoStyle.CURRENT -> scheme.onSurfaceVariant
    }

    val baseComponents = remember(theme, compact, style, dark) {
        loomaMarkdownComponents(
            theme = theme,
            compact = compact,
            showImages = false,
        )
    }

    val codeBlockRenderer: @Composable (String, String?, TextStyle) -> Unit = { text, language, codeStyle ->
        val highlighted = remember(text, style, dark) {
            highlightKotlinSample(text, style, dark, scheme.primary, scheme.tertiary, palette.mutedColor)
        }
        val borderMod = if (style == ProtoStyle.HYBRID) {
            Modifier.border(1.dp, scheme.outlineVariant, theme.codeShape)
        } else {
            Modifier
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(theme.codeShape)
                .background(theme.colors.codeBackground)
                .then(borderMod),
        ) {
            if (!language.isNullOrBlank() && !compact) {
                when (style) {
                    ProtoStyle.M3E -> {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = scheme.surfaceContainerHighest,
                            modifier = Modifier.padding(start = 12.dp, top = 10.dp),
                        ) {
                            Text(
                                text = language.uppercase(),
                                style = MaterialTheme.typography.labelSmallEmphasized,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    ProtoStyle.HYBRID -> {
                        Text(
                            text = language.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp, top = 10.dp),
                        )
                    }
                    else -> Unit
                }
            }
            BasicText(
                text = highlighted,
                style = codeStyle,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        start = if (compact) 10.dp else 14.dp,
                        end = if (compact) 10.dp else 14.dp,
                        top = if (!language.isNullOrBlank() && !compact && (style == ProtoStyle.M3E || style == ProtoStyle.HYBRID)) 6.dp else if (compact) 8.dp else 12.dp,
                        bottom = if (compact) 8.dp else 12.dp,
                    ),
            )
        }
    }

    return markdownComponents(
        table = { model -> LoomaMarkdownTable(model, theme, compact) },
        blockQuote = { model ->
            val settings = annotatorSettings()
            val paragraphs = remember(model.content, model.node, settings) {
                extractQuoteParagraphs(model.content, model.node, settings)
            }
            val internalGap = when (style) {
                ProtoStyle.MIAOYAN, ProtoStyle.HYBRID -> if (compact) 3.dp else 6.dp
                ProtoStyle.M3E -> if (compact) 4.dp else 8.dp
                ProtoStyle.CURRENT -> 10.dp
            }
            when (style) {
                ProtoStyle.MIAOYAN, ProtoStyle.HYBRID -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    ) {
                        Canvas(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .padding(vertical = if (compact) 3.dp else 5.dp),
                        ) {
                            drawLine(
                                color = quoteBarColor,
                                start = Offset(size.width / 2f, 0f),
                                end = Offset(size.width / 2f, size.height),
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                        Spacer(Modifier.width(if (compact) 8.dp else 11.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(internalGap)) {
                            paragraphs.forEach { p ->
                                MarkdownText(
                                    content = p,
                                    node = model.node,
                                    style = theme.typography.quote,
                                    sourceContent = model.content,
                                )
                            }
                        }
                    }
                }
                ProtoStyle.M3E -> {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = scheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 8.dp else 10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(quoteBarColor),
                            )
                            Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(internalGap)) {
                                paragraphs.forEach { p ->
                                    MarkdownText(
                                        content = p,
                                        node = model.node,
                                        style = theme.typography.quote,
                                        sourceContent = model.content,
                                    )
                                }
                            }
                        }
                    }
                }
                ProtoStyle.CURRENT -> Unit
            }
        },
        codeFence = { model -> MarkdownCodeFence(model.content, model.node, theme.typography.code, codeBlockRenderer) },
        codeBlock = { model -> MarkdownCodeBlock(model.content, model.node, theme.typography.code, codeBlockRenderer) },
        heading1 = protoHeading(theme.typography.h1, MarkdownTokenTypes.ATX_CONTENT),
        heading2 = protoHeading(theme.typography.h2, MarkdownTokenTypes.ATX_CONTENT),
        heading3 = protoHeading(theme.typography.h3, MarkdownTokenTypes.ATX_CONTENT),
        heading4 = protoHeading(theme.typography.h4, MarkdownTokenTypes.ATX_CONTENT),
        unorderedList = { model ->
            ProtoListBlock(
                content = model.content,
                node = model.node,
                ordered = false,
                style = style,
                textStyle = theme.typography.bullet,
                markerColor = markerColor,
                compact = compact,
            )
        },
        orderedList = { model ->
            ProtoListBlock(
                content = model.content,
                node = model.node,
                ordered = true,
                style = style,
                textStyle = theme.typography.ordered,
                markerColor = markerColor,
                compact = compact,
            )
        },
        checkbox = baseComponents.checkbox,
        paragraph = baseComponents.paragraph,
        horizontalRule = {
            HorizontalDivider(
                thickness = 1.dp,
                color = theme.colors.dividerColor,
                modifier = Modifier.padding(vertical = if (compact) 2.dp else 6.dp),
            )
        },
    )
}

@Composable
internal fun ProvideProtoMarkdownLocals(
    theme: LoomaMarkdownTheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalMarkdownColors provides theme.colors,
        LocalMarkdownTypography provides theme.typography,
        LocalMarkdownPadding provides theme.padding,
        content = content,
    )
}

@Composable
private fun ProtoListBlock(
    content: String,
    node: ASTNode,
    ordered: Boolean,
    style: ProtoStyle,
    textStyle: TextStyle,
    markerColor: Color,
    compact: Boolean,
) {
    val settings = annotatorSettings()
    val scheme = MaterialTheme.colorScheme
    val items = remember(content, node) {
        node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    }
    val itemGap = when (style) {
        ProtoStyle.MIAOYAN, ProtoStyle.HYBRID -> if (compact) 3.dp else 6.dp
        ProtoStyle.M3E -> if (compact) 4.dp else 8.dp
        ProtoStyle.CURRENT -> 4.dp
    }
    val bulletGap = when (style) {
        ProtoStyle.MIAOYAN, ProtoStyle.HYBRID -> if (compact) 5.dp else 7.dp
        ProtoStyle.M3E -> if (compact) 6.dp else 8.dp
        ProtoStyle.CURRENT -> 12.dp
    }
    val numberGap = when (style) {
        ProtoStyle.MIAOYAN, ProtoStyle.HYBRID -> if (compact) 4.dp else 6.dp
        ProtoStyle.M3E -> if (compact) 6.dp else 8.dp
        ProtoStyle.CURRENT -> 10.dp
    }
    val lineHeightDp = with(LocalDensity.current) {
        val lh = if (textStyle.lineHeight.isSp) textStyle.lineHeight.value else textStyle.fontSize.value * 1.6f
        lh.sp.toDp()
    }
    val checkSize = if (compact) 14.dp else 17.dp
    val checkGap = if (compact) 5.dp else 7.dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(itemGap),
    ) {
        items.forEachIndexed { index, item ->
            val checkNode = item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
            val paragraphNodes = item.children.filter {
                it.type == MarkdownElementTypes.PARAGRAPH || it.type == MarkdownTokenTypes.TEXT
            }
            val annotated = remember(content, item, settings) {
                buildAnnotatedString {
                    if (paragraphNodes.isNotEmpty()) {
                        paragraphNodes.forEachIndexed { pIdx, p ->
                            if (pIdx > 0) append("\n")
                            buildMarkdownAnnotatedString(content, p, settings)
                        }
                    } else {
                        val raw = item.getTextInNode(content).toString()
                            .replaceFirst(Regex("^\\s*(?:[-*+]|\\d+[.)])\\s*(?:\\[[xX ]\\]\\s*)?"), "")
                        append(raw.trim())
                    }
                }.withSynthItalic()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                if (checkNode != null) {
                    val checked = checkNode.getTextInNode(content).contains("[x]", ignoreCase = true)
                    val boxShape = RoundedCornerShape(percent = 30)
                    Box(
                        modifier = Modifier.height(lineHeightDp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(checkSize)
                                .clip(boxShape)
                                .border(1.5.dp, if (checked) scheme.primary else scheme.outline, boxShape)
                                .background(if (checked) scheme.primary else Color.Transparent, boxShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (checked) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    tint = scheme.onPrimary,
                                    modifier = Modifier.size(checkSize * 0.72f),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(checkGap))
                } else if (ordered) {
                    Text(
                        text = "${index + 1}.",
                        style = textStyle.copy(
                            color = markerColor,
                            fontFeatureSettings = "\"tnum\" 1",
                        ),
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(min = if (compact) 14.dp else 18.dp),
                    )
                    Spacer(Modifier.width(numberGap))
                } else {
                    Box(
                        modifier = Modifier.height(lineHeightDp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (compact) 4.dp else 5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(markerColor),
                        )
                    }
                    Spacer(Modifier.width(bulletGap))
                }

                MarkdownText(
                    content = annotated,
                    node = item,
                    style = textStyle,
                    modifier = Modifier.weight(1f),
                    sourceContent = content,
                )
            }
        }
    }
}

private fun protoHeading(
    style: TextStyle,
    contentChildType: IElementType,
): MarkdownComponent = { model ->
    val settings = annotatorSettings()
    val annotated = remember(model.content, model.node, settings) {
        val child = model.node.findChildOfType(contentChildType) ?: model.node
        buildAnnotatedString {
            buildMarkdownAnnotatedString(model.content, child, settings)
        }.withSynthItalic()
    }
    MarkdownText(
        content = annotated,
        node = model.node,
        style = style,
        sourceContent = model.content,
    )
}

private fun extractQuoteParagraphs(
    content: String,
    node: ASTNode,
    settings: AnnotatorSettings,
): List<AnnotatedString> {
    val paragraphs = node.children.filter { it.type == MarkdownElementTypes.PARAGRAPH }
    if (paragraphs.isEmpty()) {
        val raw = node.getTextInNode(content).toString()
            .lineSequence()
            .joinToString("\n") { it.removePrefix(">").trimStart() }
        return listOf(AnnotatedString(raw))
    }
    return paragraphs.map { p ->
        buildAnnotatedString {
            buildMarkdownAnnotatedString(content, p, settings)
        }.withSynthItalic()
    }
}

private fun highlightKotlinSample(
    code: String,
    style: ProtoStyle,
    dark: Boolean,
    primary: Color,
    tertiary: Color,
    muted: Color,
): AnnotatedString {
    val kwColor = when (style) {
        ProtoStyle.MIAOYAN -> if (dark) Color(0xFF9B79F7) else Color(0xFFD73A49)
        else -> primary
    }
    val strColor = when (style) {
        ProtoStyle.MIAOYAN -> if (dark) Color(0xFF8FFCCD) else Color(0xFF032F62)
        else -> tertiary
    }
    val numColor = when (style) {
        ProtoStyle.MIAOYAN -> if (dark) Color(0xFFF7CC8F) else Color(0xFF005CC5)
        else -> primary
    }
    val fnColor = when (style) {
        ProtoStyle.MIAOYAN -> if (dark) Color(0xFF99E0FC) else Color(0xFF6F42C1)
        else -> tertiary
    }

    return buildAnnotatedString {
        append(code)
        Regex("//[^\\n]*").findAll(code).forEach {
            addStyle(SpanStyle(color = muted, fontStyle = FontStyle.Italic), it.range.first, it.range.last + 1)
        }
        Regex("\"[^\"]*\"").findAll(code).forEach {
            addStyle(SpanStyle(color = strColor), it.range.first, it.range.last + 1)
        }
        Regex("\\b(data|class|val|var|fun|return|if|else)\\b").findAll(code).forEach {
            addStyle(SpanStyle(color = kwColor, fontWeight = FontWeight.SemiBold), it.range.first, it.range.last + 1)
        }
        Regex("\\b[A-Z][A-Za-z0-9_]*\\b").findAll(code).forEach {
            addStyle(SpanStyle(color = fnColor), it.range.first, it.range.last + 1)
        }
        Regex("\\b\\d+(?:\\.\\d+)?f?\\b").findAll(code).forEach {
            addStyle(SpanStyle(color = numColor), it.range.first, it.range.last + 1)
        }
    }
}

internal val protoAnnotator = markdownAnnotator(config = loomaAnnotatorConfig) { _, _ -> false }
