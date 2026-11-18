package com.example.looma.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineTagsTest {

    @Test
    fun `alternating tags and code spans visit exclusions at most once`() {
        val paragraph = " #a `x`\n\n"
        val paragraphCount = 8_000
        val body = paragraph.repeat(paragraphCount)
        val ranges = MarkdownIndex.excludedTagRanges(body)
        var rangeVisits = 0
        val countedRanges = object : AbstractList<IntRange>() {
            override val size = ranges.size

            override fun get(index: Int): IntRange {
                rangeVisits++
                return ranges[index]
            }
        }

        val tags = findInlineTags(body, countedRanges)

        assertEquals(paragraphCount, ranges.size)
        val expectedTags = List(paragraphCount) { index ->
            val start = index * paragraph.length + 1
            InlineTagSpan(start..(start + 1), "a")
        }
        assertEquals(expectedTags, tags)
        assertTrue("Visited $rangeVisits exclusion ranges", rangeVisits <= ranges.size)
    }

    @Test
    fun `nested and overlapping exclusions suppress partial tag matches`() {
        val body = "#outside #outer #inner #overlap #after"
        val sortedExclusions = listOf(12..20, 17..18, 20..25)

        assertEquals(
            listOf(InlineTagSpan(0..7, "outside"), InlineTagSpan(32..37, "after")),
            findInlineTags(body, sortedExclusions),
        )
    }
}
