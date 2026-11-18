package com.example.looma.domain.model

import java.time.Instant

@JvmInline
value class NotePath(
    val relativePath: String
)

fun String.removeMarkdownSuffix(): String =
    if (endsWith(".md", ignoreCase = true)) dropLast(3) else this

data class NoteId(
    val vaultId: VaultId,
    val path: NotePath,
)

data class Note(
    val id: NoteId,
    val title: String,
    val content: String,
    val modifiedAt: Instant,
    val tags: Set<String> = emptySet(),
    val revision: NoteRevision = NoteRevision.of(content.toByteArray(Charsets.UTF_8)),
)

data class NoteSummary(
    val id: NoteId,
    val title: String,
    val modifiedAt: Instant,
    val tags: Set<String> = emptySet(),
)
