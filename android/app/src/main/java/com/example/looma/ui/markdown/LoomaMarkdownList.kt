package com.example.looma.ui.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.looma.R
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalOrderedListHandler
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

@Composable
internal fun LoomaMarkdownList(
    content: String,
    node: ASTNode,
    ordered: Boolean,
    depth: Int,
    layout: MarkdownLayoutSpec,
    theme: LoomaMarkdownTheme,
    onCheckboxToggle: ((position: Int, checked: Boolean) -> Unit)?,
    bodyStart: Int,
) {
    val spec = layout.list
    val items = remember(node) { node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM } }
    if (items.isEmpty()) return

    val isLoose = remember(node, items) { isLooseList(node, items) }
    val itemGap = if (isLoose) spec.looseItemSpacing else spec.itemSpacing
    val startNumber = if (ordered) {
        items.first()
            .getUnescapedTextInNode(content)
            .takeWhile(Char::isDigit)
            .toIntOrNull()
            ?: 1
    } else {
        1
    }
    val orderedStyle = theme.typography.paragraph.copy(
        color = theme.listMarkerColor,
        fontFeatureSettings = "\"tnum\" 1",
        textAlign = TextAlign.End,
    )
    val lineHeight = lineHeightOf(theme.typography.paragraph)
    val orderedHandler = LocalOrderedListHandler.current

    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, itemNode ->
            key(itemNode.startOffset) {
                if (index > 0) {
                    Spacer(Modifier.height(itemGap))
                }
                LoomaListItemRow(
                    content = content,
                    itemNode = itemNode,
                    ordered = ordered,
                    index = index,
                    startNumber = startNumber,
                    depth = depth,
                    layout = layout,
                    theme = theme,
                    orderedStyle = orderedStyle,
                    lineHeight = lineHeight,
                    orderedHandler = orderedHandler,
                    onCheckboxToggle = onCheckboxToggle,
                    bodyStart = bodyStart,
                )
            }
        }
    }
}

@Composable
private fun LoomaListItemRow(
    content: String,
    itemNode: ASTNode,
    ordered: Boolean,
    index: Int,
    startNumber: Int,
    depth: Int,
    layout: MarkdownLayoutSpec,
    theme: LoomaMarkdownTheme,
    orderedStyle: TextStyle,
    lineHeight: Dp,
    orderedHandler: com.mikepenz.markdown.model.BulletHandler,
    onCheckboxToggle: ((position: Int, checked: Boolean) -> Unit)?,
    bodyStart: Int,
) {
    val spec = layout.list
    val checkboxNode = itemNode.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
    val indicatorNode = if (ordered) itemNode.findChildOfType(MarkdownTokenTypes.LIST_NUMBER) else null
    val isOrderedNumber = ordered && checkboxNode == null

    Row(
        modifier = Modifier
            .semantics { isTraversalGroup = true }
            .fillMaxWidth(),
    ) {
        when {
            checkboxNode != null -> {
                val checked = checkboxNode.getTextInNode(content).contains("[x]", ignoreCase = true)
                Box(
                    modifier = Modifier
                        .height(lineHeight)
                        .padding(end = spec.checkboxGap),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    CheckboxMarker(
                        checked = checked,
                        lineHeight = lineHeight,
                        size = spec.checkboxSize,
                        cornerPercent = spec.checkboxCornerPercent,
                        onToggle = onCheckboxToggle?.let { toggle ->
                            { newChecked: Boolean -> toggle(bodyStart + checkboxNode.startOffset, newChecked) }
                        },
                    )
                }
            }
            isOrderedNumber -> {
                val markerText = orderedHandler.transform(
                    type = MarkdownTokenTypes.LIST_NUMBER,
                    bullet = indicatorNode?.getUnescapedTextInNode(content),
                    index = index,
                    listNumber = startNumber,
                    depth = depth,
                ).trim()
                BasicText(
                    text = markerText,
                    style = orderedStyle,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .widthIn(min = spec.orderedMinWidth)
                        .padding(end = spec.orderedGap)
                        .alignByBaseline(),
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .width(spec.bulletGutterWidth)
                        .height(lineHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    BulletGlyph(
                        depth = depth,
                        size = spec.bulletSize,
                        color = theme.listMarkerColor,
                    )
                }
                Spacer(Modifier.width(spec.bulletGap))
            }
        }

        val contentNodes = remember(itemNode) {
            itemNode.children.filter { child ->
                child.type != MarkdownTokenTypes.LIST_BULLET &&
                    child.type != MarkdownTokenTypes.LIST_NUMBER &&
                    child.type != GFMTokenTypes.CHECK_BOX &&
                    child.type != MarkdownTokenTypes.EOL &&
                    child.type != MarkdownTokenTypes.WHITE_SPACE
            }
        }
        val components = LocalMarkdownComponents.current
        val bodyColumnModifier = if (isOrderedNumber) {
            Modifier.weight(1f).alignByBaseline()
        } else {
            Modifier.weight(1f)
        }
        Column(modifier = bodyColumnModifier) {
            contentNodes.forEachIndexed { childIndex, nestedChild ->
                key(nestedChild.startOffset) {
                    val isNestedList = nestedChild.type == MarkdownElementTypes.ORDERED_LIST ||
                        nestedChild.type == MarkdownElementTypes.UNORDERED_LIST
                    if (childIndex > 0) {
                        val gap = if (isNestedList) spec.nestedListTopSpacing else spec.nestedBlockSpacing
                        Spacer(Modifier.height(gap))
                    }
                    if (isNestedList) {
                        val nestedModifier = if (spec.nestedIndent > 0.dp) {
                            Modifier.padding(start = spec.nestedIndent)
                        } else {
                            Modifier
                        }
                        Box(nestedModifier) {
                            LoomaMarkdownList(
                                content = content,
                                node = nestedChild,
                                ordered = nestedChild.type == MarkdownElementTypes.ORDERED_LIST,
                                depth = depth + 1,
                                layout = layout,
                                theme = theme,
                                onCheckboxToggle = onCheckboxToggle,
                                bodyStart = bodyStart,
                            )
                        }
                    } else {
                        MarkdownElement(
                            node = nestedChild,
                            components = components,
                            content = content,
                            includeSpacer = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BulletGlyph(
    depth: Int,
    size: Dp,
    color: Color,
) {
    Canvas(modifier = Modifier.size(size)) {
        val diameter = this.size.minDimension
        when (depth % 3) {
            0 -> drawCircle(
                color = color,
                radius = diameter / 2f,
            )
            1 -> {
                val stroke = 1.25.dp.toPx()
                drawCircle(
                    color = color,
                    radius = (diameter - stroke) / 2f,
                    style = Stroke(width = stroke),
                )
            }
            else -> {
                val side = diameter * 0.88f
                val offset = (diameter - side) / 2f
                val corner = 1.dp.toPx()
                drawRoundRect(
                    color = color,
                    topLeft = Offset(offset, offset),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(corner, corner),
                )
            }
        }
    }
}

@Composable
internal fun LoomaMarkdownBlockQuote(
    content: String,
    node: ASTNode,
    layout: MarkdownLayoutSpec,
    theme: LoomaMarkdownTheme,
) {
    val spec = layout.blockQuote
    val barColor = theme.blockQuoteBar
    val components = LocalMarkdownComponents.current
    val a11yLabels = LocalMarkdownA11yLabels.current
    val baseTypography = LocalMarkdownTypography.current
    val quoteStyle = theme.typography.quote
    val quoteTypography = remember(baseTypography, quoteStyle) {
        if (baseTypography is DefaultMarkdownTypography) {
            baseTypography.copy(
                text = quoteStyle,
                paragraph = quoteStyle,
                ordered = quoteStyle,
                bullet = quoteStyle,
                list = quoteStyle,
            )
        } else {
            object : MarkdownTypography by baseTypography {
                override val text: TextStyle = quoteStyle
                override val paragraph: TextStyle = quoteStyle
                override val ordered: TextStyle = quoteStyle
                override val bullet: TextStyle = quoteStyle
                override val list: TextStyle = quoteStyle
            }
        }
    }

    val children = remember(node) {
        node.children.filter { child ->
            child.type != MarkdownTokenTypes.EOL &&
                child.type != MarkdownTokenTypes.WHITE_SPACE &&
                child.type != MarkdownTokenTypes.BLOCK_QUOTE
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yLabels.blockquote }
            .clip(theme.blockQuoteShape)
            .background(theme.blockQuoteContainer)
            .drawBehind {
                val stroke = spec.barWidth.toPx()
                val x = if (layoutDirection == LayoutDirection.Ltr) stroke / 2f else size.width - stroke / 2f
                drawLine(
                    color = barColor,
                    strokeWidth = stroke,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                )
            }
            .padding(spec.contentPadding),
    ) {
        CompositionLocalProvider(LocalMarkdownTypography provides quoteTypography) {
            children.forEachIndexed { index, child ->
                key(child.startOffset) {
                    if (index > 0) {
                        val gap = if (child.type == MarkdownElementTypes.BLOCK_QUOTE) {
                            spec.nestedQuoteSpacing
                        } else {
                            spec.innerBlockSpacing
                        }
                        Spacer(Modifier.height(gap))
                    }
                    if (child.type == MarkdownElementTypes.BLOCK_QUOTE) {
                        LoomaMarkdownBlockQuote(
                            content = content,
                            node = child,
                            layout = layout,
                            theme = theme,
                        )
                    } else {
                        MarkdownElement(
                            node = child,
                            components = components,
                            content = content,
                            includeSpacer = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckboxMarker(
    checked: Boolean,
    lineHeight: Dp,
    size: Dp,
    cornerPercent: Int,
    onToggle: ((Boolean) -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = cornerPercent)
    val boxModifier = Modifier
        .size(size)
        .then(
            if (onToggle != null) {
                Modifier
                    .wrapContentSize(unbounded = true)
                    .requiredSize(width = CheckboxTouchTarget, height = maxOf(lineHeight, size))
                    .toggleable(
                        value = checked,
                        interactionSource = null,
                        indication = ripple(bounded = false, radius = CheckboxTouchTarget / 2),
                        role = Role.Checkbox,
                        onValueChange = onToggle,
                    )
                    .wrapContentSize()
                    .size(size)
            } else {
                Modifier
            },
        )
        .clip(shape)
        .border(1.5.dp, if (checked) colors.primary else colors.outline, shape)
        .background(if (checked) colors.primary else Color.Transparent, shape)
    Box(boxModifier, contentAlignment = Alignment.Center) {
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(size * 0.72f),
            )
        }
    }
}

private fun isLooseList(listNode: ASTNode, items: List<ASTNode>): Boolean {
    var seenItem = false
    for (child in listNode.children) {
        if (child.type == MarkdownElementTypes.LIST_ITEM) {
            if (seenItem) continue
            seenItem = true
        } else if (seenItem && child.type == MarkdownTokenTypes.EOL) {
            return true
        }
    }
    return items.any { item ->
        var blockCount = 0
        for (child in item.children) {
            if (child.type != MarkdownTokenTypes.LIST_BULLET &&
                child.type != MarkdownTokenTypes.LIST_NUMBER &&
                child.type != GFMTokenTypes.CHECK_BOX &&
                child.type != MarkdownTokenTypes.EOL &&
                child.type != MarkdownTokenTypes.WHITE_SPACE
            ) {
                blockCount++
                if (blockCount > 1) return@any true
            }
        }
        false
    }
}

private val CheckboxTouchTarget = 48.dp

@Composable
private fun lineHeightOf(style: TextStyle): Dp {
    val resolved = if (style.lineHeight.isSp) style.lineHeight.value else style.fontSize.value * 1.5f
    return with(LocalDensity.current) { resolved.sp.toDp() }
}
