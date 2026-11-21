package com.example.looma.data.search

import com.example.looma.data.note.writeBytesAtomically
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
    fun hasCompletedIndex(vaultId: VaultId): Boolean = directory(vaultId).resolve("complete").isFile

    fun hasSearchableIndex(vaultId: VaultId): Boolean {
        val directory = directory(vaultId)
        if (hasCompletedIndex(vaultId)) return true
        return try {
            Files.newDirectoryStream(directory.toPath(), "*.vec").use { it.iterator().hasNext() }
        } catch (_: IOException) {
            false
        }
    }

    fun enabled(vaultId: VaultId): Boolean =
        directory(vaultId).resolve("enabled").isFile || hasSearchableIndex(vaultId)

    fun enable(vaultId: VaultId) {
        val dir = directory(vaultId)
        Files.createDirectories(dir.toPath())
        dir.resolve("enabled").createNewFile()
    }

    fun partial(vaultId: VaultId): Boolean =
        directory(vaultId).resolve("partial-index").isFile || markers(vaultId, "*.skipped").isNotEmpty()

    fun markScanPartial(vaultId: VaultId, partial: Boolean) {
        val marker = directory(vaultId).resolve("partial-index")
        if (partial) marker.createNewFile() else Files.deleteIfExists(marker.toPath())
    }

    fun markSkipped(id: NoteId, skipped: Boolean) {
        val marker = directory(id.vaultId).resolve("${key(id.path.relativePath)}.skipped")
        if (skipped) marker.createNewFile() else Files.deleteIfExists(marker.toPath())
    }

    @Synchronized
    fun markDirty(id: NoteId) {
        val file = directory(id.vaultId).resolve("${key(id.path.relativePath)}.dirty")
        val value = "${java.util.UUID.randomUUID()}\n${id.path.relativePath}"
        writeBytesAtomically(file, value.toByteArray())
    }

    fun hasPending(vaultId: VaultId): Boolean = try {
        Files.newDirectoryStream(directory(vaultId).toPath(), "*.dirty").use { it.iterator().hasNext() }
    } catch (_: java.nio.file.NoSuchFileException) {
        false
    }

    fun pending(vaultId: VaultId): Map<File, String> =
        markers(vaultId, "*.dirty").mapNotNull { file ->
            try { file to file.readText() } catch (_: java.io.FileNotFoundException) { null }
        }.toMap()

    private fun markers(vaultId: VaultId, pattern: String): List<File> = try {
        Files.newDirectoryStream(directory(vaultId).toPath(), pattern).use { stream -> stream.map { it.toFile() } }
    } catch (_: java.nio.file.NoSuchFileException) {
        emptyList()
    }

    @Synchronized
    fun acknowledge(file: File, value: String) {
        if (file.isFile && file.readText() == value) Files.deleteIfExists(file.toPath())
    }

    fun complete(vaultId: VaultId, checkActive: () -> Unit) {
        val directory = directory(vaultId)
        Files.createDirectories(directory.toPath())
        writeBytesAtomically(directory.resolve("complete"), byteArrayOf(), checkActive)
    }

    fun prune(vaultId: VaultId, notes: Set<NoteId>, checkActive: () -> Unit = {}) {
        val known = notes.mapTo(HashSet()) { cacheName(it) }
        directory(vaultId).listFiles().orEmpty().forEach { file ->
            checkActive()
            if (file.extension == "vec" && file.name !in known) file.delete()
            if (file.extension == "skipped" && "${file.nameWithoutExtension}.vec" !in known) file.delete()
        }
    }

    fun forNote(id: NoteId): NoteVectors =
        NoteVectors(directory(id.vaultId).resolve(cacheName(id)))

    private fun directory(vaultId: VaultId) = root.resolve(key(vaultId.value))
    private fun cacheName(id: NoteId) = "${key(id.path.relativePath)}.vec"
    private fun key(value: String) = (NoteRevision.of(value.toByteArray()) as NoteRevision.Known).sha256
}

internal class NoteVectors(private val file: File) {
    fun exists(): Boolean = file.isFile

    fun isCurrent(revision: NoteRevision.Known, source: String, checkActive: () -> Unit): Boolean =
        read(revision, source, null, checkActive) != null

    fun best(
        revision: NoteRevision.Known,
        source: String,
        query: FloatArray,
        checkActive: () -> Unit,
    ): CachedRanking? = read(revision, source, query, checkActive)

    fun rank(query: FloatArray, checkActive: () -> Unit): ScoredPassage? =
        read(null, null, query, checkActive)?.best

    fun delete() { Files.deleteIfExists(file.toPath()) }

    private fun read(
        revision: NoteRevision.Known?,
        source: String?,
        query: FloatArray?,
        checkActive: () -> Unit,
    ): CachedRanking? {
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val digest = input.readUTF()
                if (digest.length != 64 || (revision != null && digest != revision.sha256)) return null
                val count = input.readInt()
                if (count !in 0..(source?.length ?: com.example.looma.data.note.MAX_MARKDOWN_BYTES.toInt())) return null
                var best: ScoredPassage? = null
                repeat(count) {
                    checkActive()
                    val anchor = PassageAnchor(digest, input.readInt(), input.readInt())
                    val section = input.readUTF()
                    if (section.length > 256 || anchor.start < 0 || anchor.end <= anchor.start) return null
                    if (revision != null && source != null && !anchor.matches(revision, source)) return null
                    var score = 0.0
                    repeat(EmbeddingGemma.DIMENSIONS) { dimension ->
                        val value = input.readFloat()
                        if (!value.isFinite()) return null
                        if (query != null) score += query[dimension].toDouble() * value
                    }
                    if (!score.isFinite()) return null
                    val previous = best
                    if (query != null && (previous == null || score > previous.score)) {
                        best = ScoredPassage(anchor, section, score)
                    }
                }
                if (input.read() != -1) return null
                CachedRanking(best)
            }
        } catch (_: IOException) {
            null
        }
    }

    inline fun replace(
        revision: NoteRevision.Known,
        chunks: List<SemanticChunk>,
        beforeReplace: () -> Unit = {},
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
            beforeReplace()
            Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            pending.delete()
        }
    }
}

internal data class ScoredPassage(val anchor: PassageAnchor, val section: String, val score: Double)
internal data class CachedRanking(val best: ScoredPassage?)
