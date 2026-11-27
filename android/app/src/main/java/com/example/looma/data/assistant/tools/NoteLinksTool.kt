package com.example.looma.data.assistant.tools

import com.example.looma.data.note.NoteRepository
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import org.json.JSONObject

internal class NoteLinksTool(
    private val gateway: VaultToolGateway,
) : AgentTool {
    constructor(vaults: VaultRepository, notes: NoteRepository) :
        this(DefaultVaultToolGateway(vaults, notes))

    override val spec = ToolSpec(
        name = TOOL_NAME,
        description = "Get outgoing wiki links and incoming backlinks for a specific note.",
        parametersSchemaJson = """{"type":"object","properties":{"path":{"type":"string","description":"Relative path of the note, e.g. 'folder/note.md'."}},"required":["path"]}""",
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val path = args.optString("path").trim()
        validateVisibleNotePath(path)?.let { return ToolResult(it, isError = true) }

        return executeVaultTool(gateway, "Failed to get links for '$path'.") { vault ->
            val noteId = NoteId(vault.id, NotePath(path))
            val note = gateway.readNote(noteId)
            val wiki = gateway.wikiContext(noteId)
            val outgoing = MarkdownIndex.index(note.content, note.title).links
                .map { link ->
                    val resolved = wiki.resolve(link.target)
                    if (resolved != null) resolved.path.relativePath else "${link.target} (unresolved)"
                }
                .distinct()

            val output = buildString {
                appendLine("Links for ${note.id.path.relativePath}:")
                appendLine("Outgoing links (${outgoing.size}):")
                if (outgoing.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    outgoing.forEach { target -> appendLine("  - $target") }
                }
                appendLine("Incoming backlinks (${wiki.backlinks.size}):")
                if (wiki.backlinks.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    wiki.backlinks.forEach { backlink ->
                        appendLine("  - ${backlink.id.path.relativePath} (${backlink.title})")
                    }
                }
            }.trimEnd()
            ToolResult(output)
        }
    }

    companion object {
        const val TOOL_NAME = "get_note_links"
    }
}
