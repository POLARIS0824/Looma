package com.example.looma.data.assistant.tools

import com.example.looma.data.note.NoteRepository
import com.example.looma.data.vault.VaultRepository
import org.json.JSONObject

internal class KeywordSearchTool(
    private val gateway: VaultToolGateway,
) : AgentTool {
    constructor(vaults: VaultRepository, notes: NoteRepository) :
        this(DefaultVaultToolGateway(vaults, notes))

    override val spec = ToolSpec(
        name = TOOL_NAME,
        description = "Search notes in the current vault by exact keyword or phrase across titles, tags, and content.",
        parametersSchemaJson = """{"type":"object","properties":{"query":{"type":"string","description":"Keyword or phrase to search for (1-200 characters)."},"limit":{"type":"integer","description":"Maximum number of matching notes to return (1-15, default 8)."}},"required":["query"]}""",
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val query = args.optString("query").trim()
        if (query.length !in 1..MAX_QUERY_LENGTH) {
            return ToolResult("Query must be between 1 and $MAX_QUERY_LENGTH characters.", isError = true)
        }
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        return executeVaultTool(gateway, "Keyword search failed.") { vault ->
            val scan = gateway.evidenceNotes(vault)
            if (scan.items.isEmpty()) {
                return@executeVaultTool ToolResult("The current vault has no notes.")
            }
            val titleById = scan.items.associate { it.summary.id to it.summary.title }
            val matches = gateway.search(query, limit)
            if (matches.isEmpty()) {
                return@executeVaultTool ToolResult("No notes matched '$query'.")
            }

            val output = buildString {
                appendLine("Found ${matches.size} matching note(s):")
                matches.forEachIndexed { index, match ->
                    val title = titleById[match.id].orEmpty()
                    appendLine("[${index + 1}] ${match.id.path.relativePath} ($title)")
                    match.hit.titleMatch?.let {
                        appendLine("  Title match: ${it.text.trim()}")
                    }
                    if (match.hit.tagMatches.isNotEmpty()) {
                        appendLine("  Tag match: ${match.hit.tagMatches.joinToString(", ") { it.text.trim() }}")
                    }
                    match.hit.matches.take(MAX_SNIPPETS_PER_NOTE).forEach { snippet ->
                        appendLine("  - ${snippet.text.trim().take(MAX_SNIPPET_CHARS)}")
                    }
                }
            }.trimEnd()
            ToolResult(output)
        }
    }

    companion object {
        const val TOOL_NAME = "keyword_search"
        private const val MAX_QUERY_LENGTH = 200
        private const val DEFAULT_LIMIT = 8
        private const val MAX_LIMIT = 15
        private const val MAX_SNIPPETS_PER_NOTE = 3
        private const val MAX_SNIPPET_CHARS = 240
    }
}
