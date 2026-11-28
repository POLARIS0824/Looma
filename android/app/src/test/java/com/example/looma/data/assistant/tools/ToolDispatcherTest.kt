package com.example.looma.data.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolDispatcherTest {

    private val dispatcher = ToolDispatcher()

    @Test
    fun `extractToolCall parses bare tool JSON`() {
        val result = dispatcher.extractToolCall("""{"tool":"search_vault","arguments":{"query":"notes"}}""")
        assertNotNull(result)
        assertEquals("search_vault", result!!.name)
        assertEquals("""{"query":"notes"}""", result.argumentsJson)
    }

    @Test
    fun `extractToolCall parses single top-level fenced tool JSON`() {
        val raw = """
            ```json
            {"tool":"read_note","arguments":{"path":"inbox.md"}}
            ```
        """.trimIndent()
        val result = dispatcher.extractToolCall(raw)
        assertNotNull(result)
        assertEquals("read_note", result!!.name)
        assertEquals("""{"path":"inbox.md"}""", result.argumentsJson)
    }

    @Test
    fun `extractToolCall accepts name key when paired with arguments args or parameters`() {
        val withArgs = dispatcher.extractToolCall("""{"name":"search_vault","args":{"q":"a"}}""")
        assertEquals(ParsedToolCall("search_vault", """{"q":"a"}"""), withArgs)

        val withParams = dispatcher.extractToolCall("""{"name":"search_vault","parameters":{"q":"b"}}""")
        assertEquals(ParsedToolCall("search_vault", """{"q":"b"}"""), withParams)
    }

    @Test
    fun `extractToolCall returns null for ordinary JSON containing name without arguments`() {
        val raw = """{"name": "demo-app", "version": "1.0"}"""
        assertNull(dispatcher.extractToolCall(raw))
    }

    @Test
    fun `extractToolCall returns null for prose containing embedded fenced JSON example`() {
        val raw = """
            Here is an example package manifest:
            ```json
            {"name": "demo-app", "version": "1.0"}
            ```
        """.trimIndent()
        assertNull(dispatcher.extractToolCall(raw))
    }

    @Test
    fun `extractToolCall returns null for prose containing embedded tool-shaped JSON`() {
        val raw = """
            To invoke the tool, send:
            ```json
            {"tool":"search_vault","arguments":{"query":"test"}}
            ```
        """.trimIndent()
        assertNull(dispatcher.extractToolCall(raw))
    }
}
