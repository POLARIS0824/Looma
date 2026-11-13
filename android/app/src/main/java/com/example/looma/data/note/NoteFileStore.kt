package com.example.looma.data.note

import com.example.looma.domain.markdown.ImageContext
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.NoteWriteMode
import com.example.looma.domain.model.TrashedNote
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class StoredMarkdown(
    val id: NoteId,
    val content: String,
    val modifiedAt: Instant,
    val root: File,
    val file: File,
    val revision: NoteRevision,
)

internal data class StoredNoteStats(
    val path: NotePath,
    val sizeBytes: Long,
    val modifiedAt: Instant,
)

internal data class ConflictCopy(val id: NoteId, val revision: NoteRevision)

internal class NoteFileStore(
    private val vault: Vault,
) {
    suspend fun readNote(id: NoteId): StoredMarkdown = withContext(Dispatchers.IO) {
        val (vault, ops) = currentVault(id.vaultId)
        val file = ops.noteFile(id.path)
        storedMarkdown(vault, ops.rootDir, file, readMarkdownBytes(file))
    }

    suspend fun imageContext(id: NoteId): ImageContext? = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        ImageContext(
            vaultRoot = ops.rootDir.absolutePath,
            noteFile = ops.noteFile(id.path).absolutePath,
        )
    }

    suspend fun noteStats(id: NoteId): StoredNoteStats = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val file = ops.noteFile(id.path)
        StoredNoteStats(
            path = id.path,
            sizeBytes = file.length(),
            modifiedAt = Instant.ofEpochMilli(file.lastModified()),
        )
    }

    suspend fun modifiedAt(id: NoteId): Instant = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        Instant.ofEpochMilli(ops.noteFile(id.path).lastModified())
    }

    suspend fun readText(id: NoteId): Pair<String, Instant> = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val file = ops.noteFile(id.path)
        readMarkdown(file) to Instant.ofEpochMilli(file.lastModified())
    }

    suspend fun createNote(
        title: String,
        folder: FolderPath = FolderPath.Root,
    ): StoredMarkdown = withContext(Dispatchers.IO) {
        val ops = VaultFileOps(File(vault.rootPath))
        val file = ops.newNoteFile(folder, title, fallbackName = UNTITLED_FALLBACK)
        storedMarkdown(vault, ops.rootDir, file, byteArrayOf())
    }

    suspend fun discardNote(id: NoteId) = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val file = ops.noteFile(id.path)
        if (!file.delete()) throw VaultFileException(VaultFileFailure.IO)
    }

    suspend fun saveNote(
        id: NoteId,
        content: String,
        mode: NoteWriteMode,
    ): StoredMarkdown = withContext(Dispatchers.IO) {
        val (vault, ops) = currentVault(id.vaultId)
        val file = ops.noteFile(id.path)
        val current = readMarkdownBytes(file)
        val actualRevision = NoteRevision.of(current)
        val expectedRevision = when (mode) {
            is NoteWriteMode.Checked -> mode.revision
            is NoteWriteMode.Overwrite -> mode.revision
        }
        if (actualRevision != expectedRevision) throw NoteChangedExternallyException(actualRevision)
        val bytes = markdownBytes(content)
        if (mode is NoteWriteMode.Checked && content == current.toString(Charsets.UTF_8)) {
            return@withContext storedMarkdown(vault, ops.rootDir, file, current)
        }
        if (mode is NoteWriteMode.Overwrite) backupConflictBytes(ops.rootDir, id.path, current)
        writeBytesAtomically(file, bytes) {
            val observed = if (file.isFile) NoteRevision.of(readMarkdownBytes(file)) else NoteRevision.Unknown
            if (observed != actualRevision) throw NoteChangedExternallyException(observed)
        }
        storedMarkdown(vault, ops.rootDir, file, bytes)
    }

    suspend fun writeConflictCopy(
        id: NoteId,
        content: String,
        previous: ConflictCopy?,
    ): StoredMarkdown = withContext(Dispatchers.IO) {
        val (vault, ops) = currentVault(id.vaultId)
        val root = ops.rootDir
        val bytes = markdownBytes(content)
        val folder = conflictCopyDirectory(id, ops)
        val target = previous?.let { conflictCopyFile(id, it, root, folder) }
        val current = target?.let(::readConflictCopy)
        if (previous != null && target != null && current != null && NoteRevision.of(current) == previous.revision) {
            if (bytes.contentEquals(current)) return@withContext storedMarkdown(vault, root, target, current)
            try {
                writeBytesAtomically(target, bytes) {
                    val observed = readConflictCopy(target)?.let(NoteRevision::of) ?: NoteRevision.Unknown
                    if (observed != previous.revision) throw NoteChangedExternallyException(observed)
                }
                return@withContext storedMarkdown(vault, root, target, bytes)
            } catch (_: NoteChangedExternallyException) {
            }
        }
        val noteName = File(id.path.relativePath).nameWithoutExtension
        val file = createConflictFile(folder, noteName, bytes, avoid = target)
        storedMarkdown(vault, root, file, bytes)
    }

    suspend fun discardConflictCopy(id: NoteId, copy: ConflictCopy): Boolean = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val folder = conflictCopyDirectory(id, ops)
        val target = conflictCopyFile(id, copy, ops.rootDir, folder) ?: return@withContext false
        val current = readConflictCopy(target) ?: return@withContext false
        NoteRevision.of(current) == copy.revision && Files.deleteIfExists(target.toPath())
    }

    private fun conflictCopyDirectory(id: NoteId, ops: VaultFileOps): File {
        val folderPath = id.path.relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        return ops.folderDirectory(FolderPath(folderPath))
    }

    private fun conflictCopyFile(id: NoteId, copy: ConflictCopy, root: File, folder: File): File? {
        if (copy.id.vaultId != id.vaultId || copy.id == id) return null
        val file = File(root, copy.id.path.relativePath)
        return file.takeIf { it.parentFile == folder && it.extension.equals("md", ignoreCase = true) }
    }

    private fun readConflictCopy(file: File): ByteArray? = try {
        if (file.canonicalFile == file && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) {
            readMarkdownBytes(file)
        } else {
            null
        }
    } catch (_: IOException) {
        null
    }

    suspend fun deleteNote(id: NoteId): TrashedNote = withContext(Dispatchers.IO) {
        val (_, ops) = currentVault(id.vaultId)
        val record = ops.trash(ops.noteFile(id.path))
        TrashedNote(
            trashId = record.trashId,
            originalPath = record.originalPath,
            deletedAt = record.deletedAt,
            title = record.originalPath.displayName(),
            excerpt = "",
        )
    }

    suspend fun listTrashedNotes(): List<TrashedNote> = withContext(Dispatchers.IO) {
        val ops = VaultFileOps(File(vault.rootPath))
        val parser = MarkdownParser()
        ops.trashed().map { record ->
            val metadata = runCatching { readMarkdown(record.file) }
                .mapCatching { read -> parser.parse(read, record.originalPath.displayName()) }
                .getOrNull()
            TrashedNote(
                trashId = record.trashId,
                originalPath = record.originalPath,
                deletedAt = record.deletedAt,
                title = metadata?.title.orEmpty().ifBlank { record.originalPath.displayName() },
                excerpt = metadata?.excerpt.orEmpty(),
            )
        }
    }

    suspend fun deleteTrashedNote(trashId: String) = withContext(Dispatchers.IO) {
        VaultFileOps(File(vault.rootPath)).deletePermanently(trashId)
    }

    suspend fun restoreNote(trashId: String): StoredMarkdown = withContext(Dispatchers.IO) {
        val ops = VaultFileOps(File(vault.rootPath))
        val record = ops.trashed().firstOrNull { it.trashId == trashId }
            ?: throw VaultFileException(VaultFileFailure.NOT_FOUND)
        val bytes = readMarkdownBytes(record.file)
        val file = ops.restore(record)
        storedMarkdown(vault, ops.rootDir, file, bytes)
    }

    suspend fun restoreNotes(trashIds: List<String>): Pair<List<StoredMarkdown>, TrashBulkOutcome> =
        withContext(Dispatchers.IO) {
            val ops = VaultFileOps(File(vault.rootPath))
            bulkOverTrash(ops, trashIds) { record ->
                val bytes = readMarkdownBytes(record.file)
                storedMarkdown(vault, ops.rootDir, ops.restore(record), bytes)
            }
        }

    suspend fun deleteTrashedNotes(trashIds: List<String>): TrashBulkOutcome =
        withContext(Dispatchers.IO) {
            val ops = VaultFileOps(File(vault.rootPath))
            bulkOverTrash(ops, trashIds) { record -> ops.deletePermanently(record) }.second
        }

    private fun backupConflictBytes(root: File, path: NotePath, bytes: ByteArray): File {
        try {
            val mirroredNote = File(File(root, CONFLICT_DIRECTORY), path.relativePath)
            val directory = checkNotNull(mirroredNote.parentFile)
            if (directory.canonicalFile != directory) throw VaultFileException(VaultFileFailure.INVALID_PATH)
            ensureDirectory(directory)
            val timestamp = LocalDateTime.now().format(CONFLICT_TIMESTAMP)
            val file = generateSequence(1) { it + 1 }
                .map { copy -> File(directory, conflictBackupName(mirroredNote.nameWithoutExtension, timestamp, copy)) }
                .first { !Files.exists(it.toPath(), NOFOLLOW_LINKS) }
            writeBytesAtomically(file, bytes) {
                if (directory.canonicalFile != directory || Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                    throw VaultFileException(VaultFileFailure.ALREADY_EXISTS)
                }
            }
            return file
        } catch (failure: IOException) {
            throw NoteBackupFailedException(failure)
        }
    }

    private fun createConflictFile(folder: File, noteName: String, bytes: ByteArray, avoid: File? = null): File {
        if (folder.canonicalFile != folder) throw VaultFileException(VaultFileFailure.INVALID_PATH)
        ensureDirectory(folder)
        val timestamp = LocalDateTime.now().format(CONFLICT_TIMESTAMP)
        for (copy in generateSequence(1) { it + 1 }) {
            val file = File(folder, conflictBackupName(noteName, timestamp, copy))
            if (file == avoid) continue
            val channel = try {
                FileChannel.open(file.toPath(), CREATE_NEW, WRITE)
            } catch (_: FileAlreadyExistsException) {
                continue
            }
            channel.use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
            return file
        }
        error("Conflict filename sequence ended")
    }

    private fun conflictBackupName(noteName: String, timestamp: String, copy: Int): String {
        val suffix = if (copy == 1) " (conflicted copy $timestamp).md" else " (conflicted copy $timestamp) $copy.md"
        val stemBudget = MAX_FILENAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size
        return noteName.takeUtf8Bytes(stemBudget).trimEnd() + suffix
    }

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < length) {
            val codePoint = codePointAt(end)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return substring(0, end)
    }

    private fun currentVault(expected: VaultId): Pair<Vault, VaultFileOps> {
        if (vault.id != expected) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        return vault to VaultFileOps(File(vault.rootPath))
    }

    private fun storedMarkdown(vault: Vault, root: File, file: File, bytes: ByteArray): StoredMarkdown =
        StoredMarkdown(
            id = NoteId(vault.id, NotePath(file.relativeTo(root).invariantSeparatorsPath)),
            content = bytes.toString(Charsets.UTF_8),
            modifiedAt = Instant.ofEpochMilli(file.lastModified()),
            root = root,
            file = file,
            revision = NoteRevision.of(bytes),
        )

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw VaultFileException(VaultFileFailure.IO)
    }

    private companion object {
        const val UNTITLED_FALLBACK = "Untitled"
        const val CONFLICT_DIRECTORY = ".looma/conflicts"
        const val MAX_FILENAME_BYTES = 255
        val CONFLICT_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss")
        val REVISION_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal fun <T> bulkOverTrash(
    ops: VaultFileOps,
    trashIds: List<String>,
    action: (TrashRecord) -> T,
): Pair<List<T>, TrashBulkOutcome> {
    val records = ops.trashed().associateBy { it.trashId }
    val results = mutableListOf<T>()
    var failed = 0
    for (trashId in trashIds) {
        val record = records[trashId]
        if (record == null) {
            failed++
            continue
        }
        try {
            results += action(record)
        } catch (e: IOException) {
            failed++
        }
    }
    return results to TrashBulkOutcome(results.size, failed)
}

private fun NotePath.displayName(): String =
    relativePath.substringAfterLast('/').removeSuffix(".md").ifBlank { relativePath }

class NoteChangedExternallyException(val actualRevision: NoteRevision) : IOException("note changed outside the app")

class NoteBackupFailedException(cause: IOException) : IOException("could not back up note before overwrite", cause)
