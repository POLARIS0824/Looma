package com.example.looma.data.assistant.tools

import com.example.looma.data.assistant.Evidence
import com.example.looma.data.assistant.EvidenceScope
import com.example.looma.data.assistant.VaultEvidence
import org.json.JSONObject

internal class SearchVaultTool(
    private val evidence: VaultEvidence,
    private val scope: EvidenceScope,
    private val pool: MutableList<Evidence>,
    private val searched: MutableSet<String>,
    private val onPartial: (Boolean) -> Unit = {},
    private val onStep: (String) -> Unit = {},
) : AgentTool {
    override val spec = ToolSpec(
        name = "search_vault",
        description = "Search the user's notes for passages matching short keywords.",
        parametersSchemaJson = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""",
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val query = args.optString("query").trim()
        if (query.length !in 1..200) {
            return ToolResult("Query must be between 1 and 200 characters.", isError = true)
        }
        if (!searched.add(query.lowercase())) {
            return ToolResult("Already searched for '$query'. Use existing passages or try different keywords.")
        }
        onStep(query)
        val found = evidence.search(scope, query, 4)
        if (found.partial) onPartial(true)
        val added = mutableListOf<Pair<Int, Evidence>>()
        for (hit in found.hits) {
            if (pool.none { it.id == hit.id && it.anchor == hit.anchor }) {
                val resolved = evidence.resolve(scope.vault, hit) ?: continue
                pool += resolved
                added += pool.size to resolved
                if (added.size >= 2) break
            }
        }
        if (added.isEmpty()) {
            return ToolResult("No new passages found for '$query'.")
        }
        val output = buildString {
            appendLine("Added ${added.size} passage(s):")
            added.forEach { (id, item) ->
                append("[").append(id).append("] ")
                append(item.id.path.relativePath)
                if (item.section.isNotBlank()) append(" (").append(item.section).append(")")
                appendLine(":")
                appendLine(item.text.take(600))
            }
        }.trim()
        return ToolResult(output)
    }
}

internal class ReadPassageTool(
    private val evidence: VaultEvidence,
    private val scope: EvidenceScope,
    private val pool: MutableList<Evidence>,
    private val expanded: MutableSet<Int>,
    private val onStep: (String) -> Unit = {},
) : AgentTool {
    override val spec = ToolSpec(
        name = "read_passage",
        description = "Read more surrounding context for an existing passage by its 1-based id.",
        parametersSchemaJson = """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}""",
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val id = args.optInt("id", -1)
        val index = id - 1
        if (index !in pool.indices) {
            return ToolResult("Invalid passage id: $id. Valid range: 1..${pool.size}.", isError = true)
        }
        val current = pool[index]
        val path = current.id.path.relativePath
        if (!expanded.add(index)) {
            return ToolResult("Passage [$id] $path:\n${current.text}")
        }
        onStep(path)
        val resolved = evidence.resolve(scope.vault, current, expand = true)
            ?: return ToolResult("Passage $id ($path) is no longer available.", isError = true)
        pool[index] = resolved
        return ToolResult("Passage [$id] $path:\n${resolved.text}")
    }
}

internal class VaultToolsProvider(
    evidence: VaultEvidence,
    scope: EvidenceScope,
    pool: MutableList<Evidence>,
    searched: MutableSet<String> = mutableSetOf(),
    expanded: MutableSet<Int> = mutableSetOf(),
    onPartial: (Boolean) -> Unit = {},
    onStep: (String) -> Unit = {},
) : ToolsProvider {
    private val toolList = listOf<AgentTool>(
        SearchVaultTool(evidence, scope, pool, searched, onPartial, onStep),
        ReadPassageTool(evidence, scope, pool, expanded, onStep),
    )

    override fun tools(): List<AgentTool> = toolList
}
