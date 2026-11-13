package com.example.looma.data.note

import com.example.looma.domain.model.Note

internal data class SavedNote(
    val stored: StoredMarkdown,
    val metadata: MarkdownMetadata,
) {
    val id get() = stored.id
    val content get() = stored.content
    val modifiedAt get() = stored.modifiedAt

    fun toNote(): Note = stored.toNote(metadata.title, metadata.tags)
}

internal fun MarkdownParser.savedNote(stored: StoredMarkdown): SavedNote =
    SavedNote(stored, parse(stored.content, stored.file.nameWithoutExtension))

internal fun MarkdownParser.note(stored: StoredMarkdown): Note =
    stored.toNote(
        title = title(stored.content, stored.file.nameWithoutExtension),
        tags = tags(stored.content),
    )

private fun StoredMarkdown.toNote(title: String, tags: Set<String>): Note = Note(
    id = id,
    title = title,
    content = content,
    modifiedAt = modifiedAt,
    tags = tags,
    revision = revision,
)
