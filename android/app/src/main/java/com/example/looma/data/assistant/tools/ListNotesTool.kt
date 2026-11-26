package com.example.looma.data.assistant.tools

import com.example.looma.data.note.NoteRepository
import com.example.looma.data.vault.VaultRepository
import org.json.JSONObject

internal class ListNotesTool(
    private val gateway: VaultToolGateway,
) : AgentTool {
    constructor(vaults: VaultRepository, notes: NoteRepository) :
        this(DefaultVaultToolGateway(vaults, notes))

    override val spec = ToolSpec(
        name = TOOL_NAME,
        description = "List notes in the current vault, optionally filtered by folder prefix or tag.",
        parametersSchemaJson = """{"type":"object","properties":{"folder":{"type":"string","description":"Optional folder path prefix filter."},"tag":{"type":"string","description":"Optional tag filter (case-insensitive)."},"limit":{"type":"integer","description":"Maximum number of notes to return (1-50, default 20)."}}}""",
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val folder = args.optString("folder").trim().replace('\\', '/').trim('/')
        val tag = args.optString("tag").trim().removePrefix("#")
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        return executeVaultTool(gateway, "Failed to list notes.") { vault ->
            val scan = gateway.evidenceNotes(vault)
            val filtered = scan.items.filter { entry ->
                val matchesFolder = folder.isEmpty() ||
                    entry.summary.id.path.relativePath.startsWith("$folder/", ignoreCase = true)
                val matchesTag = tag.isEmpty() ||
                    entry.summary.tags.any { it.equals(tag, ignoreCase = true) }
                matchesFolder && matchesTag
            }

            if (filtered.isEmpty()) {
                return@executeVaultTool ToolResult("No matching notes found.")
            }

            val shown = filtered.take(limit)
            val output = buildString {
                appendLine("Showing ${shown.size} of ${filtered.size} note(s):")
                shown.forEachIndexed { index, entry ->
                    val summary = entry.summary
                    append("${index + 1}. ${summary.id.path.relativePath} — ${summary.title}")
                    if (summary.tags.isNotEmpty()) {
                        append(" [${summary.tags.joinToString(", ")}]")
                    }
                    appendLine()
                }
            }.trimEnd()
            ToolResult(output)
        }
    }

    companion object {
        const val TOOL_NAME = "list_notes"
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 50
    }
}
