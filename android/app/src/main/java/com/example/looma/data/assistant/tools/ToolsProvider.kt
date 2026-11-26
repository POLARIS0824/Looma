package com.example.looma.data.assistant.tools

internal fun interface ToolsProvider {
    fun tools(): List<AgentTool>
    fun specs(): List<ToolSpec> = tools().map { it.spec }
}

internal class DefaultToolsProvider(
    private val toolList: List<AgentTool>,
) : ToolsProvider {
    override fun tools(): List<AgentTool> = toolList
}
