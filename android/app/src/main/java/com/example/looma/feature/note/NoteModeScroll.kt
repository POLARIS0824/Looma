package com.example.looma.feature.note

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextLayoutResult
import com.example.looma.domain.markdown.ReadingBlock
import com.example.looma.domain.markdown.ReadingBlocks
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@Stable
internal class NoteModeScroll(
    private val read: LazyListState,
    private val edit: ScrollState,
) {
    var editLayout: TextLayoutResult? by mutableStateOf(null)
    var editTextOffset: Int? by mutableStateOf(null)

    fun captureRead(blocks: List<ReadingBlock>, bodyStart: Int, hasHeader: Boolean): ReadingAnchor? {
        val layout = read.layoutInfo
        if (layout.visibleItemsInfo.isEmpty()) return null
        val viewportTop = layout.viewportStartOffset
        val header = if (hasHeader) 1 else 0
        if (
            !read.canScrollBackward ||
            layout.visibleItemsInfo.any { it.index < header && it.offset + it.size > viewportTop }
        ) {
            return ReadingAnchor.atDocumentTop(bodyStart)
        }
        val item = layout.visibleItemsInfo.firstOrNull {
            blocks.getOrNull(it.index - header)?.hasContent == true && it.offset + it.size > viewportTop
        }
        if (item == null) {
            val block = blocks.lastOrNull { it.hasContent } ?: return ReadingAnchor.atDocumentTop(bodyStart)
            return ReadingAnchor.edit(bodyStart + block.end, block = null, fraction = 0f, atTop = false)
        }
        val fraction = ((viewportTop - item.offset).toFloat() / item.size).coerceIn(0f, 1f)
        return ReadingAnchor.read(blocks, bodyStart, item.index - header, fraction)
    }

    fun captureEdit(text: String): EditScrollPosition? {
        val layout = editLayout?.takeIf { it.layoutInput.text.text == text } ?: return null
        val textOffset = editTextOffset ?: return null
        val visibleTop = (edit.value - textOffset).toFloat().coerceIn(0f, layout.size.height.toFloat())
        val line = layout.getLineForVerticalPosition(visibleTop)
        return EditScrollPosition(layout, visibleTop, layout.getLineStart(line), edit.value <= textOffset)
    }

    suspend fun restoreRead(
        position: EditScrollPosition,
        blocks: List<ReadingBlock>,
        bodyStart: Int,
        hasHeader: Boolean,
    ) {
        val header = if (hasHeader) 1 else 0
        val index = ReadingBlocks.indexAt(blocks, position.sourceOffset, bodyStart)
        if (position.atTop || index == null) {
            read.scrollToItem(0)
            return
        }
        val itemIndex = index + header
        val block = blocks[index].shift(bodyStart)
        val fraction = position.fractionIn(block)
        val beforePadding = read.layoutInfo.beforeContentPadding
        read.scrollToItem(itemIndex, beforePadding)
        if (fraction > 0f) {
            val item = read.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
            if (item != null && item.size > 0) {
                val offset = (fraction * item.size).roundToInt().coerceAtMost(item.size - 1)
                read.scrollToItem(itemIndex, beforePadding + offset)
            }
        }
    }

    suspend fun restoreEdit(anchor: ReadingAnchor, text: String) {
        val (layout, textOffset) = awaitEditLayout(text)
        val position = if (anchor.atTop) 0 else (textOffset + textTopFor(anchor, layout)).roundToInt()
        edit.scrollTo(position)
    }

    suspend fun restoreEdit(scrollOffset: Int, text: String) {
        awaitEditLayout(text)
        edit.scrollTo(scrollOffset)
    }

    private suspend fun awaitEditLayout(text: String): Pair<TextLayoutResult, Int> =
        snapshotFlow {
            val layout = editLayout
            val textOffset = editTextOffset
            if (edit.viewportSize > 0 && layout?.layoutInput?.text?.text == text && textOffset != null) {
                layout to textOffset
            } else {
                null
            }
        }.filterNotNull().first()

    private fun textTopFor(anchor: ReadingAnchor, layout: TextLayoutResult): Float {
        val length = layout.layoutInput.text.length
        val block = anchor.block
        val offset = anchor.sourceOffset.coerceIn(0, length)
        if (block == null) return layout.getLineTop(layout.getLineForOffset(offset))
        val range = layout.verticalRange(block)
        return range.top + anchor.fractionInBlock * range.height
    }
}

internal data class EditScrollPosition(
    val layout: TextLayoutResult,
    val visibleTop: Float,
    val sourceOffset: Int,
    val atTop: Boolean,
) {
    fun fractionIn(block: ReadingBlock): Float {
        val range = layout.verticalRange(block)
        return blockFraction(visibleTop, range.top, range.height)
    }
}

private data class TextBlockRange(val top: Float, val height: Float)

private fun TextLayoutResult.verticalRange(block: ReadingBlock): TextBlockRange {
    val length = layoutInput.text.length
    val start = block.start.coerceIn(0, length)
    val last = (block.end - 1).coerceIn(start, length)
    val top = getLineTop(getLineForOffset(start))
    val bottom = getLineBottom(getLineForOffset(last))
    return TextBlockRange(top, bottom - top)
}

internal fun blockFraction(visibleTop: Float, blockTop: Float, blockHeight: Float): Float =
    if (blockHeight > 0f) ((visibleTop - blockTop) / blockHeight).coerceIn(0f, 1f) else 0f
