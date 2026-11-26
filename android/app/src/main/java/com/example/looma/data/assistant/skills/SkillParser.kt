package com.example.looma.data.assistant.skills

import com.example.looma.data.assistant.tools.AgentSkillDetail

internal object SkillParser {
    fun parse(raw: String): AgentSkillDetail? {
        val normalized = raw.removePrefix("\uFEFF").replace("\r\n", "\n")
        val lines = normalized.lines()
        if (lines.firstOrNull()?.trim() != "---") return null

        var closingIndex = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                closingIndex = i
                break
            }
        }
        if (closingIndex < 0) return null

        var name = ""
        var description = ""
        for (i in 1 until closingIndex) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val value = stripSurroundingQuotes(line.substring(colon + 1).trim())
            when (key) {
                "name" -> name = value
                "description" -> description = value
            }
        }

        val instructions = lines
            .subList(closingIndex + 1, lines.size)
            .joinToString("\n")
            .trim()

        if (name.isBlank() || description.isBlank() || instructions.isBlank()) {
            return null
        }
        return AgentSkillDetail(
            name = name,
            description = description,
            instructions = instructions,
        )
    }

    private fun stripSurroundingQuotes(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length - 1).trim()
            }
        }
        return value
    }
}
