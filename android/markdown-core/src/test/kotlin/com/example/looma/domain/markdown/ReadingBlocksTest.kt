package com.example.looma.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingBlocksTest {

    private val blocks = listOf(
        ReadingBlock(0, 10),
        ReadingBlock(20, 30),
        ReadingBlock(40, 50),
    )

    @Test
    fun `an offset inside a block resolves to that block`() {
        assertEquals(1, ReadingBlocks.indexAt(blocks, sourceOffset = 25, bodyStart = 0))
    }

    @Test
    fun `an offset in a gap resolves to the next block`() {
        assertEquals(1, ReadingBlocks.indexAt(blocks, sourceOffset = 15, bodyStart = 0))
    }

    @Test
    fun `a blank line resolves to the next content item using the original list index`() {
        val withSpacer = listOf(
            ReadingBlock(0, 10),
            ReadingBlock(10, 11, hasContent = false),
            ReadingBlock(11, 12, hasContent = false),
            ReadingBlock(12, 20),
            ReadingBlock(20, 21, hasContent = false),
        )
        assertEquals(0, ReadingBlocks.indexAt(withSpacer, sourceOffset = 14, bodyStart = 5))
        assertEquals(3, ReadingBlocks.indexAt(withSpacer, sourceOffset = 15, bodyStart = 5))
        assertEquals(3, ReadingBlocks.indexAt(withSpacer, sourceOffset = 16, bodyStart = 5))
        assertEquals(3, ReadingBlocks.indexAt(withSpacer, sourceOffset = 17, bodyStart = 5))
        assertEquals(3, ReadingBlocks.indexAt(withSpacer, sourceOffset = 25, bodyStart = 5))
    }

    @Test
    fun `an offset before the body resolves to the first block`() {
        assertEquals(0, ReadingBlocks.indexAt(blocks, sourceOffset = 0, bodyStart = 8))
        assertEquals(0, ReadingBlocks.indexAt(blocks, sourceOffset = 3, bodyStart = 8))
    }

    @Test
    fun `an offset at the end of the body resolves to the last block`() {
        assertEquals(2, ReadingBlocks.indexAt(blocks, sourceOffset = 60, bodyStart = 10))
    }

    @Test
    fun `an empty block list has no index`() {
        assertNull(ReadingBlocks.indexAt(emptyList(), sourceOffset = 0, bodyStart = 0))
        assertNull(ReadingBlocks.indexAt(listOf(ReadingBlock(0, 1, hasContent = false)), 0, 0))
    }

    @Test
    fun `shifting a block adds the body start`() {
        assertEquals(ReadingBlock(12, 22), ReadingBlock(0, 10).shift(12))
    }
}
