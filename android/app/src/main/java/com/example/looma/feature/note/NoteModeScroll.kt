package com.example.looma.feature.note

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import com.example.looma.domain.markdown.ReadingBlock
import com.example.looma.domain.markdown.ReadingBlocks
import kotlin.math.roundToInt

@Stable
internal class NoteModeScroll(
    private val read: LazyListState,
    private val editor: NoteEditorState,
) {
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

    fun captureEdit(): EditScrollPosition? = editor.captureScrollPosition()

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
        val block = blocks[index].shift(bodyStart)
        scrollReadBlock(index + header, position.fractionIn(block))
    }

    suspend fun openPassage(sourceOffset: Int, blocks: List<ReadingBlock>, bodyStart: Int, hasHeader: Boolean) {
        val index = ReadingBlocks.indexAt(blocks, sourceOffset, bodyStart) ?: return
        val block = blocks[index].shift(bodyStart)
        val fraction = blockFraction(sourceOffset.toFloat(), block.start.toFloat(), (block.end - block.start).toFloat())
        scrollReadBlock(index + if (hasHeader) 1 else 0, fraction)
    }

    private suspend fun scrollReadBlock(itemIndex: Int, fraction: Float) {
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

    suspend fun restoreEdit(anchor: ReadingAnchor) {
        editor.awaitLayoutReady()
        if (anchor.atTop) {
            editor.scrollToY(0)
            return
        }
        val targetY = textTopFor(anchor)
        editor.scrollToY(targetY.roundToInt())
    }

    suspend fun restoreEdit(scrollOffset: Int) {
        editor.awaitLayoutReady()
        editor.scrollToY(scrollOffset)
    }

    private fun textTopFor(anchor: ReadingAnchor): Float {
        val block = anchor.block ?: return editor.verticalTopFor(anchor.sourceOffset)
        val top = editor.verticalTopFor(block.start)
        val bottom = editor.verticalBottomFor((block.end - 1).coerceAtLeast(block.start))
        return top + anchor.fractionInBlock * (bottom - top)
    }
}

internal data class EditScrollPosition(
    val sourceOffset: Int,
    val atTop: Boolean,
) {
    fun fractionIn(block: ReadingBlock): Float =
        blockFraction(sourceOffset.toFloat(), block.start.toFloat(), (block.end - block.start).toFloat())
}

internal fun blockFraction(visibleTop: Float, blockTop: Float, blockHeight: Float): Float =
    if (blockHeight > 0f) ((visibleTop - blockTop) / blockHeight).coerceIn(0f, 1f) else 0f
