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
import com.example.looma.data.search.SemanticModelMissingException
import com.example.looma.data.search.SemanticSearchRepository
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.domain.search.SearchMatch
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val noteRepository: NoteRepository,
    private val folderRepository: FolderRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val semanticSearchRepository: SemanticSearchRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var modelJob: Job? = null
    private var revealGeneration = 0L
    private var index: HomeVaultIndex = HomeVaultIndex.Empty

    fun refresh(): Job {
        refreshJob?.cancel()
        clearSearch()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val result = noteRepository.listNotes()
                val vault = result.vault
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
                dialog = null,
                dialogError = null,
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
            _uiState.update {
                if (it.semanticSearch is HomeSemanticSearch.Indexing || it.semanticSearch is HomeSemanticSearch.SearchFailed) {
                    it.copy(semanticSearch = HomeSemanticSearch.Ready)
                } else {
                    it
                }
            }
        } else if (!_uiState.value.isLoading) {
            scheduleSearch(value)
        }
    }

    private fun clearSearch() {
        searchJob?.cancel()
    }

    fun setSemanticSearch(enabled: Boolean) {
        clearSearch()
        modelJob?.cancel()
        val status = when {
            !enabled -> HomeSemanticSearch.Off
            !semanticSearchRepository.supported -> HomeSemanticSearch.Unsupported
            else -> HomeSemanticSearch.CheckingModel
        }
        _uiState.update { it.copy(semanticSearch = status, searchResults = emptyList(), isSearching = false) }
        when (status) {
            HomeSemanticSearch.Off -> {
                unloadSemanticModel()
                scheduleSearch(_uiState.value.query)
            }
            HomeSemanticSearch.CheckingModel -> {
                modelJob = viewModelScope.launch {
                    val available = semanticSearchRepository.hasModel()
                    _uiState.update {
                        it.copy(semanticSearch = if (available) HomeSemanticSearch.Ready else HomeSemanticSearch.NeedsModel)
                    }
                    if (available && !_uiState.value.isLoading) scheduleSearch(_uiState.value.query)
                }
            }
            else -> Unit
        }
    }

    fun downloadSemanticModel() {
        val status = _uiState.value.semanticSearch
        if (status != HomeSemanticSearch.NeedsModel && status != HomeSemanticSearch.DownloadFailed) return
        _uiState.update { it.copy(semanticSearch = HomeSemanticSearch.Downloading(0), isSearching = false) }
        modelJob = viewModelScope.launch {
            try {
                semanticSearchRepository.downloadModel { percent ->
                    _uiState.update {
                        if (it.semanticSearch is HomeSemanticSearch.Downloading) {
                            it.copy(semanticSearch = HomeSemanticSearch.Downloading(percent))
                        } else {
                            it
                        }
                    }
                }
                _uiState.update { it.copy(semanticSearch = HomeSemanticSearch.Ready) }
                if (!_uiState.value.isLoading) scheduleSearch(_uiState.value.query, debounce = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(semanticSearch = HomeSemanticSearch.DownloadFailed) }
            }
        }
    }

    fun retrySemanticSearch() {
        if (_uiState.value.semanticSearch == HomeSemanticSearch.SearchFailed) {
            scheduleSearch(_uiState.value.query, debounce = false)
        }
    }

    fun onSearchClosed() {
        setQuery("")
        if (_uiState.value.semanticSearch is HomeSemanticSearch.Downloading) {
            modelJob?.cancel()
            _uiState.update { it.copy(semanticSearch = HomeSemanticSearch.NeedsModel) }
        }
        unloadSemanticModel()
    }

    fun unloadSemanticModel() {
        viewModelScope.launch { semanticSearchRepository.releaseModel() }
    }

    private fun scheduleSearch(value: String, debounce: Boolean = true) {
        searchJob?.cancel()
        val keyword = value.trim()
        if (keyword.isEmpty()) return
        val semantic = _uiState.value.semanticSearch
        if (semantic != HomeSemanticSearch.Off) {
            if (semantic.canSearch) scheduleSemanticSearch(value, keyword, debounce)
            return
        }
        _uiState.update { it.copy(isSearching = true) }
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val snapshotState = _uiState.value
            val vaultId = snapshotState.vaultId
            val notes = snapshotState.notes
            val sort = snapshotState.sort
            val results = if (vaultId == null || notes.isEmpty()) {
                emptyList()
            } else {
                try {
                    withContext(Dispatchers.Default) {
                        val matches = noteRepository.search(
                            candidates = notes.map { it.toSearchableNote() },
                            query = keyword,
                        )
                        mergeSearchMatches(notes, matches).orderedBy(sort)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
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

    private fun scheduleSemanticSearch(value: String, keyword: String, debounce: Boolean) {
        _uiState.update { it.copy(semanticSearch = HomeSemanticSearch.Ready, isSearching = true) }
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val notes = _uiState.value.notes.associateBy { it.id }
            val (results, status) = try {
                semanticSearchRepository.search(keyword, notes.values.map { it.toSearchableNote() }) { completed, total ->
                    _uiState.update {
                        if (it.query == value && it.semanticSearch.canSearch) {
                            it.copy(semanticSearch = HomeSemanticSearch.Indexing(completed, total))
                        } else {
                            it
                        }
                    }
                }.mapNotNull { hit ->
                    val note = notes[hit.id] ?: return@mapNotNull null
                    HomeSearchResult(
                        note = note,
                        titleMatch = null,
                        tagMatches = emptyList(),
                        matches = listOf(SearchMatch(hit.excerpt, emptyList())),
                        passage = hit.anchor,
                        section = hit.section,
                    )
                } to HomeSemanticSearch.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (_: SemanticModelMissingException) {
                emptyList<HomeSearchResult>() to HomeSemanticSearch.NeedsModel
            } catch (_: Exception) {
                emptyList<HomeSearchResult>() to HomeSemanticSearch.SearchFailed
            } catch (_: LinkageError) {
                emptyList<HomeSearchResult>() to HomeSemanticSearch.Unsupported
            }
            _uiState.update {
                if (it.query == value && it.semanticSearch.canSearch) {
                    it.copy(searchResults = results, semanticSearch = status, isSearching = false)
                } else {
                    it
                }
            }
        }
    }

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
            searchResults = if (it.semanticSearch == HomeSemanticSearch.Off) it.searchResults.orderedBy(sort) else it.searchResults,
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
            it.copy(dialog = HomeFileDialog.Rename(item.id, item.fileName), dialogError = null)
        }
    }

    fun startProperties() {
        val item = _uiState.value.singleNote() ?: return
        val request = HomeFileDialog.LoadingProperties(item.id)
        _uiState.update { it.copy(dialog = request, dialogError = null) }
        viewModelScope.launch {
            val properties = try {
                noteRepository.properties(request.noteId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _uiState.update { it.finishProperties(request, properties) }
        }
    }

    fun startNewFolder() = _uiState.update {
        it.copy(dialog = HomeFileDialog.NewFolder, dialogError = null)
    }

    fun dismissDialog() = _uiState.update { it.copy(dialog = null, dialogError = null) }

    fun confirmRename(dialog: HomeFileDialog.Rename, name: String) {
        if (_uiState.value.dialog !== dialog) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(dialogError = HomeFileError.EMPTY_NAME) }
            return
        }
        runDialogAction {
            val outcome = noteRepository.renameNote(dialog.noteId, trimmed)
            HomeMessage(
                notice = HomeNotice.Renamed(outcome.linksUpdated, outcome.linksFailed),
                undo = HomeUndo.RenameNote(outcome.note.id, dialog.initialName),
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
        val state = _uiState.value
        if (state.isActionRunning) return
        _uiState.update { it.copy(isActionRunning = true, dialogError = null) }
        viewModelScope.launch {
            try {
                finishAction(message())
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelocationStalledException) {
                reportStalledRelocation(e.stalled)
            } catch (e: Exception) {
                val error = (e as? VaultFileException)?.failure?.toHomeFileError() ?: HomeFileError.GENERIC
                _uiState.update { current ->
                    current.copy(
                        isActionRunning = false,
                        dialogError = error.takeIf { current.dialog === state.dialog },
                    )
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
            noteRepository: NoteRepository,
            folderRepository: FolderRepository,
            searchHistoryRepository: SearchHistoryRepository,
            semanticSearchRepository: SemanticSearchRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    noteRepository,
                    folderRepository,
                    searchHistoryRepository,
                    semanticSearchRepository,
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
)
