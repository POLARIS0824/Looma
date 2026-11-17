package com.example.looma.data.index

import com.example.looma.data.note.MarkdownParser
import com.example.looma.data.note.VaultFileOps
import com.example.looma.data.note.readMarkdown
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class ScanReport(
    val filesRead: Int,
    val filesKept: Int,
    val filesRemoved: Int,
)

internal data class IndexScan(
    val notes: List<IndexedNote>,
    val folders: List<VaultFolder>,
    val unreadableCount: Int,
    val report: ScanReport,
)

internal class VaultIndexer(
    private val root: File,
    private val vault: Vault,
    private val index: NoteIndex,
) {
    private val parser = MarkdownParser()

    suspend fun scan(): IndexScan = withContext(Dispatchers.IO) {
        scanAttempt(retryOnConflict = true)
    }

    private suspend fun scanAttempt(retryOnConflict: Boolean): IndexScan {
        val version = index.version(vault.id)
        val ops = VaultFileOps(root)
        val rootDir = ops.rootDir
        val folders = mutableListOf<VaultFolder>()
        val onDisk = LinkedHashMap<NotePath, FileFingerprint>()
        var complete = true

        val pending = ArrayDeque<Pair<File, Int>>()
        pending += rootDir to 0
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (directory, depth) = pending.removeFirst()
            val children = directory.listFiles()
            if (children == null) {
                complete = false
                continue
            }
            for (child in children) {
                coroutineContext.ensureActive()
                val attributes = try {
                    Files.readAttributes(child.toPath(), BasicFileAttributes::class.java)
                } catch (_: NoSuchFileException) {
                    continue
                } catch (_: IOException) {
                    complete = false
                    continue
                }
                if (attributes.isDirectory) {
                    val inside = runCatching { ops.isInside(child) }.getOrDefault(false)
                    if (!inside || child.name.startsWith('.')) continue
                    val path = FolderPath(child.relativeTo(rootDir).invariantSeparatorsPath)
                    folders += VaultFolder(vaultId = vault.id, path = path, name = child.name)
                    if (depth + 1 < MAX_DEPTH) {
                        pending += child to depth + 1
                    } else {
                        complete = false
                    }
                    continue
                }
                if (!attributes.isRegularFile || !child.extension.equals("md", ignoreCase = true)) continue
                if (!runCatching { ops.isInside(child) }.getOrDefault(false)) continue
                val path = NotePath(child.relativeTo(rootDir).invariantSeparatorsPath)
                onDisk[path] = attributes.fingerprint()
            }
        }

        val indexed = index.load(vault.id)
        val plan = planScan(indexed, onDisk).let { if (complete) it else it.copy(removed = emptyList()) }
        val indexedByPath = indexed.associateBy { it.id.path }

        val fresh = mutableListOf<IndexedNote>()
        val retained = mutableListOf<IndexedNote>()
        var unreadable = 0
        var readCount = 0
        for (path in plan.toRead) {
            coroutineContext.ensureActive()
            val file = File(rootDir, path.relativePath)
            val fingerprint = onDisk.getValue(path)
            readCount++
            val note = try {
                val text = readMarkdown(file)
                val metadata = parser.parse(text, file.nameWithoutExtension)
                val note = indexedNote(vault.id, rootDir, file, text, metadata, fingerprint)
                val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                note.takeIf { attributes.isRegularFile && attributes.fingerprint() == fingerprint }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (note != null) {
                fresh += note
            } else {
                unreadable++
                indexedByPath[path]?.let(retained::add)
            }
        }

        val delta = IndexDelta(vaultId = vault.id, upserts = fresh, removed = plan.removed)
        val applied = index.applyIfVersion(delta, expectedVersion = version)
        if (!applied && retryOnConflict) return scanAttempt(retryOnConflict = false)

        val notes = if (applied) plan.kept + fresh + retained else index.load(vault.id)
        return IndexScan(
            notes = notes,
            folders = folders.sortedBy { it.path.relativePath.lowercase() },
            unreadableCount = unreadable,
            report = ScanReport(
                filesRead = readCount,
                filesKept = if (applied) plan.kept.size else notes.count { onDisk[it.id.path] == it.fingerprint },
                filesRemoved = if (applied) plan.removed.size else 0,
            ),
        )
    }

    private companion object {
        const val MAX_DEPTH = 40
    }
}
