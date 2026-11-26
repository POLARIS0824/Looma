package com.example.looma.data.assistant.skills

import com.example.looma.data.assistant.tools.AgentSkillDetail

internal object BuiltInSkills {
    private val rawSkills = listOf(
        """
        ---
        name: vault-briefing
        description: Summarize recent notes, active themes, and open tasks across the vault.
        ---
        When the user asks for a briefing, catch-up, or overview of their vault:
        1. Call `list_notes` to inspect recent notes and folder structure.
        2. Read the most relevant or recently updated notes with `read_note`.
        3. Use `keyword_search` for open tasks (`- [ ]`) or action items if relevant.
        4. Present a concise briefing grouped by theme, citing exact note paths.
        """.trimIndent(),
        """
        ---
        name: note-organizer
        description: Audit folder structure, tags, and suggest or draft organized notes.
        ---
        When the user wants help organizing notes, cleaning up folders, or structuring a topic:
        1. Call `list_notes` to inspect the existing folders, tags, and note titles.
        2. Use `search_vault` or `read_note` to check overlapping or uncategorized notes.
        3. Propose a clear folder or tag structure grounded in existing conventions.
        4. Only call `create_note` when the user asks to save a new summary or index note.
        """.trimIndent(),
        """
        ---
        name: wiki-linker
        description: Discover related notes and suggest [[wiki-link]] connections or backlinks.
        ---
        When the user wants to connect notes, find backlinks, or build MOCs:
        1. Call `get_note_links` on the target note to see existing outgoing links and referrers.
        2. Use `search_vault` and `keyword_search` to find semantically or lexically related notes.
        3. Read candidate notes with `read_note` to verify the connection is meaningful.
        4. Recommend specific `[[note]]` links with brief rationale and cite each note path.
        """.trimIndent(),
    )

    val all: List<AgentSkillDetail> = rawSkills.mapNotNull { SkillParser.parse(it) }
}
