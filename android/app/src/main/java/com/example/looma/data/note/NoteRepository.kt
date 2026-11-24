package com.example.looma.data.note

import com.example.looma.data.index.FileFingerprint
import com.example.looma.data.index.IndexDelta
import com.example.looma.data.index.IndexedNote
import com.example.looma.data.index.NoteIndex
import com.example.looma.data.index.NoteIndexUpdater
import com.example.looma.data.index.SearchSignature
import com.example.looma.data.index.fingerprint
import com.example.looma.data.index.indexedNote
import com.example.looma.data.index.toIndexedNote
import com.example.looma.data.vault.VaultRepository
import com.example.looma.data.vault.VaultWriteCoordinator
import com.example.looma.domain.markdown.ImageContext
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteProperties
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.NoteTextStats
import com.example.looma.domain.model.NoteWriteMode
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.domain.model.TrashedNote
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultFolder
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.search.NoteSearchMatch
import com.example.looma.domain.search.SearchableNote
import com.example.looma.domain.search.searchNote
import com.example.looma.domain.wiki.WikiContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteRepository internal constructor(
    private val vaultRepository: VaultRepository,
    private val index: NoteIndex,
    onFilesChanged: suspend (Vault, IndexDelta) -> Unit = { _, _ -> },
    private val onVaultScanned: suspend (Vault, Boolean) -> Unit = { _, _ -> },
) {
    private val parser = MarkdownParser()
    private val indexUpdater = NoteIndexUpdater(index, onFilesChanged)
    private data class SignatureStamp(val inode: Long, val changed: java.nio.file.attribute.FileTime, val size: Long)
    private data class VerifiedSignature(val stamp: SignatureStamp, val bytes: ByteArray)
    private val verifiedSignatures = object : LinkedHashMap<File, VerifiedSignature>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<File, VerifiedSignature>?) = size > 4096
    }
    private val searchDispatcher = Dispatchers.IO.limitedParallelism(SEARCH_IO_PARALLELISM)
    private val stalledRelocations = ConcurrentHashMap<VaultId, StalledRelocation>()

    private data class RecentScan(val result: NoteListResult, val at: Long)
    @Volatile private var recentScan: RecentScan? = null

    internal suspend fun evidenceNotes(vault: Vault): NoteListResult {
        val recent = recentScan
        if (recent != null && recent.result.vault == vault && recent.result.complete &&
            System.nanoTime() - recent.at < 5_000_000_000L && recent.result.version == index.version(vault.id)) {
            return recent.result
        }
        return listNotes(vault)
    }

    internal suspend fun listNotes(expected: Vault? = null, force: Boolean = false): NoteListResult {
        val current = currentVault(expected?.id)
        if (expected != null && current.rootPath != expected.rootPath) {
            throw VaultFileException(VaultFileFailure.NOT_FOUND)
        }
        val stalled = VaultWriteCoordinator.write(current) {
            recoverPendingRelocations(current, retryStalled = true)
        }
        return read(current.id) { vault, _ ->
            if (vault.rootPath != current.rootPath) throw VaultFileException(VaultFileFailure.NOT_FOUND)
            val scan = indexUpdater.scan(vault, force)
            if (expected == null) onVaultScanned(vault, !scan.complete || scan.unreadableCount > 0 || stalled != null)
            val previous = recentScan?.result
            val items = if (!force && previous != null && previous.vault == vault &&
                scan.version != null && previous.version == scan.version) previous.items
                else scan.notes.map { it.toListEntry() }.sortedByDescending { it.summary.modifiedAt }
            NoteListResult(
                vault = vault,
                items = items,
                folders = scan.folders,
                unreadableCount = scan.unreadableCount,
                stalledRelocation = stalled,
                complete = scan.complete,
                version = scan.version,
            ).also { recentScan = RecentScan(it, System.nanoTime()) }
        }
    }

    internal suspend fun search(
        candidates: List<SearchableNote>,
        query: String,
    ): List<NoteSearchMatch> {
        val keyword = query.trim()
        if (keyword.isEmpty() || candidates.isEmpty()) return emptyList()
        val vault = currentVault(candidates.first().id.vaultId)
        val distinctCandidates = candidates
            .asSequence()
            .filter { it.id.vaultId == vault.id }
            .distinctBy { it.id }
            .toList()
        if (distinctCandidates.isEmpty()) return emptyList()

        val querySignature = SearchSignature.prepare(keyword)
        val (ops, signatures) = withContext(Dispatchers.IO) {
            val entries = if (querySignature == null) emptyMap() else index.searchSignatures(vault.id)
            VaultFileOps(File(vault.rootPath)) to entries
        }

        suspend fun searchCandidate(candidate: SearchableNote): NoteSearchMatch? {
            val signature = signatures[candidate.id.path]
                ?.takeIf { querySignature?.mayContain(it.bytes) == false }
            return searchCandidateOnDisk(ops, candidate, keyword, querySignature, signature?.fingerprint)
        }

        val workerCount = minOf(SEARCH_IO_PARALLELISM, distinctCandidates.size)
        if (workerCount == 1) {
            return withContext(searchDispatcher) { listOfNotNull(searchCandidate(distinctCandidates.single())) }
        }
        val cursor = AtomicInteger()
        val hits = arrayOfNulls<NoteSearchMatch>(distinctCandidates.size)
        coroutineScope {
            repeat(workerCount) {
                launch(searchDispatcher) {
                    while (true) {
                        ensureActive()
                        val itemIndex = cursor.getAndIncrement()
                        if (itemIndex >= distinctCandidates.size) break
                        hits[itemIndex] = searchCandidate(distinctCandidates[itemIndex])
                    }
                }
            }
        }
        return hits.filterNotNull()
    }

    private suspend fun searchCandidateOnDisk(
        ops: VaultFileOps,
        candidate: SearchableNote,
        keyword: String,
        query: SearchSignature.Query?,
        rejectingFingerprint: FileFingerprint?,
    ): NoteSearchMatch? {
        val context = coroutineContext
        try {
            val file = ops.noteFile(candidate.id.path)
            if (query != null && rejectingFingerprint != null && file.fingerprint() == rejectingFingerprint) {
                val stamp = signatureStamp(file)
                if (stamp != null) {
                    val known = synchronized(verifiedSignatures) { verifiedSignatures[file] }
                    if (known?.stamp == stamp) {
                        if (!query.mayContain(known.bytes)) return metadataMatch(candidate, keyword)
                    } else {
                        val text = readMarkdown(file)
                        val verified = SearchSignature.fromBody(text)
                        if (stamp == signatureStamp(file)) {
                            synchronized(verifiedSignatures) { verifiedSignatures[file] = VerifiedSignature(stamp, verified) }
                        }
                        val hit = searchNote(candidate.title, candidate.tags, text, keyword)
                        return hit?.let { NoteSearchMatch(candidate.id, it) }
                    }
                }
            }
            return openMarkdownReader(file).use { reader ->
                searchNote(candidate.title, candidate.tags, reader, keyword, checkActive = context::ensureActive)
                    ?.let { NoteSearchMatch(candidate.id, it) }
            }
        } catch (_: IOException) {
        }
        return metadataMatch(candidate, keyword)
    }

    private fun signatureStamp(file: File): SignatureStamp? = try {
        val attrs = Files.readAttributes(file.toPath(), "unix:ino,ctime,size")
        val changed = attrs["ctime"] as java.nio.file.attribute.FileTime
        // Coarse shared-storage clocks cannot distinguish equal-size edits within a tick.
        if (changed.to(java.util.concurrent.TimeUnit.NANOSECONDS) % 1_000_000L == 0L) null
        else SignatureStamp(attrs["ino"] as Long, changed, attrs["size"] as Long)
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun metadataMatch(candidate: SearchableNote, keyword: String): NoteSearchMatch? {
        val hit = searchNote(candidate.title, candidate.tags, text = "", query = keyword)
        return hit?.let { NoteSearchMatch(candidate.id, it) }
    }

    internal fun lexicalReader(vault: Vault): LexicalReader = LexicalReader(vault)

    internal inner class LexicalReader(vault: Vault) {
        private val files = VaultFileOps(File(vault.rootPath))
        private val store = NoteFileStore(vault)
        suspend fun read(id: NoteId, tests: List<SearchSignature.Query?>, metadataMatch: Boolean): StoredMarkdown? {
            val file = files.noteFile(id.path)
            val stamp = signatureStamp(file)
            val known = synchronized(verifiedSignatures) { verifiedSignatures[file] }
            if (!metadataMatch && stamp != null && known != null && known.stamp == stamp &&
                tests.all { it != null && !it.mayContain(known.bytes) }) return null
            val source = store.readNote(id, requireUtf8 = true)
            if (stamp != null && known?.stamp != stamp) {
                val signature = SearchSignature.fromBody(source.content)
                if (signatureStamp(file) == stamp) synchronized(verifiedSignatures) {
                    verifiedSignatures[file] = VerifiedSignature(stamp, signature)
                }
            }
            return source
        }
    }

    suspend fun readNote(id: NoteId): Note = read(id.vaultId) { _, store ->
        parser.note(store.readNote(id, requireUtf8 = true))
    }

    internal suspend fun readSearchSource(id: NoteId, expectedRoot: String, requireUtf8: Boolean = false): StoredMarkdown = read(id.vaultId) { vault, store ->
        if (vault.rootPath != expectedRoot) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        store.readNote(id, requireUtf8 = requireUtf8)
    }

    suspend fun imageContext(id: NoteId): ImageContext? = read(id.vaultId) { _, store -> store.imageContext(id) }

    internal suspend fun editorDocument(id: NoteId, recover: Boolean = false): StoredMarkdown {
        return if (recover) write(id.vaultId) { _, store -> store.readNote(id, requireUtf8 = true) }
        else read(id.vaultId) { _, store -> store.readNote(id, requireUtf8 = true) }
    }

    suspend fun openNote(id: NoteId): Note = write(id.vaultId) { _, store ->
        parser.note(store.readNote(id, requireUtf8 = true))
    }

    suspend fun reloadNote(id: NoteId): Note = write(id.vaultId) { _, store ->
        parser.note(store.readNote(id, requireUtf8 = true))
    }

    suspend fun wikiContext(id: NoteId): WikiContext = read(id.vaultId) { _, _ ->
        withContext(Dispatchers.Default) { index.wikiContext(id) }
    }

    suspend fun createNote(
        title: String,
        folder: FolderPath = FolderPath.Root,
    ): Note = write { vault, fileStore ->
        record(vault, fileStore.createNote(title, folder))
    }

    suspend fun saveNote(
        id: NoteId,
        content: String,
        mode: NoteWriteMode,
    ): Note = write(id.vaultId) { vault, fileStore ->
        record(vault, fileStore.saveNote(id, content, mode))
    }

    internal suspend fun saveConflictCopy(
        id: NoteId,
        content: String,
        previous: ConflictCopy?,
    ): ConflictCopy = write(id.vaultId) { vault, fileStore ->
        val stored = fileStore.writeConflictCopy(id, content, previous)
        val copy = ConflictCopy(stored.id, stored.revision)
        if (copy != previous && stored.file.length() <= MAX_MARKDOWN_BYTES) record(vault, stored)
        copy
    }

    internal suspend fun discardConflictCopy(id: NoteId, copy: ConflictCopy) = write(id.vaultId) { vault, store ->
        if (store.discardConflictCopy(id, copy)) {
            indexUpdater.apply(vault, IndexDelta(id.vaultId, removed = listOf(copy.id.path)))
        }
    }

    suspend fun renameNote(id: NoteId, newFilename: String): RenameOutcome =
        relocate(id) { it.renameNote(id, newFilename) }

    suspend fun moveNote(id: NoteId, target: FolderPath): RenameOutcome =
        relocate(id) { it.moveNote(id, target) }

    internal suspend fun relocateNotes(ids: List<NoteId>, target: FolderPath): Map<NoteId, NotePath> =
        write(ids.first().vaultId) { vault, _ -> runRelocation(vault) { it.moveNotes(ids, target) }.path }

    internal suspend fun restoreLocations(originals: Map<NoteId, NotePath>): Int =
        write(originals.keys.first().vaultId) { vault, _ -> runRelocation(vault) { it.restoreLocations(originals) }.path }

    internal suspend fun moveNoteToOriginalPath(id: NoteId, originalPath: NotePath): Unit = write(id.vaultId) { vault, _ ->
        runRelocation(vault) { it.moveNoteToOriginalPath(id, originalPath) }
    }

    suspend fun deleteNote(id: NoteId): TrashedNote = write(id.vaultId) { vault, store ->
        val trashed = store.deleteNote(id)
        indexUpdater.apply(vault, IndexDelta(id.vaultId, removed = listOf(id.path)))
        trashed
    }

    suspend fun discardNote(id: NoteId, expectedRevision: NoteRevision) = write(id.vaultId) { vault, store ->
        val current = store.readNote(id)
        if (current.revision != expectedRevision) throw NoteChangedExternallyException(current.revision)
        store.discardNote(id)
        indexUpdater.apply(vault, IndexDelta(id.vaultId, removed = listOf(id.path)))
    }

    suspend fun listTrashedNotes(): List<TrashedNote> = read { _, store -> store.listTrashedNotes() }

    suspend fun restoreNote(trashId: String): NoteId = write { vault, store ->
        val id = store.restoreNote(trashId)
        reindex(vault, RelocationChanges(changedNotes = listOf(id.path)))
        id
    }

    suspend fun deleteTrashedNote(trashId: String) = write { _, store -> store.deleteTrashedNote(trashId) }

    suspend fun restoreTrashedNotes(trashIds: List<String>, vaultId: VaultId? = null): TrashBulkOutcome =
        write(vaultId) { vault, store ->
            val (restored, outcome) = store.restoreNotes(trashIds)
            reindex(vault, RelocationChanges(changedNotes = restored.map { it.path }))
            outcome
        }

    suspend fun deleteTrashedNotes(trashIds: List<String>): TrashBulkOutcome =
        write { _, store -> store.deleteTrashedNotes(trashIds) }

    suspend fun properties(id: NoteId): NoteProperties = read(id.vaultId) { _, fileStore ->
        val stored = fileStore.readNote(id)
        val metadata = parser.savedNote(stored).metadata
        val stats = fileStore.noteStats(id)
        NoteProperties(
            path = id.path,
            sizeBytes = stats.sizeBytes,
            modifiedAt = stats.modifiedAt,
            words = NoteTextStats.words(stored.content),
            characters = NoteTextStats.characters(stored.content),
            checkboxesDone = metadata.checklist.count { it.checked },
            checkboxesTotal = metadata.checklist.size,
        )
    }

    suspend fun createFolder(parent: FolderPath, name: String): VaultFolder = write { vault, _ ->
        val ops = VaultFileOps(File(vault.rootPath))
        val directory = ops.createFolder(parent, name)
        folder(vault, FolderPath(directory.relativeTo(ops.rootDir).invariantSeparatorsPath))
    }

    suspend fun renameFolder(path: FolderPath, newName: String): VaultFolder = write { vault, _ ->
        folder(vault, runRelocation(vault) { it.renameFolder(path, newName) }.path)
    }

    suspend fun moveFolder(path: FolderPath, destination: FolderPath): VaultFolder = write { vault, _ ->
        folder(vault, runRelocation(vault) { it.moveFolder(path, destination) }.path)
    }

    internal suspend fun folderNotePaths(path: FolderPath, vaultId: VaultId) = read(vaultId) { vault, _ ->
        VaultFileOps(File(vault.rootPath)).folderNotePaths(path)
    }

    internal suspend fun deleteFolderWithOutcome(path: FolderPath, vaultId: VaultId, expected: List<NotePath>): FolderDeleteOutcome =
        write(vaultId) { vault, _ ->
            val result = VaultFileOps(File(vault.rootPath)).deleteFolderWithOutcome(path, expected)
            reindex(vault, RelocationChanges(removedNotes = result.records.map { it.originalPath }))
            FolderDeleteOutcome(result.records.map { it.trashId }, result.failed, result.directoryRemoved)
        }

    internal suspend fun deleteEmptyFolder(path: FolderPath, vaultId: VaultId): Boolean = write(vaultId) { vault, _ ->
        VaultFileOps(File(vault.rootPath)).deleteEmptyFolder(path)
    }

    suspend fun abandonStalledRelocation(journalId: String) {
        val vault = currentVault()
        VaultWriteCoordinator.write(vault) {
            VaultRelocation(vault).abandon(journalId)
            stalledRelocations.remove(vault.id)
        }
    }

    internal suspend fun prepareForSync(vault: Vault): StalledRelocation? =
        VaultWriteCoordinator.write(vault) {
            recoverPendingRelocations(vault, retryStalled = true)
        }

    internal suspend fun applyExternalNoteChanges(
        vault: Vault,
        changedNotes: List<NotePath>,
        removedNotes: List<NotePath>,
    ) {
        if (changedNotes.isEmpty() && removedNotes.isEmpty()) return
        VaultWriteCoordinator.write(vault) {
            reindex(vault, RelocationChanges(changedNotes = changedNotes, removedNotes = removedNotes))
        }
    }

    private suspend fun relocate(id: NoteId, operation: (VaultRelocation) -> RelocationResult<NotePath>): RenameOutcome =
        write(id.vaultId) { vault, store ->
            val result = runRelocation(vault, operation)
            val stored = store.readNote(NoteId(vault.id, result.path))
            RenameOutcome(parser.indexSavedNote(stored).toNote(), linksUpdated = result.linksUpdated, linksFailed = result.linksFailed)
        }

    private suspend fun <P> runRelocation(
        vault: Vault,
        operation: (VaultRelocation) -> RelocationResult<P>,
    ): RelocationResult<P> {
        recoverPendingRelocations(vault, retryStalled = true)?.let { throw RelocationStalledException(it) }
        val result = try {
            operation(VaultRelocation(vault))
        } catch (error: RelocationStalledException) {
            stalledRelocations[vault.id] = error.stalled
            throw error
        }
        stalledRelocations.remove(vault.id)
        reindex(vault, result.changes)
        return result
    }

    private suspend fun <T> read(
        expected: VaultId? = null,
        block: suspend (Vault, NoteFileStore) -> T,
    ): T {
        val vault = currentVault(expected)
        return withContext(Dispatchers.IO) { block(vault, NoteFileStore(vault)) }
    }

    private suspend fun <T> write(
        expected: VaultId? = null,
        block: suspend (Vault, NoteFileStore) -> T,
    ): T {
        val vault = currentVault(expected)
        return VaultWriteCoordinator.write(vault) {
            recoverPendingRelocations(vault, retryStalled = false)
            block(vault, NoteFileStore(vault))
        }
    }

    private suspend fun currentVault(expected: VaultId? = null): Vault {
        val vault = vaultRepository.requireCurrentVault()
        if (expected != null && vault.id != expected) throw VaultFileException(VaultFileFailure.NOT_FOUND)
        return vault
    }

    private suspend fun recoverPendingRelocations(vault: Vault, retryStalled: Boolean): StalledRelocation? {
        if (!retryStalled) stalledRelocations[vault.id]?.let { return it }
        val report = VaultRelocation(vault).recoverPending()
        reindex(vault, report.changes)
        val stalled = report.stalled
        if (stalled == null) {
            stalledRelocations.remove(vault.id)
        } else {
            stalledRelocations[vault.id] = stalled
        }
        return stalled
    }

    private suspend fun reindex(vault: Vault, changes: RelocationChanges) {
        if (changes.removedNotes.isEmpty() && changes.changedNotes.isEmpty()) return
        val ops = VaultFileOps(File(vault.rootPath))
        val changed = changes.changedNotes.distinct()
        val upserts = ArrayList<IndexedNote>(changed.size)
        val signatures = LinkedHashMap<NotePath, ByteArray>(changed.size)
        for (path in changed) {
            val parsed = try {
                val file = ops.noteFile(path)
                val before = file.fingerprint()
                val text = readMarkdown(file)
                if (before != file.fingerprint()) continue
                val metadata = parser.parseForIndex(text, file.nameWithoutExtension)
                indexedNote(vault.id, ops.rootDir, file, text, metadata, before) to SearchSignature.fromBody(text)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (parsed != null) {
                upserts += parsed.first
                signatures[path] = parsed.second
            }
        }
        val removed = changes.removedNotes.distinct().filterNot { it in changed }
        indexUpdater.apply(vault, IndexDelta(vault.id, upserts = upserts, removed = removed, signatures = signatures))
    }

    private fun folder(vault: Vault, path: FolderPath) = VaultFolder(
        vaultId = vault.id,
        path = path,
        name = path.relativePath.substringAfterLast('/'),
    )

    private fun IndexedNote.toListEntry(): NoteListEntry = NoteListEntry(
        summary = NoteSummary(
            id = id,
            title = title,
            modifiedAt = modifiedAt,
            tags = tags.toSet(),
        ),
        coverPath = coverPath,
        markdownSnippet = snippet,
    )

    private suspend fun record(vault: Vault, stored: StoredMarkdown): Note {
        val saved = parser.indexSavedNote(stored)
        val signatures = if (stored.fingerprint != null) {
            mapOf(saved.id.path to SearchSignature.fromBody(saved.content))
        } else {
            emptyMap()
        }
        indexUpdater.apply(
            vault,
            IndexDelta(
                vaultId = saved.id.vaultId,
                upserts = listOf(saved.toIndexedNote()),
                signatures = signatures,
            ),
        )
        return saved.toNote()
    }

    private companion object {
        const val SEARCH_IO_PARALLELISM = 4
    }
}

internal data class FolderDeleteOutcome(
    val trashIds: List<String>,
    val failed: Int,
    val directoryRemoved: Boolean,
)
