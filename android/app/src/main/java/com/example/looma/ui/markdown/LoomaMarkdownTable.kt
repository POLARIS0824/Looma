package com.example.looma.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

private data class TableCell(val node: ASTNode, val text: AnnotatedString)

@Composable
internal fun LoomaMarkdownTable(
    model: MarkdownComponentModel,
    theme: LoomaMarkdownTheme,
    spec: MarkdownTableSpec,
) {
    val settings = annotatorSettings()
    val rows = remember(model.content, model.node, settings) {
        model.node.children
            .filter { row -> row.type == GFMElementTypes.HEADER || row.type == GFMElementTypes.ROW }
            .map { row ->
                row.children.filter { it.type == GFMTokenTypes.CELL }.map { cell ->
                    val annotated = buildAnnotatedString {
                        buildMarkdownAnnotatedString(model.content, cell, settings)
                    }.withSynthItalic()
                    val start = annotated.indexOfFirst { !it.isWhitespace() }
                    val end = annotated.indexOfLast { !it.isWhitespace() } + 1
                    TableCell(cell, if (start < 0) AnnotatedString("") else annotated.subSequence(start, end))
                }
            }
    }
    val columnCount = rows.maxOfOrNull { it.size } ?: return
    if (columnCount == 0) return

    val bodyStyle = theme.typography.table
    val headerStyle = bodyStyle.copy(fontWeight = FontWeight.Bold)
    val gridColor = theme.colors.dividerColor
    val shape = theme.tableShape
    val horizontalPadding = spec.cellHorizontalPadding
    val verticalPadding = spec.cellVerticalPadding
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 0)
    val minimumWidth = with(density) {
        maxOf(spec.minColumnWidth, bodyStyle.fontSize.toDp() * 4).roundToPx()
    }
    val preferredWidths = remember(rows, headerStyle, bodyStyle, textMeasurer, minimumWidth, horizontalPadding, spec.maxColumnWidth) {
        with(density) {
            val maxWidth = maxOf(minimumWidth, spec.maxColumnWidth.roundToPx())
            val padding = horizontalPadding.roundToPx() * 2
            val textConstraints = Constraints(maxWidth = maxOf(0, maxWidth - padding))
            val widths = IntArray(columnCount) { minimumWidth }
            rows.forEachIndexed { rowIndex, row ->
                val style = if (rowIndex == 0) headerStyle else bodyStyle
                row.forEachIndexed { column, cell ->
                    if (widths[column] < maxWidth) {
                        val textWidth = textMeasurer.measure(
                            text = cell.text,
                            style = style,
                            softWrap = false,
                            maxLines = 1,
                            constraints = textConstraints,
                        ).size.width
                        widths[column] = maxOf(widths[column], textWidth + padding)
                    }
                }
            }
            widths
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(theme.colors.tableBackground)
            .border(spec.borderWidth, gridColor, shape)
            .semantics { collectionInfo = CollectionInfo(rows.size, columnCount) },
    ) {
        val viewportWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else preferredWidths.sum()
        val minimumTableWidth = minimumWidth * columnCount
        val tableWidth = when {
            viewportWidth >= minimumTableWidth -> viewportWidth
            spec.constrainToMinWidth -> minimumTableWidth
            else -> preferredWidths.sum()
        }
        val columnWidths = remember(preferredWidths, minimumWidth, tableWidth) {
            fitTableColumns(preferredWidths, minimumWidth, tableWidth)
        }
        val scrollState = if (tableWidth > viewportWidth) rememberScrollState() else null
        Column {
            val scroll = if (scrollState != null) Modifier.horizontalScroll(scrollState) else Modifier
            Column(scroll.width(with(density) { tableWidth.toDp() })) {
                rows.forEachIndexed { index, row ->
                    val header = index == 0
                    val style = if (header) headerStyle else bodyStyle
                    val fill = if (header) Modifier.background(theme.tableHeader) else Modifier
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(fill)
                            .drawBehind {
                                val stroke = 1.dp.toPx()
                                var edge = 0
                                for (column in 0 until columnWidths.lastIndex) {
                                    edge += columnWidths[column]
                                    val x = if (layoutDirection == LayoutDirection.Ltr) edge.toFloat() else size.width - edge
                                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), stroke)
                                }
                                if (index < rows.lastIndex) {
                                    val y = size.height - stroke / 2
                                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), stroke)
                                }
                            },
                    ) {
                        columnWidths.forEachIndexed { column, width ->
                            Box(
                                Modifier
                                    .width(with(density) { width.toDp() })
                                    .semantics {
                                        if (header) heading()
                                        collectionItemInfo = CollectionItemInfo(index, 1, column, 1)
                                    }
                                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                            ) {
                                val cell = row.getOrNull(column)
                                if (cell != null && cell.text.isNotEmpty()) {
                                    MarkdownText(
                                        content = cell.text,
                                        node = cell.node,
                                        style = style,
                                        sourceContent = model.content,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (scrollState != null) {
                TableScrollIndicator(scrollState, viewportWidth, tableWidth)
            }
        }
    }
}

@Composable
private fun TableScrollIndicator(state: ScrollState, viewportWidth: Int, tableWidth: Int) {
    val colors = MaterialTheme.colorScheme
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(3.dp)
            .drawBehind {
                val y = size.height / 2
                val track = (size.width - size.height).coerceAtLeast(0f)
                val thumb = (track * viewportWidth / tableWidth).coerceIn(minOf(16.dp.toPx(), track), track)
                val fraction = state.value.toFloat() / (tableWidth - viewportWidth)
                val progress = if (layoutDirection == LayoutDirection.Ltr) fraction else 1f - fraction
                val start = y + (track - thumb) * progress.coerceIn(0f, 1f)
                drawLine(colors.surfaceContainerHigh, Offset(y, y), Offset(y + track, y), size.height, StrokeCap.Round)
                drawLine(colors.outline, Offset(start, y), Offset(start + thumb, y), size.height, StrokeCap.Round)
            },
    )
}

private fun fitTableColumns(preferred: IntArray, minimum: Int, width: Int): IntArray {
    val surplus = width - minimum * preferred.size
    val demand = preferred.sumOf { it - minimum }
    if (surplus >= demand) {
        val extra = surplus - demand
        return IntArray(preferred.size) { column ->
            preferred[column] + extra / preferred.size + if (column < extra % preferred.size) 1 else 0
        }
    }

    var cumulativeDemand = 0L
    var allocated = 0
    return IntArray(preferred.size) { column ->
        cumulativeDemand += preferred[column] - minimum
        val end = (surplus * cumulativeDemand / demand).toInt()
        val columnWidth = minimum + end - allocated
        allocated = end
        columnWidth
    }
}
