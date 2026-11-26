package com.example.looma.data.assistant.tools

import com.example.looma.data.note.NoteRepository
import com.example.looma.data.vault.VaultRepository
import org.json.JSONObject

internal class CreateNoteTool(
    private val gateway: VaultToolGateway,
) : AgentTool {
    constructor(vaults: VaultRepository, notes: NoteRepository) :
        this(DefaultVaultToolGateway(vaults, notes))

    override val spec = ToolSpec(
        name = TOOL_NAME,
        description = "Create a new Markdown note in the current vault with the given title and content.",
        parametersSchemaJson = """{"type":"object","properties":{"title":{"type":"string","description":"Note title (1-72 characters, non-blank)."},"content":{"type":"string","description":"Markdown body content for the new note (non-blank)."}},"required":["title","content"]}""",
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val title = args.optString("title").trim()
        if (title.isEmpty() || title.length > MAX_TITLE_LENGTH) {
            return ToolResult("Title must be non-blank and at most $MAX_TITLE_LENGTH characters.", isError = true)
        }
        val content = args.optString("content")
        if (content.isBlank()) {
            return ToolResult("Content must not be blank.", isError = true)
        }

        return executeVaultTool(gateway, "Failed to create note '$title'.") { vault ->
            val created = gateway.createWithContent(vault, title, content)
            ToolResult("Created note: ${created.id.path.relativePath}")
        }
    }

    companion object {
        const val TOOL_NAME = "create_note"
        private const val MAX_TITLE_LENGTH = 72
    }
}
