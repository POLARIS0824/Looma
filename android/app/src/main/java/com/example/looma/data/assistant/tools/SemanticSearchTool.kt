package com.example.looma.data.assistant.tools

import com.example.looma.data.note.NoteRepository
import com.example.looma.data.search.SemanticIndexMissingException
import com.example.looma.data.search.SemanticModelMissingException
import com.example.looma.data.search.SemanticSearchRepository
import com.example.looma.data.search.SemanticVaultChangedException
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.search.SearchableNote
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal class SemanticSearchTool(
    private val semanticSearch: SemanticSearchRepository,
    private val vaults: VaultRepository,
    private val notes: NoteRepository,
) : AgentTool {
    override val spec = ToolSpec(
        name = TOOL_NAME,
        description = "Search the user's vault notes for passages semantically relevant to the query. Use this when the user asks about their notes, personal knowledge, or past writing.",
        parametersSchemaJson = """{"type":"object","properties":{"query":{"type":"string","description":"Search query describing what to look for in the user's notes."},"limit":{"type":"integer","description":"Maximum number of passages to return (1-8, default 5)."}},"required":["query"]}""",
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val query = args.optString("query").trim()
        if (query.length !in 1..500) {
            return ToolResult("Query must be between 1 and 500 characters.", isError = true)
        }
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val vault = try {
            vaults.requireCurrentVault()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return ToolResult("No vault is currently open.", isError = true)
        }

        val scan = notes.evidenceNotes(vault)
        val candidates = scan.items.map {
            SearchableNote(it.summary.id, it.summary.title, it.summary.tags)
        }
        if (candidates.isEmpty()) {
            return ToolResult("The current vault has no notes.")
        }

        val hits = try {
            semanticSearch.search(query, candidates).take(limit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SemanticModelMissingException) {
            return ToolResult("Semantic search embedding model is not installed.", isError = true)
        } catch (_: SemanticIndexMissingException) {
            return ToolResult("Semantic search index has not been built for this vault yet.", isError = true)
        } catch (_: SemanticVaultChangedException) {
            return ToolResult("Vault changed during search; please try again.", isError = true)
        } catch (e: Exception) {
            return ToolResult(e.message ?: "Semantic search failed.", isError = true)
        }

        if (hits.isEmpty()) {
            return ToolResult("No relevant passages found in the vault for '$query'.")
        }

        val output = buildString {
            appendLine("Found ${hits.size} relevant passage(s):")
            hits.forEachIndexed { index, hit ->
                append("[").append(index + 1).append("] ")
                append(hit.id.path.relativePath)
                if (hit.section.isNotBlank()) {
                    append(" (").append(hit.section).append(")")
                }
                append(String.format(Locale.US, " [score=%.2f]", hit.similarity))
                appendLine(":")
                appendLine(hit.excerpt.trim().take(MAX_EXCERPT_CHARS))
            }
        }.trim()
        return ToolResult(output)
    }

    companion object {
        const val TOOL_NAME = "search_vault"
        private const val DEFAULT_LIMIT = 5
        private const val MAX_LIMIT = 8
        private const val MAX_EXCERPT_CHARS = 800
    }
}
