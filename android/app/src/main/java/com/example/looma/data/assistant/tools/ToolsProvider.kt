package com.example.looma.data.assistant.tools

internal interface ToolsProvider {
    fun tools(): List<AgentTool>
    fun specs(): List<ToolSpec> = tools().map { it.spec }
}
