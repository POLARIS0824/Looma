package com.example.looma.data.search

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.search.PassageAnchor
import com.example.looma.embedding.EmbeddingGemma
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal class SemanticVectorCache(private val root: File) {
    fun prune(vaultId: VaultId, notes: Set<NoteId>) {
        val known = notes.mapTo(HashSet()) { cacheName(it) }
        directory(vaultId).listFiles().orEmpty().forEach { file ->
            if (file.name !in known) file.delete()
        }
    }

    fun forNote(id: NoteId): NoteVectors =
        NoteVectors(directory(id.vaultId).resolve(cacheName(id)))

    private fun directory(vaultId: VaultId) = root.resolve(key(vaultId.value))
    private fun cacheName(id: NoteId) = "${key(id.path.relativePath)}.vec"
    private fun key(value: String) = (NoteRevision.of(value.toByteArray()) as NoteRevision.Known).sha256
}

internal class NoteVectors(private val file: File) {
    fun best(
        revision: NoteRevision.Known,
        source: String,
        query: FloatArray,
        checkActive: () -> Unit,
    ): CachedRanking? {
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != revision.sha256) return null
                val count = input.readInt()
                if (count !in 0..source.length) return null
                var best: ScoredPassage? = null
                repeat(count) {
                    checkActive()
                    val anchor = PassageAnchor(revision.sha256, input.readInt(), input.readInt())
                    val section = input.readUTF()
                    if (!anchor.matches(revision, source) || section.length > 256) return null
                    var score = 0.0
                    for (value in query) score += value.toDouble() * input.readFloat()
                    if (!score.isFinite()) return null
                    val previous = best
                    if (previous == null || score > previous.score) best = ScoredPassage(anchor, section, score)
                }
                CachedRanking(best)
            }
        } catch (_: IOException) {
            null
        }
    }

    fun replace(
        revision: NoteRevision.Known,
        chunks: List<SemanticChunk>,
        vector: (SemanticChunk) -> FloatArray,
    ) {
        val directory = checkNotNull(file.parentFile)
        Files.createDirectories(directory.toPath())
        val pending = directory.resolve("${file.name}.partial")
        try {
            FileOutputStream(pending).use { stream ->
                val output = DataOutputStream(stream.buffered())
                output.writeUTF(revision.sha256)
                output.writeInt(chunks.size)
                for (chunk in chunks) {
                    output.writeInt(chunk.start)
                    output.writeInt(chunk.end)
                    output.writeUTF(chunk.section)
                    val embedding = vector(chunk)
                    check(embedding.size == EmbeddingGemma.DIMENSIONS && embedding.all { it.isFinite() })
                    for (value in embedding) output.writeFloat(value)
                }
                output.flush()
                stream.fd.sync()
            }
            Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            pending.delete()
        }
    }
}

internal data class ScoredPassage(val anchor: PassageAnchor, val section: String, val score: Double)
internal data class CachedRanking(val best: ScoredPassage?)
