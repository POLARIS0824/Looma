package com.example.looma.data.assistant.tools

import com.example.looma.data.note.NoteListEntry
import com.example.looma.data.note.NoteListResult
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.search.NoteSearchHit
import com.example.looma.domain.search.NoteSearchMatch
import com.example.looma.domain.search.SearchMatch
import com.example.looma.domain.wiki.Backlink
import com.example.looma.domain.wiki.WikiContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VaultAgentToolsTest {

    private val gateway = FakeVaultToolGateway()

    private val readNoteTool = ReadNoteTool(gateway)
    private val listNotesTool = ListNotesTool(gateway)
    private val keywordSearchTool = KeywordSearchTool(gateway)
    private val noteLinksTool = NoteLinksTool(gateway)
    private val createNoteTool = CreateNoteTool(gateway)

    private val provider = DefaultToolsProvider(
        listOf(
            readNoteTool,
            listNotesTool,
            keywordSearchTool,
            noteLinksTool,
            createNoteTool,
        ),
    )

    private val dispatcher = ToolDispatcher()

    @Test
    fun `tool specs declare expected names required fields and parameter schemas`() {
        assertEquals("read_note", readNoteTool.spec.name)
        val readSchema = JSONObject(readNoteTool.spec.parametersSchemaJson)
        assertEquals("object", readSchema.getString("type"))
        assertEquals(listOf("path"), readSchema.getJSONArray("required").toStringList())
        assertTrue(readSchema.getJSONObject("properties").has("path"))
        assertTrue(readSchema.getJSONObject("properties").has("max_chars"))

        assertEquals("list_notes", listNotesTool.spec.name)
        val listSchema = JSONObject(listNotesTool.spec.parametersSchemaJson)
        assertEquals("object", listSchema.getString("type"))
        assertFalse(listSchema.has("required"))
        assertTrue(listSchema.getJSONObject("properties").has("folder"))
        assertTrue(listSchema.getJSONObject("properties").has("tag"))
        assertTrue(listSchema.getJSONObject("properties").has("limit"))

        assertEquals("keyword_search", keywordSearchTool.spec.name)
        val searchSchema = JSONObject(keywordSearchTool.spec.parametersSchemaJson)
        assertEquals("object", searchSchema.getString("type"))
        assertEquals(listOf("query"), searchSchema.getJSONArray("required").toStringList())
        assertTrue(searchSchema.getJSONObject("properties").has("query"))
        assertTrue(searchSchema.getJSONObject("properties").has("limit"))

        assertEquals("get_note_links", noteLinksTool.spec.name)
        val linksSchema = JSONObject(noteLinksTool.spec.parametersSchemaJson)
        assertEquals("object", linksSchema.getString("type"))
        assertEquals(listOf("path"), linksSchema.getJSONArray("required").toStringList())
        assertTrue(linksSchema.getJSONObject("properties").has("path"))

        assertEquals("create_note", createNoteTool.spec.name)
        val createSchema = JSONObject(createNoteTool.spec.parametersSchemaJson)
        assertEquals("object", createSchema.getString("type"))
        assertEquals(listOf("title", "content"), createSchema.getJSONArray("required").toStringList())
        assertTrue(createSchema.getJSONObject("properties").has("title"))
        assertTrue(createSchema.getJSONObject("properties").has("content"))
    }

    @Test
    fun `read note rejects blank path`() = runBlocking {
        val missing = readNoteTool.execute(JSONObject())
        assertTrue(missing.isError)
        assertEquals("Path must not be blank.", missing.output)

        val blank = readNoteTool.execute(JSONObject().put("path", "   "))
        assertTrue(blank.isError)
        assertEquals("Path must not be blank.", blank.output)
    }

    @Test
    fun `read note returns title tags and body`() = runBlocking {
        gateway.addNote(
            path = "projects/alpha.md",
            title = "Alpha",
            content = "Project Alpha body.",
            tags = setOf("work", "active"),
        )

        val result = readNoteTool.execute(JSONObject().put("path", "projects/alpha.md"))

        assertFalse(result.isError)
        assertEquals(
            "Path: projects/alpha.md\nTitle: Alpha\nTags: work, active\n\nProject Alpha body.",
            result.output,
        )
    }

    @Test
    fun `read note truncates at max_chars with truncation notice`() = runBlocking {
        val body = "x".repeat(250)
        gateway.addNote(
            path = "long.md",
            title = "Long Note",
            content = body,
        )

        val result = readNoteTool.execute(
            JSONObject()
                .put("path", "long.md")
                .put("max_chars", 200),
        )

        assertFalse(result.isError)
        assertEquals(
            "Path: long.md\nTitle: Long Note\nTags: none\n\n${"x".repeat(200)}\n\n[Truncated: showing 200 of 250 characters]",
            result.output,
        )
    }

    @Test
    fun `read note returns error when note is missing`() = runBlocking {
        val result = readNoteTool.execute(JSONObject().put("path", "missing.md"))

        assertTrue(result.isError)
        assertEquals("Note not found: missing.md", result.output)
    }

    @Test
    fun `list notes handles empty vault`() = runBlocking {
        val result = listNotesTool.execute(JSONObject())

        assertFalse(result.isError)
        assertEquals("No matching notes found.", result.output)
    }

    @Test
    fun `list notes filters by folder prefix case-insensitive tag and respects limit`() = runBlocking {
        gateway.addNote("work/a.md", "Alpha", "a", setOf("Project", "Urgent"))
        gateway.addNote("work/sub/b.md", "Beta", "b", setOf("project"))
        gateway.addNote("work/c.md", "Gamma", "c", setOf("other"))
        gateway.addNote("personal/d.md", "Delta", "d", setOf("project"))

        val all = listNotesTool.execute(JSONObject())
        assertFalse(all.isError)
        assertTrue(all.output.startsWith("Showing 4 of 4 note(s):"))

        val filtered = listNotesTool.execute(
            JSONObject()
                .put("folder", "/work/")
                .put("tag", "#PROJECT")
                .put("limit", 1),
        )
        assertFalse(filtered.isError)
        assertEquals(
            "Showing 1 of 2 note(s):\n1. work/a.md — Alpha [Project, Urgent]",
            filtered.output,
        )
    }

    @Test
    fun `keyword search rejects empty or over 200 char query`() = runBlocking {
        val empty = keywordSearchTool.execute(JSONObject().put("query", "   "))
        assertTrue(empty.isError)
        assertEquals("Query must be between 1 and 200 characters.", empty.output)

        val tooLong = keywordSearchTool.execute(JSONObject().put("query", "a".repeat(201)))
        assertTrue(tooLong.isError)
        assertEquals("Query must be between 1 and 200 characters.", tooLong.output)
    }

    @Test
    fun `keyword search returns matching notes snippets and respects limit`() = runBlocking {
        val first = gateway.addNote("a.md", "Alpha Guide", "alpha snippet", setOf("kotlin"))
        val second = gateway.addNote("b.md", "Beta Guide", "alpha second", setOf("kotlin"))
        gateway.searchResults = listOf(
            NoteSearchMatch(
                id = first.id,
                hit = NoteSearchHit(
                    titleMatch = SearchMatch("Alpha Guide", listOf(0..4)),
                    tagMatches = listOf(SearchMatch("#kotlin", listOf(1..6))),
                    matches = listOf(SearchMatch("alpha snippet", listOf(0..4))),
                ),
            ),
            NoteSearchMatch(
                id = second.id,
                hit = NoteSearchHit(
                    titleMatch = null,
                    tagMatches = emptyList(),
                    matches = listOf(SearchMatch("alpha second", listOf(0..4))),
                ),
            ),
        )

        val result = keywordSearchTool.execute(
            JSONObject()
                .put("query", "alpha")
                .put("limit", 1),
        )

        assertFalse(result.isError)
        assertEquals(1, gateway.lastSearchLimit)
        assertEquals(
            "Found 1 matching note(s):\n[1] a.md (Alpha Guide)\n  Title match: Alpha Guide\n  Tag match: #kotlin\n  - alpha snippet",
            result.output,
        )
    }

    @Test
    fun `keyword search handles no matches`() = runBlocking {
        gateway.addNote("a.md", "Alpha", "body")
        gateway.searchResults = emptyList()

        val result = keywordSearchTool.execute(JSONObject().put("query", "missing"))

        assertFalse(result.isError)
        assertEquals("No notes matched 'missing'.", result.output)
    }

    @Test
    fun `note links rejects blank path`() = runBlocking {
        val missing = noteLinksTool.execute(JSONObject())
        assertTrue(missing.isError)
        assertEquals("Path must not be blank.", missing.output)

        val blank = noteLinksTool.execute(JSONObject().put("path", "  "))
        assertTrue(blank.isError)
        assertEquals("Path must not be blank.", blank.output)
    }

    @Test
    fun `note links returns outgoing links and incoming backlinks`() = runBlocking {
        val target = gateway.addNote("target.md", "Target", "body")
        val source = gateway.addNote("source.md", "Source", "See [[target]] and [[ghost]].")
        val referrer = gateway.addNote("referrer.md", "Referrer", "Links to [[source]].")
        gateway.wikiContexts[source.id.path.relativePath] = WikiContext(
            targets = mapOf("target" to target.id),
            backlinks = listOf(Backlink(referrer.id, referrer.title)),
        )

        val result = noteLinksTool.execute(JSONObject().put("path", "source.md"))

        assertFalse(result.isError)
        assertEquals(
            "Links for source.md:\nOutgoing links (2):\n  - target.md\n  - ghost (unresolved)\nIncoming backlinks (1):\n  - referrer.md (Referrer)",
            result.output,
        )
    }

    @Test
    fun `note links handles note with no links`() = runBlocking {
        gateway.addNote("isolated.md", "Isolated", "Just plain text.")

        val result = noteLinksTool.execute(JSONObject().put("path", "isolated.md"))

        assertFalse(result.isError)
        assertEquals(
            "Links for isolated.md:\nOutgoing links (0):\n  (none)\nIncoming backlinks (0):\n  (none)",
            result.output,
        )
    }

    @Test
    fun `create note rejects blank or over 72 char title and blank content`() = runBlocking {
        val blankTitle = createNoteTool.execute(JSONObject().put("title", "   ").put("content", "body"))
        assertTrue(blankTitle.isError)
        assertEquals("Title must be non-blank and at most 72 characters.", blankTitle.output)

        val longTitle = createNoteTool.execute(JSONObject().put("title", "x".repeat(73)).put("content", "body"))
        assertTrue(longTitle.isError)
        assertEquals("Title must be non-blank and at most 72 characters.", longTitle.output)

        val blankContent = createNoteTool.execute(JSONObject().put("title", "Valid").put("content", "   "))
        assertTrue(blankContent.isError)
        assertEquals("Content must not be blank.", blankContent.output)
    }

    @Test
    fun `create note creates note via gateway and returns created path`() = runBlocking {
        val result = createNoteTool.execute(
            JSONObject()
                .put("title", "Meeting Notes")
                .put("content", "# Agenda\n- Item 1"),
        )

        assertFalse(result.isError)
        assertEquals("Created note: Meeting Notes.md", result.output)
        assertEquals("Meeting Notes", gateway.lastCreatedTitle)
        assertEquals("# Agenda\n- Item 1", gateway.lastCreatedContent)
    }

    @Test
    fun `create note propagates gateway failure as error result`() = runBlocking {
        gateway.createError = IllegalStateException("Disk full")

        val result = createNoteTool.execute(
            JSONObject()
                .put("title", "Draft")
                .put("content", "Body"),
        )

        assertTrue(result.isError)
        assertEquals("Disk full", result.output)
    }

    @Test
    fun `dispatcher routes validation errors and handles unknown or invalid json`() = runBlocking {
        val readResult = dispatcher.dispatch(provider, "read_note", """{"path":""}""")
        assertTrue(readResult.isError)
        assertEquals("Path must not be blank.", readResult.output)

        val createResult = dispatcher.dispatch(provider, "create-note", """{"title":"ok","content":" "}""")
        assertTrue(createResult.isError)
        assertEquals("Content must not be blank.", createResult.output)

        val unknownResult = dispatcher.dispatch(provider, "nonexistent_tool", "{}")
        assertTrue(unknownResult.isError)
        assertEquals("Unknown tool: nonexistent_tool", unknownResult.output)

        val invalidJsonResult = dispatcher.dispatch(provider, "read_note", "{not json")
        assertTrue(invalidJsonResult.isError)
        assertEquals("Invalid JSON arguments for read_note", invalidJsonResult.output)
    }

    @Test
    fun `dispatcher executes all five tools end to end`() = runBlocking {
        val existing = gateway.addNote(
            path = "notes/hello.md",
            title = "Hello",
            content = "Links [[created]]",
            tags = setOf("demo"),
        )
        val createdTarget = Note(
            id = NoteId(gateway.vault.id, NotePath("created.md")),
            title = "created",
            content = "New body",
            modifiedAt = Instant.EPOCH,
        )
        gateway.wikiContexts[existing.id.path.relativePath] = WikiContext(
            targets = mapOf("created" to createdTarget.id),
            backlinks = emptyList(),
        )
        gateway.searchResults = listOf(
            NoteSearchMatch(
                id = existing.id,
                hit = NoteSearchHit(
                    titleMatch = SearchMatch("Hello", listOf(0..4)),
                    tagMatches = emptyList(),
                    matches = emptyList(),
                ),
            ),
        )

        val listResult = dispatcher.dispatch(provider, "list_notes", """{"folder":"notes"}""")
        assertFalse(listResult.isError)
        assertTrue(listResult.output.contains("notes/hello.md"))

        val readResult = dispatcher.dispatch(provider, "read_note", """{"path":"notes/hello.md"}""")
        assertFalse(readResult.isError)
        assertTrue(readResult.output.contains("Title: Hello"))

        val searchResult = dispatcher.dispatch(provider, "keyword_search", """{"query":"Hello"}""")
        assertFalse(searchResult.isError)
        assertTrue(searchResult.output.contains("notes/hello.md"))

        val linksResult = dispatcher.dispatch(provider, "get_note_links", """{"path":"notes/hello.md"}""")
        assertFalse(linksResult.isError)
        assertTrue(linksResult.output.contains("created.md"))

        val createResult = dispatcher.dispatch(provider, "create_note", """{"title":"created","content":"New body"}""")
        assertFalse(createResult.isError)
        assertEquals("Created note: created.md", createResult.output)
    }

    private fun org.json.JSONArray.toStringList(): List<String> =
        List(length()) { index -> getString(index) }

    private class FakeVaultToolGateway : VaultToolGateway {
        val vault = Vault(
            id = VaultId("vault-1"),
            name = "Test Vault",
            rootPath = "/tmp/vault",
        )
        var currentVault: Vault? = vault
        val notesByPath = linkedMapOf<String, Note>()
        var searchResults: List<NoteSearchMatch> = emptyList()
        val wikiContexts = mutableMapOf<String, WikiContext>()
        var createError: Exception? = null
        var lastSearchLimit: Int? = null
        var lastCreatedTitle: String? = null
        var lastCreatedContent: String? = null

        fun addNote(
            path: String,
            title: String,
            content: String,
            tags: Set<String> = emptySet(),
        ): Note {
            val note = Note(
                id = NoteId(vault.id, NotePath(path)),
                title = title,
                content = content,
                modifiedAt = Instant.EPOCH,
                tags = tags,
            )
            notesByPath[path] = note
            return note
        }

        override suspend fun requireCurrentVault(): Vault =
            currentVault ?: error("No vault is currently open.")

        override suspend fun readNote(id: NoteId): Note =
            notesByPath[id.path.relativePath]
                ?: throw IllegalStateException("Note not found: ${id.path.relativePath}")

        override suspend fun evidenceNotes(vault: Vault): NoteListResult = NoteListResult(
            vault = vault,
            items = notesByPath.values.map { note ->
                NoteListEntry(
                    summary = NoteSummary(
                        id = note.id,
                        title = note.title,
                        modifiedAt = note.modifiedAt,
                        tags = note.tags,
                    ),
                    coverPath = null,
                    markdownSnippet = note.content.take(80),
                )
            },
        )

        override suspend fun search(query: String, limit: Int): List<NoteSearchMatch> {
            lastSearchLimit = limit
            return searchResults.take(limit)
        }

        override suspend fun wikiContext(id: NoteId): WikiContext =
            wikiContexts[id.path.relativePath] ?: WikiContext.Empty

        override suspend fun createWithContent(vault: Vault, title: String, content: String): Note {
            createError?.let { throw it }
            lastCreatedTitle = title
            lastCreatedContent = content
            return addNote(path = "$title.md", title = title, content = content)
        }
    }
}
