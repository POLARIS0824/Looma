package com.example.looma.data.index

import com.example.looma.data.note.MarkdownLink
import com.example.looma.data.note.MarkdownMetadata
import com.example.looma.data.note.SavedNote
import com.example.looma.data.note.markdownSnippetOf
import com.example.looma.data.note.resolveCover
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.wiki.WikiContext
import java.io.File
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

internal data class FileFingerprint(
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

internal fun File.fingerprint() = FileFingerprint(length(), lastModified())

internal fun BasicFileAttributes.fingerprint() = FileFingerprint(size(), lastModifiedTime().toMillis())

internal data class IndexedNote(
    val id: NoteId,
    val title: String,
    val snippet: String,
    val coverPath: String?,
    val modifiedAt: Instant,
    val fingerprint: FileFingerprint,
    val tags: List<String>,
    val links: List<MarkdownLink>,
)

internal data class IndexDelta(
    val vaultId: VaultId,
    val upserts: List<IndexedNote> = emptyList(),
    val removed: List<NotePath> = emptyList(),
    val signatures: Map<NotePath, ByteArray> = emptyMap(),
)

internal data class IndexedSearchSignature(
    val fingerprint: FileFingerprint,
    val bytes: ByteArray,
)

internal interface NoteIndex {
    suspend fun load(vaultId: VaultId): List<IndexedNote>

    suspend fun wikiContext(id: NoteId): WikiContext =
        WikiIndexSnapshot(id.vaultId, load(id.vaultId)).context(id)

    suspend fun searchSignatures(vaultId: VaultId): Map<NotePath, IndexedSearchSignature> = emptyMap()

    suspend fun pathsNeedingSearchSignature(vaultId: VaultId): Set<NotePath> = emptySet()

    suspend fun version(vaultId: VaultId): Long

    suspend fun apply(delta: IndexDelta)

    suspend fun applyIfVersion(delta: IndexDelta, expectedVersion: Long): Boolean
}

internal fun indexedNote(
    vaultId: VaultId,
    root: File,
    file: File,
    text: String,
    metadata: MarkdownMetadata,
    fingerprint: FileFingerprint = file.fingerprint(),
): IndexedNote {
    return IndexedNote(
        id = NoteId(vaultId, NotePath(file.relativeTo(root).invariantSeparatorsPath)),
        title = metadata.title,
        snippet = markdownSnippetOf(text, metadata.title),
        coverPath = resolveCover(root, file, metadata.imageReferences),
        modifiedAt = Instant.ofEpochMilli(fingerprint.modifiedAtMillis),
        fingerprint = fingerprint,
        tags = metadata.tags.toList(),
        links = metadata.links,
    )
}

internal fun SavedNote.toIndexedNote(): IndexedNote =
    indexedNote(
        stored.id.vaultId,
        stored.root,
        stored.file,
        stored.content,
        metadata,
        fingerprint = stored.fingerprint ?: stored.file.fingerprint(),
    )
