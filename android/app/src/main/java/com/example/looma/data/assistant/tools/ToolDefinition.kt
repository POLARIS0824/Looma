package com.example.looma.data.assistant.tools

import org.json.JSONObject

internal data class ToolSpec(
    val name: String,
    val description: String,
    val parametersSchemaJson: String = "{}",
)

internal data class ToolResult(
    val output: String,
    val isError: Boolean = false,
)

internal interface AgentTool {
    val spec: ToolSpec
    suspend fun execute(args: JSONObject): ToolResult
}
