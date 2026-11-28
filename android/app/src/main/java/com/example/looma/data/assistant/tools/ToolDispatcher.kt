package com.example.looma.data.assistant.tools

import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject

internal data class ParsedToolCall(
    val name: String,
    val argumentsJson: String,
)

internal class ToolDispatcher {
    suspend fun dispatch(
        provider: ToolsProvider,
        name: String,
        rawArgsJson: String,
    ): ToolResult {
        val tool = findTool(provider, name)
            ?: return ToolResult(output = "Unknown tool: $name", isError = true)
        val args = if (rawArgsJson.isBlank()) {
            JSONObject()
        } else {
            extractJsonObject(rawArgsJson)
                ?: return ToolResult(output = "Invalid JSON arguments for $name", isError = true)
        }
        return try {
            tool.execute(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(output = e.message ?: "Tool execution failed", isError = true)
        }
    }

    fun extractToolCall(rawModelOutput: String): ParsedToolCall? {
        val trimmed = rawModelOutput.trim()
        if (trimmed.isEmpty()) return null
        val candidate = unwrapTopLevelCodeFence(trimmed)
        if (!candidate.startsWith("{") || !candidate.endsWith("}")) return null
        val json = tryParseObject(candidate) ?: return null
        val toolName = json.optString("tool").trim()
        val nameKey = json.optString("name").trim()
        val hasArgs = json.has("arguments") || json.has("args") || json.has("parameters")
        val name = toolName.ifEmpty { if (hasArgs) nameKey else "" }
        if (name.isEmpty()) return null
        val argsObj = json.optJSONObject("arguments")
            ?: json.optJSONObject("args")
            ?: json.optJSONObject("parameters")
        val argsStr = when {
            argsObj != null -> argsObj.toString()
            json.has("arguments") -> json.optString("arguments")
            json.has("args") -> json.optString("args")
            json.has("parameters") -> json.optString("parameters")
            else -> "{}"
        }
        return ParsedToolCall(name = name, argumentsJson = argsStr)
    }

    fun extractJsonObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        tryParseObject(trimmed)?.let { return it }

        val unfenced = stripCodeFence(trimmed)
        if (unfenced != trimmed) {
            tryParseObject(unfenced)?.let { return it }
        }

        val start = unfenced.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until unfenced.length) {
            val ch = unfenced[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) {
                        val candidate = unfenced.substring(start, i + 1)
                        return tryParseObject(candidate)
                    }
                }
            }
        }
        return null
    }

    private fun findTool(provider: ToolsProvider, name: String): AgentTool? {
        val target = normalizeName(name)
        return provider.tools().firstOrNull { normalizeName(it.spec.name) == target }
    }

    private fun normalizeName(name: String): String =
        name.trim().lowercase().replace("-", "").replace("_", "")

    private fun unwrapTopLevelCodeFence(trimmed: String): String {
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) return trimmed
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0) return trimmed
        val inner = trimmed.substring(firstNewline + 1, trimmed.length - 3).trim()
        return if (inner.contains("```")) trimmed else inner
    }

    private fun stripCodeFence(text: String): String {
        val fenceStart = text.indexOf("```")
        if (fenceStart < 0) return text
        val lineEnd = text.indexOf('\n', fenceStart)
        if (lineEnd < 0) return text
        val fenceEnd = text.lastIndexOf("```")
        if (fenceEnd <= lineEnd) return text
        return text.substring(lineEnd + 1, fenceEnd).trim()
    }

    private fun tryParseObject(text: String): JSONObject? =
        try {
            JSONObject(text)
        } catch (_: JSONException) {
            null
        }
}
