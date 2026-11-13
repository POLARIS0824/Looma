package com.example.looma.feature.note

import com.example.looma.domain.markdown.ReadingBlock

internal data class ReadingAnchor(
    val sourceOffset: Int,
    val block: ReadingBlock?,
    val fractionInBlock: Float,
    val atTop: Boolean,
) {
    companion object {

        fun atDocumentTop(bodyStart: Int): ReadingAnchor =
            ReadingAnchor(bodyStart, null, 0f, atTop = true)

        fun read(blocks: List<ReadingBlock>, bodyStart: Int, index: Int, fraction: Float): ReadingAnchor {
            val block = blocks[index.coerceIn(0, blocks.lastIndex)].shift(bodyStart)
            return ReadingAnchor(block.start, block, fraction.coerceIn(0f, 1f), atTop = false)
        }

        fun edit(
            sourceOffset: Int,
            block: ReadingBlock?,
            fraction: Float,
            atTop: Boolean,
        ): ReadingAnchor =
            ReadingAnchor(sourceOffset, block, fraction.coerceIn(0f, 1f), atTop)
    }
}
