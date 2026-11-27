package com.example.looma.ui.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes

enum class MarkdownDensity {
    Comfortable,
    Compact,
}

enum class MarkdownBlockKind {
    H1,
    H2,
    H3,
    H4,
    H5,
    H6,
    Paragraph,
    List,
    BlockQuote,
    CodeBlock,
    Table,
    Divider,
    Image,
    MathBlock,
    Other,
}

@Immutable
data class MarkdownSpacingSpec(
    val paragraphToParagraph: Dp,
    val paragraphToList: Dp,
    val listToParagraph: Dp,
    val afterH1: Dp,
    val afterH2: Dp,
    val afterMinorHeading: Dp,
    val h1ToH2: Dp,
    val h2ToH3: Dp,
    val headingToHeading: Dp,
    val beforeH1: Dp,
    val beforeH2: Dp,
    val beforeH3: Dp,
    val beforeMinorHeading: Dp,
    val dividerSpacing: Dp,
    val containerSpacing: Dp,
    val defaultSpacing: Dp,
) {
    fun spacingBetween(previous: MarkdownBlockKind, current: MarkdownBlockKind): Dp {
        if (previous.isHeading() && current.isHeading()) {
            return when {
                previous == MarkdownBlockKind.H1 && current == MarkdownBlockKind.H2 -> h1ToH2
                previous == MarkdownBlockKind.H2 && current == MarkdownBlockKind.H3 -> h2ToH3
                else -> headingToHeading
            }
        }
        if (previous.isHeading()) {
            return when (previous) {
                MarkdownBlockKind.H1 -> afterH1
                MarkdownBlockKind.H2 -> afterH2
                else -> afterMinorHeading
            }
        }
        if (current.isHeading()) {
            return when (current) {
                MarkdownBlockKind.H1 -> beforeH1
                MarkdownBlockKind.H2 -> beforeH2
                MarkdownBlockKind.H3 -> beforeH3
                else -> beforeMinorHeading
            }
        }
        if (previous == MarkdownBlockKind.Divider || current == MarkdownBlockKind.Divider) {
            return dividerSpacing
        }
        if (previous.isContainer() || current.isContainer()) {
            return containerSpacing
        }
        return when {
            previous == MarkdownBlockKind.Paragraph && current == MarkdownBlockKind.Paragraph -> paragraphToParagraph
            previous == MarkdownBlockKind.Paragraph && current == MarkdownBlockKind.List -> paragraphToList
            previous == MarkdownBlockKind.List && current == MarkdownBlockKind.Paragraph -> listToParagraph
            else -> defaultSpacing
        }
    }
}

private fun MarkdownBlockKind.isHeading(): Boolean = when (this) {
    MarkdownBlockKind.H1,
    MarkdownBlockKind.H2,
    MarkdownBlockKind.H3,
    MarkdownBlockKind.H4,
    MarkdownBlockKind.H5,
    MarkdownBlockKind.H6,
    -> true
    else -> false
}

private fun MarkdownBlockKind.isContainer(): Boolean = when (this) {
    MarkdownBlockKind.CodeBlock,
    MarkdownBlockKind.Table,
    MarkdownBlockKind.BlockQuote,
    MarkdownBlockKind.Image,
    MarkdownBlockKind.MathBlock,
    -> true
    else -> false
}

@Immutable
data class MarkdownListSpec(
    val bulletSize: Dp,
    val bulletGutterWidth: Dp,
    val bulletGap: Dp,
    val orderedMinWidth: Dp,
    val orderedGap: Dp,
    val checkboxSize: Dp,
    val checkboxGap: Dp,
    val nestedIndent: Dp,
    val itemSpacing: Dp,
    val looseItemSpacing: Dp,
    val nestedBlockSpacing: Dp,
    val nestedListTopSpacing: Dp,
    val checkboxCornerPercent: Int = 30,
)

@Immutable
data class MarkdownBlockQuoteSpec(
    val barWidth: Dp,
    val contentPadding: PaddingValues,
    val innerBlockSpacing: Dp,
    val nestedQuoteSpacing: Dp,
)

@Immutable
data class MarkdownCodeBlockSpec(
    val contentPadding: PaddingValues,
    val borderWidth: Dp = 1.dp,
)

@Immutable
data class MarkdownTableSpec(
    val cellHorizontalPadding: Dp,
    val cellVerticalPadding: Dp,
    val minColumnWidth: Dp,
    val maxColumnWidth: Dp = 240.dp,
    val borderWidth: Dp = 1.dp,
    val constrainToMinWidth: Boolean,
)

@Immutable
data class MarkdownDividerSpec(
    val thickness: Dp = 1.dp,
)

@Immutable
data class MarkdownMathBlockSpec(
    val innerVerticalPadding: Dp,
)

@Immutable
data class MarkdownLayoutSpec(
    val density: MarkdownDensity,
    val spacing: MarkdownSpacingSpec,
    val list: MarkdownListSpec,
    val blockQuote: MarkdownBlockQuoteSpec,
    val codeBlock: MarkdownCodeBlockSpec,
    val table: MarkdownTableSpec,
    val divider: MarkdownDividerSpec = MarkdownDividerSpec(),
    val mathBlock: MarkdownMathBlockSpec,
) {
    fun spacingBetween(previous: MarkdownBlockKind, current: MarkdownBlockKind): Dp =
        spacing.spacingBetween(previous, current)
}

object MarkdownLayoutPresets {
    val Comfortable = MarkdownLayoutSpec(
        density = MarkdownDensity.Comfortable,
        spacing = MarkdownSpacingSpec(
            paragraphToParagraph = 14.dp,
            paragraphToList = 8.dp,
            listToParagraph = 14.dp,
            afterH1 = 12.dp,
            afterH2 = 10.dp,
            afterMinorHeading = 6.dp,
            h1ToH2 = 12.dp,
            h2ToH3 = 10.dp,
            headingToHeading = 8.dp,
            beforeH1 = 28.dp,
            beforeH2 = 22.dp,
            beforeH3 = 16.dp,
            beforeMinorHeading = 14.dp,
            dividerSpacing = 20.dp,
            containerSpacing = 16.dp,
            defaultSpacing = 14.dp,
        ),
        list = MarkdownListSpec(
            bulletSize = 5.5.dp,
            bulletGutterWidth = 14.dp,
            bulletGap = 6.dp,
            orderedMinWidth = 18.dp,
            orderedGap = 6.dp,
            checkboxSize = 18.dp,
            checkboxGap = 8.dp,
            nestedIndent = 0.dp,
            itemSpacing = 6.dp,
            looseItemSpacing = 8.dp,
            nestedBlockSpacing = 6.dp,
            nestedListTopSpacing = 4.dp,
        ),
        blockQuote = MarkdownBlockQuoteSpec(
            barWidth = 3.dp,
            contentPadding = PaddingValues(start = 14.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
            innerBlockSpacing = 8.dp,
            nestedQuoteSpacing = 6.dp,
        ),
        codeBlock = MarkdownCodeBlockSpec(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        ),
        table = MarkdownTableSpec(
            cellHorizontalPadding = 10.dp,
            cellVerticalPadding = 8.dp,
            minColumnWidth = 80.dp,
            constrainToMinWidth = false,
        ),
        mathBlock = MarkdownMathBlockSpec(
            innerVerticalPadding = 4.dp,
        ),
    )

    val Compact = MarkdownLayoutSpec(
        density = MarkdownDensity.Compact,
        spacing = MarkdownSpacingSpec(
            paragraphToParagraph = 6.dp,
            paragraphToList = 4.dp,
            listToParagraph = 6.dp,
            afterH1 = 4.dp,
            afterH2 = 4.dp,
            afterMinorHeading = 2.dp,
            h1ToH2 = 4.dp,
            h2ToH3 = 4.dp,
            headingToHeading = 4.dp,
            beforeH1 = 10.dp,
            beforeH2 = 8.dp,
            beforeH3 = 6.dp,
            beforeMinorHeading = 6.dp,
            dividerSpacing = 8.dp,
            containerSpacing = 6.dp,
            defaultSpacing = 6.dp,
        ),
        list = MarkdownListSpec(
            bulletSize = 4.5.dp,
            bulletGutterWidth = 10.dp,
            bulletGap = 4.dp,
            orderedMinWidth = 14.dp,
            orderedGap = 4.dp,
            checkboxSize = 14.dp,
            checkboxGap = 6.dp,
            nestedIndent = 0.dp,
            itemSpacing = 3.dp,
            looseItemSpacing = 4.dp,
            nestedBlockSpacing = 3.dp,
            nestedListTopSpacing = 2.dp,
        ),
        blockQuote = MarkdownBlockQuoteSpec(
            barWidth = 2.dp,
            contentPadding = PaddingValues(start = 10.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
            innerBlockSpacing = 4.dp,
            nestedQuoteSpacing = 4.dp,
        ),
        codeBlock = MarkdownCodeBlockSpec(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        ),
        table = MarkdownTableSpec(
            cellHorizontalPadding = 6.dp,
            cellVerticalPadding = 4.dp,
            minColumnWidth = 64.dp,
            constrainToMinWidth = true,
        ),
        mathBlock = MarkdownMathBlockSpec(
            innerVerticalPadding = 2.dp,
        ),
    )

    fun forDensity(density: MarkdownDensity): MarkdownLayoutSpec = when (density) {
        MarkdownDensity.Comfortable -> Comfortable
        MarkdownDensity.Compact -> Compact
    }
}

val LocalMarkdownLayoutSpec = staticCompositionLocalOf { MarkdownLayoutPresets.Comfortable }

@Immutable
internal data class PlannedMarkdownBlock(
    val node: ASTNode,
    val kind: MarkdownBlockKind,
    val visible: Boolean,
    val topSpacing: Dp,
)

internal fun planDocumentBlocks(
    children: List<ASTNode>,
    layout: MarkdownLayoutSpec,
    showImages: Boolean,
): List<PlannedMarkdownBlock> {
    var previousKind: MarkdownBlockKind? = null
    return children.map { node ->
        if (node.type == MarkdownTokenTypes.EOL || node.type == MarkdownTokenTypes.WHITE_SPACE) {
            PlannedMarkdownBlock(node = node, kind = MarkdownBlockKind.Other, visible = false, topSpacing = 0.dp)
        } else {
            val kind = node.classifyBlockKind(showImages)
            if (!showImages && node.type == MarkdownElementTypes.PARAGRAPH && node.isImageOnlyParagraph()) {
                PlannedMarkdownBlock(node = node, kind = kind, visible = false, topSpacing = 0.dp)
            } else {
                val topSpacing = previousKind?.let { layout.spacingBetween(it, kind) } ?: 0.dp
                previousKind = kind
                PlannedMarkdownBlock(node = node, kind = kind, visible = true, topSpacing = topSpacing)
            }
        }
    }
}

private fun ASTNode.classifyBlockKind(showImages: Boolean): MarkdownBlockKind = when (type) {
    MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> MarkdownBlockKind.H1
    MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> MarkdownBlockKind.H2
    MarkdownElementTypes.ATX_3 -> MarkdownBlockKind.H3
    MarkdownElementTypes.ATX_4 -> MarkdownBlockKind.H4
    MarkdownElementTypes.ATX_5 -> MarkdownBlockKind.H5
    MarkdownElementTypes.ATX_6 -> MarkdownBlockKind.H6
    MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> MarkdownBlockKind.List
    MarkdownElementTypes.BLOCK_QUOTE -> MarkdownBlockKind.BlockQuote
    MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> MarkdownBlockKind.CodeBlock
    GFMElementTypes.TABLE -> MarkdownBlockKind.Table
    MarkdownTokenTypes.HORIZONTAL_RULE -> MarkdownBlockKind.Divider
    MarkdownElementTypes.IMAGE -> MarkdownBlockKind.Image
    MarkdownElementTypes.PARAGRAPH -> when {
        isMathBlockParagraph() -> MarkdownBlockKind.MathBlock
        showImages && isImageOnlyParagraph() -> MarkdownBlockKind.Image
        else -> MarkdownBlockKind.Paragraph
    }
    else -> MarkdownBlockKind.Other
}

private fun ASTNode.isMathBlockParagraph(): Boolean {
    var hasBlockMath = false
    for (child in children) {
        when (child.type) {
            GFMElementTypes.BLOCK_MATH -> hasBlockMath = true
            MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE -> Unit
            else -> return false
        }
    }
    return hasBlockMath
}

private fun ASTNode.isImageOnlyParagraph(): Boolean {
    var hasImage = false
    for (child in children) {
        when {
            child.isStandaloneImageNode() -> hasImage = true
            child.type == MarkdownTokenTypes.EOL ||
                child.type == MarkdownTokenTypes.WHITE_SPACE ||
                child.type == MarkdownTokenTypes.BLOCK_QUOTE ||
                child.type == MarkdownTokenTypes.HARD_LINE_BREAK -> Unit
            else -> return false
        }
    }
    return hasImage
}

private fun ASTNode.isStandaloneImageNode(): Boolean {
    if (type == MarkdownElementTypes.IMAGE) return true
    val labelType = when (type) {
        MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK -> MarkdownElementTypes.LINK_TEXT
        MarkdownElementTypes.SHORT_REFERENCE_LINK -> MarkdownElementTypes.LINK_LABEL
        else -> return false
    }
    val inner = findChildOfType(labelType)?.children
        ?.drop(1)
        ?.dropLast(1)
        ?.filterNot {
            it.type == MarkdownTokenTypes.WHITE_SPACE ||
                it.type == MarkdownTokenTypes.BLOCK_QUOTE ||
                it.type == MarkdownTokenTypes.HARD_LINE_BREAK
        }
        ?: return false
    return inner.singleOrNull()?.type == MarkdownElementTypes.IMAGE
}
