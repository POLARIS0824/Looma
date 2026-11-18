package com.example.looma.domain.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSignatureTest {

    @Test
    fun `body tokens and single CJK characters match case insensitively`() {
        val text = "# 标题\n\n${"ordinary text ".repeat(40)}末尾包含 Kotlin Coroutines 与文件指纹"
        val signature = SearchSignature.fromBody(text)

        assertTrue(SearchSignature.mayContain(signature, "尾"))
        assertTrue(SearchSignature.mayContain(signature, "指纹"))
        assertTrue(SearchSignature.mayContain(signature, "KOTLIN"))
        assertTrue(SearchSignature.mayContain(signature, "coroutines"))
        assertFalse(SearchSignature.mayContain(signature, "nonexistent-token"))
    }

    @Test
    fun `frontmatter tokens do not enter the body signature`() {
        val signature = SearchSignature.fromBody("---\ntags: secret-frontmatter-only\n---\nvisible body")

        assertTrue(SearchSignature.mayContain(signature, "visible"))
        assertFalse(SearchSignature.mayContain(signature, "secret-frontmatter-only"))
    }

    @Test
    fun `queries spanning inline formatting and wiki aliases match the signature`() {
        val text = """
            read **more** here
            Read [[file#heading|Topic #label]]
            Visit [the site](https://needle.example)
        """.trimIndent()
        val signature = SearchSignature.fromBody(text)

        assertTrue(SearchSignature.mayContain(signature, "read more"))
        assertTrue(SearchSignature.mayContain(signature, "#label"))
        assertTrue(SearchSignature.mayContain(signature, "Read Topic"))
        assertTrue(SearchSignature.mayContain(signature, "needle.example"))
    }

    @Test
    fun `missing or empty signature safely defaults to candidate`() {
        assertTrue(SearchSignature.mayContain(null, "anything"))
        assertTrue(SearchSignature.mayContain(ByteArray(0), "anything"))
        assertFalse(SearchSignature.mayContain(null, ""))
    }
}
