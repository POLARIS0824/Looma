package com.example.looma.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.folder.FolderRepository
import com.example.looma.data.note.NoteListEntry
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.note.RelocationStalledException
import com.example.looma.data.note.VaultFileException
import com.example.looma.data.note.VaultFileFailure
import com.example.looma.data.search.SearchHistoryRepository
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.StalledRelocation
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class HomeViewModel(
    private val vaultRepository: VaultRepository,
    private val noteRepository: NoteRepository,
    private val folderRepository: FolderRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var searchSnapshot: Deferred<SearchSnapshot>? = null
    private var revealGeneration = 0L
    private var index: HomeVaultIndex = HomeVaultIndex.Empty

    fun refresh(): Job {
        refreshJob?.cancel()
        clearSearch()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val vault = vaultRepository.requireCurrentVault()
                val result = noteRepository.listNotes()
                val notes = result.items.map { it.toHomeItem() }
                index = HomeVaultIndex.build(result.folders, notes)
                _uiState.update { current ->
                    val path = current.files.path
                        .takeIf(index::contains)
                        ?: FolderPath.Root
                    val alive = notes.mapTo(mutableSetOf()) { it.listKey }
                    val selection = current.selection.normalized(alive)
                    current.copy(
                        vaultId = vault.id,
                        vaultName = vault.name,
                        vaultRootPath = vault.rootPath,
                        notes = notes.orderedBy(current.sort),
                        searchResults = emptyList(),
                        files = index.files(path, current.sort),
                        recentReveal = current.recentReveal?.takeIf { request ->
                            request.presentIn(index.recents())
                        },
                        selection = selection,
                        selectedNotes = notes.selected(selection, current.sort),
                        stalledRelocation = result.stalledRelocation,
                        isLoading = false,
                        isRefreshing = false,
                        error = if (result.unreadableCount > 0) {
                            HomeError.UnreadableNotes(result.unreadableCount)
                        } else {
                            null
                        },
                    )
                }
                scheduleSearch(_uiState.value.query, debounce = false)
                loadRecentSearches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                index = HomeVaultIndex.Empty
                _uiState.update {
                    it.copy(
                        vaultId = null,
                        notes = emptyList(),
                        searchResults = emptyList(),
                        isSearching = false,
                        files = HomeFilesState(),
                        selection = HomeSelection.None,
                        selectedNotes = emptyList(),
                        isLoading = false,
                        isRefreshing = false,
                        error = HomeError.LoadFailed,
                    )
                }
            }
        }
        return refreshJob!!
    }

    fun startAbandonStalledRelocation() {
        val stalled = _uiState.value.stalledRelocation ?: return
        _uiState.update {
            it.copy(dialog = HomeFileDialog.AbandonStalledRelocation(stalled), dialogError = null)
        }
    }

    fun confirmAbandonStalledRelocation() {
        val dialog = _uiState.value.dialog as? HomeFileDialog.AbandonStalledRelocation ?: return
        runDialogAction {
            noteRepository.abandonStalledRelocation(dialog.stalled.journalId)
            HomeMessage(HomeNotice.StalledRelocationAbandoned)
        }
    }

    fun refreshFromUser() {
        if (_uiState.value.isCreating || _uiState.value.isActionRunning) return
        _uiState.update { it.copy(isRefreshing = true) }
        refresh()
    }

    fun openFolder(path: FolderPath) {
        if (!index.contains(path)) return
        _uiState.update {
            it.copy(
                files = index.files(path, it.sort),
                selection = HomeSelection.None,
                selectedNotes = emptyList(),
            )
        }
    }

    fun navigateUp() {
        val current = _uiState.value
        if (current.files.isAtRoot) return
        openFolder(current.files.path.parentFolder())
    }

    fun setQuery(value: String) {
        if (_uiState.value.query == value) return
        _uiState.update { it.copy(query = value, searchResults = emptyList(), isSearching = value.isNotBlank()) }
        if (value.isBlank()) {
            clearSearch()
        } else if (!_uiState.value.isLoading) {
            scheduleSearch(value)
        }
    }

    private fun clearSearch() {
        searchJob?.cancel()
        searchSnapshot?.cancel()
        searchSnapshot = null
    }

    private fun scheduleSearch(value: String, debounce: Boolean = true) {
        searchJob?.cancel()
        val keyword = value.trim()
        if (keyword.isEmpty()) return
        _uiState.update { it.copy(isSearching = true) }
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val notes = _uiState.value.notes
            val texts = searchTexts(notes)
            val sort = _uiState.value.sort
            val results = withContext(Dispatchers.Default) {
                notes.filter { note ->
                    coroutineContext.ensureActive()
                    note.matchesSearch(keyword, texts[note.id] ?: note.searchableText)
                }.orderedBy(sort)
            }
            _uiState.update { current ->
                if (current.query != value) {
                    current
                } else {
                    current.copy(
                        searchResults = if (sort == current.sort) results else results.orderedBy(current.sort),
                        isSearching = false,
                    )
                }
            }
        }
    }

    private suspend fun searchTexts(notes: List<HomeNoteItem>): Map<NoteId, String> {
        searchSnapshot?.await()?.let { snapshot ->
            if (snapshot.loadedAt.elapsedNow() < 2.seconds) return snapshot.texts
        }
        val snapshot = viewModelScope.async {
            val texts = loadSearchTexts(notes)
            SearchSnapshot(texts, TimeSource.Monotonic.markNow())
        }
        searchSnapshot = snapshot
        return snapshot.await().texts
    }

    private suspend fun loadSearchTexts(notes: List<HomeNoteItem>): Map<NoteId, String> {
        if (notes.isEmpty()) return emptyMap()
        return try {
            noteRepository.readTexts(notes.map { it.id })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private data class SearchSnapshot(val texts: Map<NoteId, String>, val loadedAt: TimeMark)

    fun recordSearch() {
        val query = _uiState.value.query
        if (query.isBlank()) return
        viewModelScope.launch {
            try {
                searchHistoryRepository.record(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            loadRecentSearches()
        }
    }

    fun toggleLayout() = _uiState.update { it.copy(isGridMode = !it.isGridMode) }

    fun setSort(sort: HomeSort) = _uiState.update {
        it.copy(
            sort = sort,
            notes = it.notes.orderedBy(sort),
            searchResults = it.searchResults.orderedBy(sort),
            files = index.files(it.files.path, sort),
            selectedNotes = it.selectedNotes.orderedBy(sort),
        )
    }

    fun createNote(title: String) {
        if (_uiState.value.isCreating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, error = null) }
            try {
                val folder = _uiState.value.files.path
                val note = noteRepository.createNote(title = title, folder = folder)
                val request = RecentReveal(++revealGeneration, note.id)
                _uiState.update { it.copy(createdNote = note.id, recentReveal = request) }
                refresh().join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = HomeError.CreateFailed) }
            } finally {
                _uiState.update { it.copy(isCreating = false) }
            }
        }
    }

    fun onCreatedNoteOpened() = _uiState.update { it.copy(createdNote = null) }

    fun onNoteOpened() = _uiState.update { it.copy(noteToOpen = null) }

    fun onRecentRevealed(request: RecentReveal) = _uiState.update { it.acknowledgeReveal(request) }

    fun onSelectionEvent(event: HomeSelectionEvent) {
        val current = _uiState.value
        if (current.isActionRunning) return
        when (val outcome = reduceHomeSelection(current.selection, event)) {
            is HomeSelectionOutcome.Changed -> _uiState.update {
                it.copy(
                    selection = outcome.selection,
                    selectedNotes = it.notes.selected(outcome.selection, it.sort),
                    dialog = null,
                    dialogError = null,
                )
            }
            is HomeSelectionOutcome.OpenFolder -> openFolder(outcome.path)
            is HomeSelectionOutcome.OpenNote -> _uiState.update { it.copy(noteToOpen = outcome.id) }
        }
    }

    fun clearSelection() = _uiState.update {
        it.copy(
            selection = HomeSelection.None,
            selectedNotes = emptyList(),
            dialog = null,
            dialogError = null,
        )
    }

    fun startRename() {
        val item = _uiState.value.singleNote() ?: return
        _uiState.update {
            it.copy(dialog = HomeFileDialog.Rename(item.fileName), dialogError = null)
        }
    }

    fun startProperties() {
        val item = _uiState.value.singleNote() ?: return
        viewModelScope.launch {
            try {
                val properties = noteRepository.properties(item.id)
                _uiState.update {
                    it.copy(dialog = HomeFileDialog.Properties(properties), dialogError = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(message = HomeMessage(HomeNotice.ActionFailed))
                }
            }
        }
    }

    fun startNewFolder() = _uiState.update {
        it.copy(dialog = HomeFileDialog.NewFolder, dialogError = null)
    }

    fun dismissDialog() = _uiState.update { it.copy(dialog = null, dialogError = null) }

    fun confirmRename(name: String) {
        val item = _uiState.value.singleNote() ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(dialogError = HomeFileError.EMPTY_NAME) }
            return
        }
        val previousName = item.fileName
        runDialogAction {
            val outcome = noteRepository.renameNote(item.id, trimmed)
            HomeMessage(
                notice = HomeNotice.Renamed(outcome.linksUpdated, outcome.linksFailed),
                undo = HomeUndo.RenameNote(outcome.note.id, previousName),
            )
        }
    }

    fun confirmNewFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(dialogError = HomeFileError.EMPTY_NAME) }
            return
        }
        runDialogAction {
            val folder = folderRepository.createFolder(_uiState.value.files.path, trimmed)
            HomeMessage(
                notice = HomeNotice.FolderCreated,
                undo = HomeUndo.DeleteFolder(folder.path, folder.vaultId),
            )
        }
    }

    fun startFolderRename() {
        val folder = _uiState.value.selectedFolder ?: return
        _uiState.update {
            it.copy(
                dialog = HomeFileDialog.RenameFolder(folder.path, folder.name),
                dialogError = null,
            )
        }
    }

    fun startFolderDelete() {
        val folder = _uiState.value.selectedFolder ?: return
        _uiState.update {
            it.copy(
                dialog = HomeFileDialog.DeleteFolder(
                    path = folder.path,
                    folderName = folder.name,
                    noteCount = index.noteCountUnder(folder.path),
                ),
                dialogError = null,
            )
        }
    }

    fun confirmRenameFolder(name: String) {
        val dialog = _uiState.value.dialog as? HomeFileDialog.RenameFolder ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(dialogError = HomeFileError.EMPTY_NAME) }
            return
        }
        val previousName = dialog.path.relativePath.substringAfterLast('/')
        runDialogAction {
            val renamed = folderRepository.renameFolder(dialog.path, trimmed)
            HomeMessage(
                notice = HomeNotice.FolderRenamed,
                undo = HomeUndo.RenameFolder(renamed.path, previousName),
            )
        }
    }

    fun confirmDeleteFolder() {
        val dialog = _uiState.value.dialog as? HomeFileDialog.DeleteFolder ?: return
        val vaultId = _uiState.value.vaultId ?: return
        runDialogAction {
            val result = folderRepository.deleteFolderWithOutcome(dialog.path, vaultId)
            HomeMessage(
                notice = if (result.directoryRemoved) {
                    HomeNotice.FolderDeleted(result.trashIds.size)
                } else {
                    HomeNotice.FolderKept(result.trashIds.size, result.failed)
                },
                undo = result.trashIds.takeIf { it.isNotEmpty() }?.let { HomeUndo.RestoreNotes(it, vaultId) },
            )
        }
    }

    fun moveSelectionTo(target: FolderPath?) {
        if (target == null) {
            _uiState.update { it.copy(message = HomeMessage(HomeNotice.InvalidMoveTarget)) }
            return
        }
        val state = _uiState.value
        if (state.isActionRunning) return
        when (val selection = state.selection) {
            HomeSelection.None -> Unit
            is HomeSelection.Folder -> moveFolderTo(selection.item, target)
            is HomeSelection.Notes -> moveNotesTo(state.selectedNotes, target)
        }
    }

    private fun moveNotesTo(notes: List<HomeNoteItem>, target: FolderPath) {
        if (notes.isEmpty()) return
        runNoteBatch {
            val result = processNotes(notes) { note ->
                MovedNote(noteRepository.relocateNote(note.id, target), note.id.path)
            }
            val count = result.completed.size
            val notice = when {
                count == 0 -> (result.failure as? VaultFileException)?.toMessage()?.notice ?: HomeNotice.ActionFailed
                result.failed > 0 -> HomeNotice.MovePartial(count, result.failed)
                else -> HomeNotice.Moved(count)
            }
            val moved = result.completed.filter { it.id.path != it.originalPath }
            val undo = moved.takeIf { it.isNotEmpty() }?.let { HomeUndo.MoveNotes(it) }
            finishNoteBatch(result, HomeMessage(notice, undo))
        }
    }

    private fun moveFolderTo(folder: HomeFolderItem, target: FolderPath) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionRunning = true) }
            try {
                val moved = folderRepository.moveFolder(folder.path, target)
                finishAction(
                    HomeMessage(
                        notice = HomeNotice.FolderMoved,
                        undo = HomeUndo.MoveFolder(moved.path, folder.path.parentFolder()),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelocationStalledException) {
                reportStalledRelocation(e.stalled)
            } catch (e: VaultFileException) {
                _uiState.update { it.copy(isActionRunning = false, message = e.toMessage()) }
                refresh()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isActionRunning = false, message = HomeMessage(HomeNotice.ActionFailed))
                }
                refresh()
            }
        }
    }

    fun deleteSelected() {
        val state = _uiState.value
        val vaultId = state.vaultId ?: return
        if (state.selectedNotes.isEmpty()) return
        runNoteBatch {
            val result = processNotes(state.selectedNotes) { note -> noteRepository.deleteNote(note.id).trashId }
            val count = result.completed.size
            val notice = when {
                count == 0 -> HomeNotice.ActionFailed
                result.failed > 0 -> HomeNotice.DeletePartial(count, result.failed)
                else -> HomeNotice.Deleted(count)
            }
            val undo = result.completed.takeIf { it.isNotEmpty() }?.let { HomeUndo.RestoreNotes(it, vaultId) }
            finishNoteBatch(result, HomeMessage(notice, undo))
        }
    }

    fun undo(action: HomeUndo) {
        runNoteBatch {
            try {
                finishAction(HomeMessage(applyUndo(action)))
            } catch (e: RelocationStalledException) {
                reportStalledRelocation(e.stalled)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                finishAction(HomeMessage(HomeNotice.UndoFailed))
            }
        }
    }

    private fun runNoteBatch(action: suspend () -> Unit) {
        if (_uiState.value.isActionRunning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionRunning = true, dialogError = null) }
            try {
                action()
            } finally {
                _uiState.update { it.copy(isActionRunning = false) }
            }
        }
    }

    private suspend fun applyUndo(action: HomeUndo): HomeNotice = when (action) {
        is HomeUndo.RestoreNotes -> {
            val result = noteRepository.restoreTrashedNotes(action.trashIds, action.vaultId)
            undoNotice(result.succeeded, result.failed)
        }

        is HomeUndo.RenameNote -> {
            noteRepository.renameNote(action.id, action.name)
            HomeNotice.Undone
        }

        is HomeUndo.MoveNotes -> {
            val result = processNotes(action.moves) { move ->
                noteRepository.moveNoteToOriginalPath(move.id, move.originalPath)
            }
            val stalled = result.failure as? RelocationStalledException
            if (stalled != null && result.completed.isEmpty()) throw stalled
            if (stalled != null) _uiState.update { it.copy(stalledRelocation = stalled.stalled) }
            undoNotice(result.completed.size, result.failed)
        }

        is HomeUndo.RenameFolder -> {
            folderRepository.renameFolder(action.path, action.name)
            HomeNotice.Undone
        }

        is HomeUndo.MoveFolder -> {
            folderRepository.moveFolder(action.path, action.parent)
            HomeNotice.Undone
        }

        is HomeUndo.DeleteFolder -> {
            if (folderRepository.deleteEmptyFolder(action.path, action.vaultId)) {
                HomeNotice.Undone
            } else {
                HomeNotice.UndoFailed
            }
        }
    }

    private fun undoNotice(succeeded: Int, failed: Int): HomeNotice = when {
        succeeded == 0 -> HomeNotice.UndoFailed
        failed > 0 -> HomeNotice.UndoPartial(succeeded, failed)
        else -> HomeNotice.Undone
    }

    private suspend fun <T> finishNoteBatch(result: NoteBatchResult<T>, message: HomeMessage) {
        val stalled = result.failure as? RelocationStalledException
        if (stalled != null && result.completed.isEmpty()) {
            reportStalledRelocation(stalled.stalled)
            return
        }
        if (stalled != null) _uiState.update { it.copy(stalledRelocation = stalled.stalled) }
        finishAction(message)
    }

    private suspend fun <I, T> processNotes(items: List<I>, operation: suspend (I) -> T): NoteBatchResult<T> {
        val completed = mutableListOf<T>()
        var failure: Exception? = null
        for (item in items) {
            try {
                completed += operation(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelocationStalledException) {
                failure = e
                break
            } catch (e: IOException) {
                if (failure == null) failure = e
            } catch (e: Exception) {
                failure = e
                break
            }
        }
        return NoteBatchResult(completed, items.size - completed.size, failure)
    }

    private data class NoteBatchResult<T>(val completed: List<T>, val failed: Int, val failure: Exception?)

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    private suspend fun loadRecentSearches() {
        val recent = try {
            searchHistoryRepository.recent()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        _uiState.update { it.copy(recentSearches = recent) }
    }

    private fun runDialogAction(message: suspend () -> HomeMessage) {
        if (_uiState.value.isActionRunning) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionRunning = true, dialogError = null) }
            try {
                finishAction(message())
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelocationStalledException) {
                reportStalledRelocation(e.stalled)
            } catch (e: VaultFileException) {
                _uiState.update {
                    it.copy(isActionRunning = false, dialogError = e.failure.toHomeFileError())
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isActionRunning = false, dialogError = HomeFileError.GENERIC)
                }
            }
        }
    }

    private suspend fun reportStalledRelocation(stalled: StalledRelocation) {
        _uiState.update {
            it.copy(dialog = null, dialogError = null, stalledRelocation = stalled)
        }
        refresh().join()
        _uiState.update {
            val notice = if (it.stalledRelocation == null) {
                HomeNotice.StalledRelocationCompleted
            } else {
                HomeNotice.StalledRelocationUnfinished
            }
            it.copy(isActionRunning = false, message = HomeMessage(notice))
        }
    }

    private suspend fun finishAction(message: HomeMessage) {
        refresh()
        _uiState.update {
            it.copy(
                isActionRunning = false,
                dialog = null,
                dialogError = null,
                selection = HomeSelection.None,
                selectedNotes = emptyList(),
                message = message,
            )
        }
    }

    private fun HomeSelection.normalized(aliveNotes: Set<String>): HomeSelection = when (this) {
        HomeSelection.None -> this
        is HomeSelection.Notes -> HomeSelection.Notes.of(keys.filterTo(mutableSetOf()) { it in aliveNotes })
        is HomeSelection.Folder -> if (index.contains(item.path)) this else HomeSelection.None
    }

    private fun HomeUiState.singleNote(): HomeNoteItem? = selectedNotes.singleOrNull()

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 180L

        fun factory(
            vaultRepository: VaultRepository,
            noteRepository: NoteRepository,
            folderRepository: FolderRepository,
            searchHistoryRepository: SearchHistoryRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    vaultRepository,
                    noteRepository,
                    folderRepository,
                    searchHistoryRepository,
                )
            }
        }
    }
}

private fun VaultFileFailure.toHomeFileError(): HomeFileError = when (this) {
    VaultFileFailure.INVALID_NAME -> HomeFileError.INVALID_NAME
    VaultFileFailure.NAME_TAKEN, VaultFileFailure.ALREADY_EXISTS -> HomeFileError.NAME_TAKEN
    VaultFileFailure.RESTORE_CONFLICT -> HomeFileError.RESTORE_CONFLICT
    else -> HomeFileError.GENERIC
}

private fun VaultFileException.toMessage(): HomeMessage = HomeMessage(
    when (failure) {
        VaultFileFailure.INVALID_PATH -> HomeNotice.InvalidMoveTarget
        VaultFileFailure.NAME_TAKEN, VaultFileFailure.ALREADY_EXISTS -> HomeNotice.NameTaken
        else -> HomeNotice.ActionFailed
    },
)

private fun NoteListEntry.toHomeItem() = HomeNoteItem(
    summary = summary,
    coverPath = coverPath,
    markdownSnippet = markdownSnippet,
    searchableText = searchText,
)
