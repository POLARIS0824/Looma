package com.example.looma.data.assistant.tools

import com.example.looma.data.note.NoteRepository
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import org.json.JSONObject

internal class ReadNoteTool(
    private val gateway: VaultToolGateway,
) : AgentTool {
    constructor(vaults: VaultRepository, notes: NoteRepository) :
        this(DefaultVaultToolGateway(vaults, notes))

    override val spec = ToolSpec(
        name = TOOL_NAME,
        description = "Read the full contents of a specific note in the vault by its relative path.",
        parametersSchemaJson = """{"type":"object","properties":{"path":{"type":"string","description":"Relative path of the note, e.g. 'folder/note.md'."},"max_chars":{"type":"integer","description":"Maximum characters of note body to return (200-16000, default 8000)."}},"required":["path"]}""",
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val path = args.optString("path").trim()
        validateVisibleNotePath(path)?.let { return ToolResult(it, isError = true) }
        val maxChars = args.optInt("max_chars", DEFAULT_MAX_CHARS).coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)

        return executeVaultTool(gateway, "Failed to read note '$path'.") { vault ->
            val note = gateway.readNote(NoteId(vault.id, NotePath(path)))
            val body = note.content
            val truncated = body.length > maxChars
            val shown = if (truncated) body.take(maxChars) else body
            val tagsText = if (note.tags.isEmpty()) "none" else note.tags.joinToString(", ")

            val output = buildString {
                appendLine("Path: ${note.id.path.relativePath}")
                appendLine("Title: ${note.title}")
                appendLine("Tags: $tagsText")
                appendLine()
                append(shown)
                if (truncated) {
                    appendLine()
                    appendLine()
                    append("[Truncated: showing $maxChars of ${body.length} characters]")
                }
            }.trimEnd()
            ToolResult(output)
        }
    }

    companion object {
        const val TOOL_NAME = "read_note"
        private const val DEFAULT_MAX_CHARS = 8000
        private const val MIN_MAX_CHARS = 200
        private const val MAX_MAX_CHARS = 16000
    }
}
