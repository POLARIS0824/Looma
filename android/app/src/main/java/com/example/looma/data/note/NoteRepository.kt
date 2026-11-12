package com.example.looma.data.note

import com.example.looma.data.index.IndexDelta
import com.example.looma.data.index.IndexedNote
import com.example.looma.data.index.NoteIndex
import com.example.looma.data.index.NoteIndexUpdater
import com.example.looma.data.index.indexedNote
import com.example.looma.data.index.toIndexedNote
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.markdown.ImageContext
import com.example.looma.data.vault.VaultWriteCoordinator
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.NoteWriteMode
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.model.VaultFolder
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteProperties
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.NoteTextStats
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.domain.model.TrashedNote
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.domain.wiki.WikiGraph
import com.example.looma.domain.wiki.WikiNote
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class NoteRepository internal constructor(
    private val vaultRepository: VaultRepository,
    private val index: NoteIndex,
) {
    private val parser = MarkdownParser()
    private val textCache = NoteTextCache()
    private val indexUpdater = NoteIndexUpdater(index)
    private val stalledRelocations = ConcurrentHashMap<VaultId, StalledRelocation>()

    internal suspend fun listNotes(): NoteListResult {
        val current = currentVault()
        val stalled = VaultWriteCoordinator.write(current) {
            recoverPendingRelocations(current, retryStalled = true)
        }
        return read(current.id) { vault, _ ->
            val scan = indexUpdater.scan(vault)
            NoteListResult(
                items = scan.notes
                    .map { it.toListEntry() }
                    .sortedByDescending { it.summary.modifiedAt },
                folders = scan.folders,
                unreadableCount = scan.unreadableCount,
                stalledRelocation = stalled,
            )
        }
    }

    suspend fun readTexts(ids: List<NoteId>): Map<NoteId, String> = read { vault, fileStore ->
        val texts = mutableMapOf<NoteId, String>()
        for (id in ids.filter { it.vaultId == vault.id }) {
            coroutineContext.ensureActive()
            val modifiedAt = runCatching { fileStore.modifiedAt(id) }.getOrNull() ?: continue
            val cached = textCache.get(id, modifiedAt)
            if (cached != null) {
                texts[id] = cached
                continue
            }
            runCatching { fileStore.readText(id) }.onSuccess { (text, readAt) ->
                textCache.put(id, readAt, text)
                texts[id] = text
            }
        }
        texts
    }

    suspend fun readNote(id: NoteId): Note = read(id.vaultId) { _, store ->
        parser.note(store.readNote(id))
    }

    suspend fun imageContext(id: NoteId): ImageContext? = read(id.vaultId) { _, store -> store.imageContext(id) }

    suspend fun openNote(id: NoteId): Note = write(id.vaultId) { _, store ->
        parser.note(store.readNote(id))
    }

    suspend fun reloadNote(id: NoteId): Note = write(id.vaultId) { _, store ->
        parser.note(store.readNote(id))
    }

    suspend fun wikiContext(id: NoteId): WikiContext = read(id.vaultId) { _, _ ->
        withContext(Dispatchers.Default) { wikiGraphOf(index.load(id.vaultId)).context(id) }
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

    suspend fun saveConflictCopy(
        id: NoteId,
        content: String,
        existing: NotePath?,
    ): Note = write(id.vaultId) { vault, fileStore ->
        record(vault, fileStore.writeConflictCopy(id, content, existing))
    }

    suspend fun renameNote(id: NoteId, newFilename: String): RenameOutcome =
        relocate(id) { it.renameNote(id, newFilename) }

    suspend fun moveNote(id: NoteId, target: FolderPath): RenameOutcome =
        relocate(id) { it.moveNote(id, target) }

    suspend fun deleteNote(id: NoteId): TrashedNote = write(id.vaultId) { vault, store ->
        val trashed = store.deleteNote(id)
        textCache.remove(id)
        indexUpdater.apply(vault, IndexDelta(id.vaultId, removed = listOf(id.path)))
        trashed
    }

    suspend fun discardNote(id: NoteId, expectedRevision: NoteRevision) = write(id.vaultId) { vault, store ->
        val current = store.readNote(id)
        if (current.revision != expectedRevision) throw NoteChangedExternallyException(current.revision)
        store.discardNote(id)
        textCache.remove(id)
        indexUpdater.apply(vault, IndexDelta(id.vaultId, removed = listOf(id.path)))
    }

    suspend fun listTrashedNotes(): List<TrashedNote> = read { _, store -> store.listTrashedNotes() }

    suspend fun restoreNote(trashId: String): Note = write { vault, store ->
        record(vault, store.restoreNote(trashId))
    }

    suspend fun deleteTrashedNote(trashId: String) = write { _, store -> store.deleteTrashedNote(trashId) }

    suspend fun restoreTrashedNotes(trashIds: List<String>): TrashBulkOutcome = write { vault, store ->
        val (restored, outcome) = store.restoreNotes(trashIds)
        for (stored in restored) {
            record(vault, stored)
        }
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

    suspend fun deleteFolder(path: FolderPath): List<String> = write { vault, _ ->
        val records = VaultFileOps(File(vault.rootPath)).deleteFolder(path)
        reindex(vault, RelocationChanges(removedNotes = records.map { it.originalPath }))
        records.map { it.trashId }
    }

    suspend fun abandonStalledRelocation(journalId: String) {
        val vault = currentVault()
        VaultWriteCoordinator.write(vault) {
            VaultRelocation(vault).abandon(journalId)
            stalledRelocations.remove(vault.id)
        }
    }

    private suspend fun relocate(id: NoteId, operation: (VaultRelocation) -> RelocationResult<NotePath>): RenameOutcome =
        write(id.vaultId) { vault, store ->
            val result = runRelocation(vault, operation)
            val stored = store.readNote(NoteId(vault.id, result.path))
            RenameOutcome(parser.savedNote(stored).toNote(), linksUpdated = result.linksUpdated, linksFailed = result.linksFailed)
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
        val root = VaultFileOps(File(vault.rootPath)).rootDir
        val changed = changes.changedNotes.distinct()
        (changes.removedNotes + changed).forEach { textCache.remove(NoteId(vault.id, it)) }
        val upserts = changed.mapNotNull { path ->
            val file = File(root, path.relativePath)
            try {
                val text = readMarkdown(file)
                indexedNote(vault.id, root, file, text, parser.parse(text, file.nameWithoutExtension))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        val removed = changes.removedNotes.distinct().filterNot { it in changed }
        indexUpdater.apply(vault, IndexDelta(vault.id, upserts = upserts, removed = removed))
    }

    private fun folder(vault: Vault, path: FolderPath) = VaultFolder(
        vaultId = vault.id,
        path = path,
        name = path.relativePath.substringAfterLast('/'),
    )

    private fun wikiGraphOf(indexed: List<IndexedNote>): WikiGraph = WikiGraph(
        notes = indexed.map { note -> WikiNote(note.id, note.title) },
        linksByNote = indexed.associate { note -> note.id to note.links.map { link -> link.target } },
    )

    private fun IndexedNote.toListEntry(): NoteListEntry = NoteListEntry(
        summary = NoteSummary(
            id = id,
            title = title,
            excerpt = excerpt,
            modifiedAt = modifiedAt,
            tags = tags.toSet(),
        ),
        coverPath = coverPath,
        markdownSnippet = snippet,
        searchText = snippet,
    )

    private suspend fun record(vault: Vault, stored: StoredMarkdown): Note {
        val saved = parser.savedNote(stored)
        textCache.put(saved.id, saved.modifiedAt, saved.content)
        indexUpdater.apply(
            vault,
            IndexDelta(saved.id.vaultId, upserts = listOf(saved.toIndexedNote())),
        )
        return saved.toNote()
    }
}

