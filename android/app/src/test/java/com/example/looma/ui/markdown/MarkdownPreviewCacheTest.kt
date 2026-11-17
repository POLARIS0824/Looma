package com.example.looma.ui.markdown

import com.mikepenz.markdown.model.State
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MarkdownPreviewCacheTest {
    @Test
    fun `overlapping previews reuse the same prepared state and resolve reference links`() = runBlocking {
        val cache = MarkdownPreviewCache(capacity = 2)
        val markdown = "[Example][link]\n\n[link]: https://example.com"
        val first = async { cache.load(markdown) }
        val second = async { cache.load(markdown) }
        val parsed = first.await() as State.Success

        assertSame(parsed, second.await())
        assertSame(parsed, cache.get(markdown))
        assertEquals("https://example.com", parsed.referenceLinkHandler.find("[link]"))
    }

    @Test
    fun `changed content gets its own state and unused entries are evicted`() = runBlocking {
        val cache = MarkdownPreviewCache(capacity = 2)
        val original = cache.load("Original")
        cache.load("Other preview")
        assertSame(original, cache.get("Original"))

        val edited = cache.load("Edited") as State.Success

        assertEquals("Edited", edited.content)
        assertNotSame(original, edited)
        assertSame(original, cache.get("Original"))
        assertNull(cache.get("Other preview"))
    }
}
