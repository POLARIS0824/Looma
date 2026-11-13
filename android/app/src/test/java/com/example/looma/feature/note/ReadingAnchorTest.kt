package com.example.looma.feature.note

import com.example.looma.domain.markdown.ReadingBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingAnchorTest {

    private val blocks = listOf(ReadingBlock(0, 10), ReadingBlock(20, 30))

    @Test
    fun `a read anchor carries the absolute block and clamps the fraction`() {
        val anchor = ReadingAnchor.read(blocks, bodyStart = 5, index = 1, fraction = 1.4f)

        assertEquals(25, anchor.sourceOffset)
        assertEquals(ReadingBlock(25, 35), anchor.block)
        assertEquals(1f, anchor.fractionInBlock, 0f)
    }

    @Test
    fun `the document top anchor has no block`() {
        val anchor = ReadingAnchor.atDocumentTop(bodyStart = 12)

        assertEquals(12, anchor.sourceOffset)
        assertNull(anchor.block)
        assertTrue(anchor.atTop)
    }

    @Test
    fun `an edit anchor keeps the source offset when no blocks are known`() {
        val anchor = ReadingAnchor.edit(sourceOffset = 42, block = null, fraction = -1f, atTop = false)

        assertEquals(42, anchor.sourceOffset)
        assertNull(anchor.block)
        assertEquals(0f, anchor.fractionInBlock, 0f)
    }

    @Test
    fun `the second line of a two-line block starts at half its height`() {
        assertEquals(0.5f, blockFraction(visibleTop = 60f, blockTop = 40f, blockHeight = 40f), 0f)
    }

    @Test
    fun `the preceding blank line aligns to the start of the selected block`() {
        assertEquals(0f, blockFraction(visibleTop = 20f, blockTop = 40f, blockHeight = 40f), 0f)
    }
}
