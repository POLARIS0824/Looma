package com.example.looma.domain.markdown

data class ReadingBlock(val start: Int, val end: Int, val hasContent: Boolean = true) {
    fun shift(blockStart: Int): ReadingBlock = copy(start = start + blockStart, end = end + blockStart)
}

object ReadingBlocks {

    fun indexAt(blocks: List<ReadingBlock>, sourceOffset: Int, bodyStart: Int): Int? {
        val offset = sourceOffset - bodyStart
        val next = blocks.indexOfFirst { it.hasContent && offset < it.end }
        if (next >= 0) return next
        return blocks.indexOfLast { it.hasContent }.takeIf { it >= 0 }
    }
}
