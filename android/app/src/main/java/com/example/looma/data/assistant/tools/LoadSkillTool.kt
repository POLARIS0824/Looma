package com.example.looma.data.assistant.tools

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

internal data class AgentSkillSummary(
    val name: String,
    val description: String,
    val isBuiltIn: Boolean,
    val enabled: Boolean,
)

internal data class AgentSkillDetail(
    val name: String,
    val description: String,
    val instructions: String,
)

internal interface SkillCatalog {
    val skills: StateFlow<List<AgentSkillSummary>>
    suspend fun refresh()
    suspend fun enabledSkills(): List<AgentSkillSummary>
    suspend fun loadSkill(name: String): AgentSkillDetail?
    suspend fun setEnabled(name: String, enabled: Boolean)
}

internal class LoadSkillTool(
    private val catalog: SkillCatalog,
) : AgentTool {
    override val spec = ToolSpec(
        name = TOOL_NAME,
        description = "Load the full instructions for an enabled agent skill by name.",
        parametersSchemaJson = """{"type":"object","properties":{"skill_name":{"type":"string","description":"The name of the skill to load."}},"required":["skill_name"]}""",
    )

    override suspend fun execute(args: JSONObject): ToolResult {
        val skillName = args.optString("skill_name").trim()
        if (skillName.isEmpty()) {
            return ToolResult("Skill name must not be blank.", isError = true)
        }
        val detail = catalog.loadSkill(skillName)
            ?: return ToolResult("Skill '$skillName' is not available or disabled.", isError = true)
        return ToolResult(
            buildString {
                appendLine("Skill: ${detail.name}")
                appendLine("Description: ${detail.description}")
                appendLine()
                append(detail.instructions)
            }.trimEnd(),
        )
    }

    companion object {
        const val TOOL_NAME = "load_skill"
    }
}
