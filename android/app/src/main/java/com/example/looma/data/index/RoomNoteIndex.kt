package com.example.looma.data.index

import androidx.room.withTransaction
import com.example.looma.data.note.MarkdownLink
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.search.SearchSignature
import java.time.Instant
import kotlinx.coroutines.sync.withLock

internal class RoomNoteIndex(private val database: NoteIndexDatabase) : NoteIndex {

    private val dao = database.noteIndexDao()

    override suspend fun load(vaultId: VaultId): List<IndexedNote> {
        val key = vaultId.value
        return database.withTransaction {
            val tagsByPath = dao.tags(key).groupBy { it.notePath }
            val linksByPath = dao.links(key).groupBy { it.notePath }
            dao.notes(key).map { note ->
                IndexedNote(
                    id = NoteId(vaultId, NotePath(note.path)),
                    title = note.title,
                    snippet = note.snippet,
                    coverPath = note.coverPath,
                    modifiedAt = Instant.ofEpochMilli(note.modifiedAt),
                    fingerprint = FileFingerprint(note.sizeBytes, note.modifiedAt),
                    tags = tagsByPath[note.path].orEmpty().map { it.tag },
                    links = linksByPath[note.path].orEmpty()
                        .map { MarkdownLink(target = it.target, embedded = it.embedded) },
                )
            }
        }
    }

    override suspend fun bodyCandidates(vaultId: VaultId, keyword: String): Set<NotePath> {
        val rows = dao.signatures(vaultId.value)
        if (rows.isEmpty()) return emptySet()
        val matched = HashSet<NotePath>()
        for (row in rows) {
            if (SearchSignature.mayContain(row.signature, keyword)) {
                matched.add(NotePath(row.path))
            }
        }
        return matched
    }

    override suspend fun version(vaultId: VaultId): Long =
        database.indexWriteMutex.withLock { currentVersion(vaultId) }

    override suspend fun apply(delta: IndexDelta) {
        writeIfVersion(delta, expectedVersion = null)
    }

    override suspend fun applyIfVersion(delta: IndexDelta, expectedVersion: Long): Boolean =
        writeIfVersion(delta, expectedVersion)

    private suspend fun writeIfVersion(delta: IndexDelta, expectedVersion: Long?): Boolean =
        database.indexWriteMutex.withLock {
            val version = currentVersion(delta.vaultId)
            if (expectedVersion != null && version != expectedVersion) return@withLock false
            if (delta.upserts.isNotEmpty() || delta.removed.isNotEmpty()) {
                database.indexVersions[delta.vaultId] = version + 1
                write(delta)
            }
            true
        }

    private fun currentVersion(vaultId: VaultId): Long = database.indexVersions[vaultId] ?: 0L

    private suspend fun write(delta: IndexDelta) {
        database.withTransaction {
            val vaultId = delta.vaultId.value
            val upsertPaths = delta.upserts.map { it.id.path.relativePath }
            if (upsertPaths.isNotEmpty()) {
                upsertPaths.chunked(MAX_SQL_PATHS).forEach { paths ->
                    dao.deleteSignatures(vaultId, paths)
                    dao.deleteTags(vaultId, paths)
                    dao.deleteLinks(vaultId, paths)
                }
                val indexedAt = System.currentTimeMillis()
                dao.upsertNotes(delta.upserts.map { it.toEntity(vaultId, indexedAt) })
                if (delta.signatures.isNotEmpty()) {
                    val signatureEntities = delta.upserts.mapNotNull { note ->
                        val bytes = delta.signatures[note.id.path] ?: return@mapNotNull null
                        NoteSearchSignatureEntity(vaultId, note.id.path.relativePath, bytes)
                    }
                    if (signatureEntities.isNotEmpty()) {
                        dao.upsertSignatures(signatureEntities)
                    }
                }
                val tags = delta.upserts.flatMap { note -> note.tagEntities(vaultId) }
                if (tags.isNotEmpty()) dao.insertTags(tags)
                val links = delta.upserts.flatMap { note -> note.linkEntities(vaultId) }
                if (links.isNotEmpty()) dao.insertLinks(links)
            }
            if (delta.removed.isNotEmpty()) {
                delta.removed.map { it.relativePath }.chunked(MAX_SQL_PATHS).forEach { paths ->
                    dao.deleteNotes(vaultId, paths)
                }
            }
        }
    }

    private fun IndexedNote.toEntity(vaultId: String, indexedAt: Long) = IndexedNoteEntity(
        vaultId = vaultId,
        path = id.path.relativePath,
        title = title,
        snippet = snippet,
        coverPath = coverPath,
        modifiedAt = modifiedAt.toEpochMilli(),
        sizeBytes = fingerprint.sizeBytes,
        indexedAt = indexedAt,
    )

    private fun IndexedNote.tagEntities(vaultId: String): List<NoteTagEntity> {
        val path = id.path.relativePath
        return tags.mapIndexed { index, tag -> NoteTagEntity(vaultId, path, tag, index) }
    }

    private fun IndexedNote.linkEntities(vaultId: String): List<NoteLinkEntity> {
        val path = id.path.relativePath
        return links.mapIndexed { index, link ->
            NoteLinkEntity(vaultId, path, index, link.target, link.embedded)
        }
    }

    private companion object {
        const val MAX_SQL_PATHS = 500
    }
}
