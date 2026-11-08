package com.example.looma.data.note

import com.example.looma.domain.model.Note

internal data class SavedNote(
    val stored: StoredMarkdown,
    val metadata: MarkdownMetadata,
) {
    val id get() = stored.id
    val content get() = stored.content
    val modifiedAt get() = stored.modifiedAt

    fun toNote(): Note = Note(
        id = stored.id,
        title = metadata.title,
        content = stored.content,
        modifiedAt = stored.modifiedAt,
        tags = metadata.tags,
        revision = stored.revision,
    )
}

internal fun MarkdownParser.savedNote(stored: StoredMarkdown): SavedNote =
    SavedNote(stored, parse(stored.content, stored.file.nameWithoutExtension))
