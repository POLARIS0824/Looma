package com.example.looma.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.folder.FolderRepository
import com.example.looma.data.note.NoteIndexChange
import com.example.looma.data.note.NoteListEntry
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.note.RelocationStalledException
import com.example.looma.data.note.VaultFileException
import com.example.looma.data.note.VaultFileFailure
import com.example.looma.data.search.SearchHistoryRepository
import com.example.looma.data.search.SemanticSearchRepository
import com.example.looma.data.sync.SyncActiveWork
import com.example.looma.data.sync.SyncRepository
import com.example.looma.data.sync.SyncStatus
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.domain.sync.SyncPhase
import com.example.looma.domain.sync.SyncRunOutcome
import com.example.looma.ui.markdown.warmMarkdownPreview
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val syncRepository: SyncRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var revealGeneration = 0L
    private var messageGeneration = 0L
    private var dialogGeneration = 0L
    private var dismissedSyncAtMillis: Long? = null
    private var index: HomeVaultIndex = HomeVaultIndex.Empty
    private var indexedListing: com.example.looma.data.note.NoteListResult? = null
    private val searchController = HomeSearchController(
        scope = viewModelScope,
        uiState = _uiState,
        noteRepository = noteRepository,
        searchHistoryRepository = searchHistoryRepository,
        semanticSearchRepository = semanticSearchRepository,
    )

    init {
        viewModelScope.launch {
            noteRepository.noteIndexChanges.collect { change ->
                applyIndexChange(change)
            }
        }
        if (syncRepository != null) {
            viewModelScope.launch {
                syncRepository.vaultMutatedEvents.collect { vaultId ->
                    if (_uiState.value.vaultId == vaultId) {
                        refresh()
                    }
                }
            }
            viewModelScope.launch {
                syncRepository.status.collect { status ->
                    val banner = syncBannerFor(status)
                    _uiState.update { it.copy(syncBanner = banner) }
                }
            }
        }
    }

    fun refresh(): Job = refresh(force = false)

    private fun refresh(force: Boolean): Job {
        refreshJob?.cancel()
        searchController.clearSearch()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.notes.isEmpty(), error = null) }
            val previousState = _uiState.value
            val previousVaultId = previousState.vaultId
            val previousSort = previousState.sort
            val previousNotes = previousState.notes
            try {
                val result = noteRepository.listNotes(force = force)
                val vault = result.vault
                val previous = indexedListing
                val oldIndex = index
                index = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    if (!force && result.version != null && previous != null && result.vault == previous.vault &&
                        result.version == previous.version && result.folders == previous.folders) oldIndex
                    else HomeVaultIndex.build(result.folders, result.items.map { it.toHomeItem() })
                }
                indexedListing = result
                var preserveSearch = false
                _uiState.update { current ->
                    val path = current.files.path
                        .takeIf(index::contains)
                        ?: FolderPath.Root
                    val alive = index.keys
                    val selection = current.selection.normalized(alive)
                    val orderedNotes = index.notes(current.sort)
                    val newFiles = index.files(path, current.sort)
                    val recentItems = when {
                        current.recentReveal == null -> emptyList()
                        newFiles.isAtRoot -> newFiles.recents
                        else -> index.recents()
                    }
                    preserveSearch = !force && !searchController.resultsStale && previousVaultId == vault.id &&
                        previousSort == current.sort &&
                        previousNotes == orderedNotes &&
                        !current.search.isSearching &&
                        current.search.query.isNotBlank() &&
                        current.search.results.isNotEmpty()
                    current.copy(
                        vaultId = vault.id,
                        vaultName = vault.name,
                        vaultRootPath = vault.rootPath,
                        notes = orderedNotes,
                        search = if (preserveSearch) {
                            current.search
                        } else {
                            current.search.copy(results = emptyList())
                        },
                        files = newFiles,
                        recentReveal = current.recentReveal?.takeIf { request ->
                            request.presentIn(recentItems)
                        },
                        selection = selection,
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
                if (!preserveSearch) {
                    searchController.clearResultsStale()
                    searchController.scheduleSearch(_uiState.value.search.query, debounce = false, retryFailedSemantic = false)
                }
                searchController.loadRecentSearches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                indexedListing = null
                index = HomeVaultIndex.Empty
                _uiState.update {
                    it.copy(
                        vaultId = null,
                        notes = emptyList(),
                        search = it.search.copy(results = emptyList(), isSearching = false),
                        files = HomeFilesState(),
                        selection = HomeSelection.None,
                        isLoading = false,
                        isRefreshing = false,
                        error = HomeError.LoadFailed,
                    )
                }
            }
        }
        return refreshJob!!
    }

    private suspend fun applyIndexChange(change: NoteIndexChange) {
        val listing = indexedListing ?: return
        if (listing.vault != change.vault) return
        if (listing.version != null && change.version <= listing.version) return
        val removedPaths = change.removed.toHashSet()
        val upsertedByPath = change.upserts.associateBy { it.summary.id.path }
        val (updatedItems, newIndex) = withContext(Dispatchers.Default) {
            change.upserts.forEach { warmMarkdownPreview(it.markdownSnippet) }
            val merged = buildList(listing.items.size + change.upserts.size) {
                listing.items.forEach { item ->
                    val path = item.summary.id.path
                    if (path !in removedPaths && path !in upsertedByPath) add(item)
                }
                addAll(change.upserts)
            }.sortedByDescending { it.summary.modifiedAt }
            merged to HomeVaultIndex.build(listing.folders, merged.map { it.toHomeItem() })
        }
        val latest = indexedListing
        if (latest !== listing) return
        index = newIndex
        indexedListing = listing.copy(items = updatedItems, version = change.version)
        if (_uiState.value.search.query.isNotBlank()) {
            searchController.markResultsStale()
        }
        _uiState.update { current ->
            val path = current.files.path.takeIf(index::contains) ?: FolderPath.Root
            val alive = index.keys
            val selection = current.selection.normalized(alive)
            val orderedNotes = index.notes(current.sort)
            val newFiles = index.files(path, current.sort)
            val recentItems = when {
                current.recentReveal == null -> emptyList()
                newFiles.isAtRoot -> newFiles.recents
                else -> index.recents()
            }
            current.copy(
                notes = orderedNotes,
                files = newFiles,
                recentReveal = current.recentReveal?.takeIf { request -> request.presentIn(recentItems) },
                selection = selection,
            )
        }
    }

    fun startAbandonStalledRelocation() {
        val current = _uiState.value
        if (current.isBusy) return
        val stalled = current.stalledRelocation ?: return
        _uiState.update {
            it.copy(dialog = HomeFileDialog.AbandonStalledRelocation(stalled, id = nextDialogId()))
        }
    }

    fun confirmAbandonStalledRelocation() {
        val dialog = _uiState.value.dialog as? HomeFileDialog.AbandonStalledRelocation ?: return
        runDialogAction(dialog.id) {
            noteRepository.abandonStalledRelocation(dialog.stalled.journalId)
            homeMessage(HomeNotice.StalledRelocationAbandoned)
        }
    }

    fun refreshFromUser() {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isRefreshing = true) }
        refresh(force = true)
    }

    fun openFolder(path: FolderPath) {
        if (!index.contains(path)) return
        _uiState.update {
            it.copy(
                files = index.files(path, it.sort),
                selection = HomeSelection.None,
                dialog = null,
            )
        }
    }

    fun navigateUp() {
        val current = _uiState.value
        if (current.files.isAtRoot) return
        openFolder(current.files.path.parentFolder())
    }

    fun setQuery(value: String) = searchController.setQuery(value)

    fun setSemanticSearch(enabled: Boolean) = searchController.setSemanticSearch(enabled)

    fun retrySemanticSearch() = searchController.retrySemanticSearch()

    fun onSearchOpened() = searchController.onSearchOpened()

    fun onSearchClosed() = searchController.onSearchClosed()

    fun unloadSemanticModel() = searchController.unloadSemanticModel()

    fun recordSearch() = searchController.recordSearch()

    fun toggleLayout() = _uiState.update { it.copy(isGridMode = !it.isGridMode) }

    fun setSort(sort: HomeSort) = _uiState.update {
        it.copy(
            sort = sort,
            notes = index.notes(sort),
            search = if (it.search.semanticSearch == HomeSemanticSearch.Off) {
                it.search.copy(results = it.search.results.orderedBy(sort))
            } else {
                it.search
            },
            files = index.files(it.files.path, sort),
        )
    }

    fun createNote(title: String) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = HomeBusy.CreatingNote, error = null) }
            try {
                val folder = _uiState.value.files.path
                val note = noteRepository.createNote(title = title, folder = folder)
                val request = RecentReveal(++revealGeneration, note.id)
                _uiState.update {
                    it.copy(
                        pendingOpen = HomePendingOpen(note.id, isNew = true),
                        recentReveal = request,
                    )
                }
                refresh().join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = HomeError.CreateFailed) }
            } finally {
                _uiState.update { current ->
                    if (current.busy == HomeBusy.CreatingNote) current.copy(busy = HomeBusy.Idle) else current
                }
            }
        }
    }

    fun onPendingOpenHandled() = _uiState.update { it.copy(pendingOpen = null) }

    fun onRecentRevealed(request: RecentReveal) = _uiState.update { it.acknowledgeReveal(request) }

    fun onSelectionEvent(event: HomeSelectionEvent) {
        val current = _uiState.value
        if (current.isBusy) return
        when (val outcome = reduceHomeSelection(current.selection, event)) {
            is HomeSelectionOutcome.Changed -> _uiState.update {
                it.copy(
                    selection = outcome.selection,
                    dialog = null,
                )
            }
            is HomeSelectionOutcome.OpenFolder -> openFolder(outcome.path)
            is HomeSelectionOutcome.OpenNote -> _uiState.update {
                it.copy(pendingOpen = HomePendingOpen(outcome.id, isNew = false))
            }
        }
    }

    fun clearSelection() = _uiState.update {
        it.copy(
            selection = HomeSelection.None,
            dialog = null,
        )
    }

    fun startRename() {
        val current = _uiState.value
        if (current.isBusy) return
        val item = current.singleNote() ?: return
        _uiState.update {
            it.copy(dialog = HomeFileDialog.Rename(item.id, item.fileName, id = nextDialogId()))
        }
    }

    fun startProperties() {
        val current = _uiState.value
        if (current.isBusy) return
        val item = current.singleNote() ?: return
        val request = HomeFileDialog.LoadingProperties(item.id)
        _uiState.update { it.copy(dialog = request) }
        viewModelScope.launch {
            val properties = try {
                noteRepository.properties(request.noteId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val failureGeneration = if (properties == null) ++messageGeneration else 0L
            _uiState.update { it.finishProperties(request, properties, failureGeneration) }
        }
    }

    fun startNewFolder() {
        if (_uiState.value.isBusy) return
        _uiState.update {
            it.copy(dialog = HomeFileDialog.NewFolder(id = nextDialogId()))
        }
    }

    fun dismissDialog() = _uiState.update { it.copy(dialog = null) }

    fun confirmRename(dialog: HomeFileDialog.Rename, name: String) {
        val active = _uiState.value.dialog as? HomeFileDialog.Rename ?: return
        if (active.id != dialog.id) return
        val trimmed = trimmedNameOrError(active, name) ?: return
        runDialogAction(active.id) {
            val outcome = noteRepository.renameNote(active.noteId, trimmed)
            homeMessage(
                notice = HomeNotice.Renamed(outcome.linksUpdated, outcome.linksFailed),
                undo = HomeUndo.RenameNote(outcome.note.id, active.initialName),
            )
        }
    }

    fun confirmNewFolder(name: String) {
        val dialog = _uiState.value.dialog as? HomeFileDialog.NewFolder ?: return
        val trimmed = trimmedNameOrError(dialog, name) ?: return
        runDialogAction(dialog.id) {
            val folder = folderRepository.createFolder(_uiState.value.files.path, trimmed)
            homeMessage(
                notice = HomeNotice.FolderCreated,
                undo = HomeUndo.DeleteFolder(folder.path, folder.vaultId),
            )
        }
    }

    fun startFolderRename() {
        val current = _uiState.value
        if (current.isBusy) return
        val folder = current.selectedFolder ?: return
        _uiState.update {
            it.copy(
                dialog = HomeFileDialog.RenameFolder(folder.path, folder.name, id = nextDialogId()),
            )
        }
    }

    fun startFolderDelete() {
        val current = _uiState.value
        if (current.isBusy) return
        val folder = current.selectedFolder ?: return
        val vaultId = current.vaultId ?: return
        runBusyAction {
            try {
                val paths = folderRepository.folderNotePaths(folder.path, vaultId)
                _uiState.update { state ->
                    if (state.vaultId != vaultId || state.selectedFolder != folder) state
                    else state.copy(dialog = HomeFileDialog.DeleteFolder(folder.path, folder.name, paths, id = nextDialogId()))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(message = homeMessage(HomeNotice.ActionFailed)) }
            }
        }
    }

    fun confirmRenameFolder(name: String) {
        val dialog = _uiState.value.dialog as? HomeFileDialog.RenameFolder ?: return
        val trimmed = trimmedNameOrError(dialog, name) ?: return
        val previousName = dialog.path.relativePath.substringAfterLast('/')
        runDialogAction(dialog.id) {
            val renamed = folderRepository.renameFolder(dialog.path, trimmed)
            homeMessage(
                notice = HomeNotice.FolderRenamed,
                undo = HomeUndo.RenameFolder(renamed.path, previousName),
            )
        }
    }

    fun confirmDeleteFolder() {
        val dialog = _uiState.value.dialog as? HomeFileDialog.DeleteFolder ?: return
        val vaultId = _uiState.value.vaultId ?: return
        runDialogAction(dialog.id) {
            val result = folderRepository.deleteFolderWithOutcome(dialog.path, vaultId, dialog.paths)
            homeMessage(
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
            _uiState.update { it.copy(message = homeMessage(HomeNotice.InvalidMoveTarget)) }
            return
        }
        val state = _uiState.value
        if (state.isBusy) return
        when (val selection = state.selection) {
            HomeSelection.None -> Unit
            is HomeSelection.Folder -> moveFolderTo(selection.item, target)
            is HomeSelection.Notes -> moveNotesTo(state.selectedNotes, target)
        }
    }

    private fun moveNotesTo(notes: List<HomeNoteItem>, target: FolderPath) {
        if (notes.isEmpty()) return
        runBusyAction {
            val result = try {
                val moved = noteRepository.relocateNotes(notes.map { it.id }, target)
                    .map { (id, destination) -> MovedNote(id.copy(path = destination), id.path) }
                NoteBatchResult(moved, notes.size - moved.size, null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NoteBatchResult(emptyList(), notes.size, error)
            }
            val count = result.completed.size
            val notice = when {
                count == 0 -> (result.failure as? VaultFileException)?.toNotice() ?: HomeNotice.ActionFailed
                result.failed > 0 -> HomeNotice.MovePartial(count, result.failed)
                else -> HomeNotice.Moved(count)
            }
            val moved = result.completed.filter { it.id.path != it.originalPath }
            val undo = moved.takeIf { it.isNotEmpty() }?.let { HomeUndo.MoveNotes(it) }
            finishNoteBatch(result, homeMessage(notice, undo))
        }
    }

    private fun moveFolderTo(folder: HomeFolderItem, target: FolderPath) {
        runBusyAction {
            try {
                val moved = folderRepository.moveFolder(folder.path, target)
                finishAction(
                    homeMessage(
                        notice = HomeNotice.FolderMoved,
                        undo = HomeUndo.MoveFolder(moved.path, folder.path.parentFolder()),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelocationStalledException) {
                reportStalledRelocation(e.stalled)
            } catch (e: VaultFileException) {
                _uiState.update { it.copy(message = homeMessage(e.toNotice())) }
                refresh().join()
            } catch (e: Exception) {
                _uiState.update { it.copy(message = homeMessage(HomeNotice.ActionFailed)) }
                refresh().join()
            }
        }
    }

    fun deleteSelected() {
        val state = _uiState.value
        val vaultId = state.vaultId ?: return
        if (state.selectedNotes.isEmpty()) return
        runBusyAction {
            val result = processNotes(state.selectedNotes) { note -> noteRepository.deleteNote(note.id).trashId }
            val count = result.completed.size
            val notice = when {
                count == 0 -> HomeNotice.ActionFailed
                result.failed > 0 -> HomeNotice.DeletePartial(count, result.failed)
                else -> HomeNotice.Deleted(count)
            }
            val undo = result.completed.takeIf { it.isNotEmpty() }?.let { HomeUndo.RestoreNotes(it, vaultId) }
            finishNoteBatch(result, homeMessage(notice, undo))
        }
    }

    fun undo(action: HomeUndo) {
        runBusyAction {
            try {
                finishAction(homeMessage(applyUndo(action)))
            } catch (e: RelocationStalledException) {
                reportStalledRelocation(e.stalled)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                finishAction(homeMessage(HomeNotice.UndoFailed))
            }
        }
    }

    private fun runBusyAction(action: suspend () -> Unit) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(busy = HomeBusy.RunningAction, dialog = it.dialog?.withError(null))
            }
            try {
                action()
            } finally {
                _uiState.update { current ->
                    if (current.busy == HomeBusy.RunningAction) current.copy(busy = HomeBusy.Idle) else current
                }
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
            val succeeded = noteRepository.restoreLocations(action.moves.associate { it.id to it.originalPath })
            undoNotice(succeeded, action.moves.size - succeeded)
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

    private fun trimmedNameOrError(dialog: HomeFileDialog, name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            val id = dialog.instanceId
            if (id != null) {
                _uiState.update { it.applyDialogError(id, HomeFileError.EMPTY_NAME) }
            }
            return null
        }
        return trimmed
    }

    private fun runDialogAction(dialogId: Long, message: suspend () -> HomeMessage) {
        val state = _uiState.value
        if (state.isBusy) return
        _uiState.update {
            it.copy(busy = HomeBusy.RunningAction, dialog = it.dialog?.withError(null))
        }
        viewModelScope.launch {
            try {
                finishAction(message())
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelocationStalledException) {
                reportStalledRelocation(e.stalled)
            } catch (e: Exception) {
                val error = (e as? VaultFileException)?.failure?.toHomeFileError() ?: HomeFileError.GENERIC
                _uiState.update { current -> current.applyDialogError(dialogId, error) }
            } finally {
                _uiState.update { current ->
                    if (current.busy == HomeBusy.RunningAction) current.copy(busy = HomeBusy.Idle) else current
                }
            }
        }
    }

    private suspend fun reportStalledRelocation(stalled: StalledRelocation) {
        _uiState.update {
            it.copy(dialog = null, stalledRelocation = stalled)
        }
        refresh().join()
        _uiState.update {
            val notice = if (it.stalledRelocation == null) {
                HomeNotice.StalledRelocationCompleted
            } else {
                HomeNotice.StalledRelocationUnfinished
            }
            it.copy(busy = HomeBusy.Idle, message = homeMessage(notice))
        }
    }

    private suspend fun finishAction(message: HomeMessage) {
        refresh().join()
        _uiState.update {
            it.copy(
                busy = HomeBusy.Idle,
                dialog = null,
                selection = HomeSelection.None,
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

    private fun homeMessage(notice: HomeNotice, undo: HomeUndo? = null): HomeMessage =
        HomeMessage(notice, undo, ++messageGeneration)

    private fun nextDialogId(): Long = ++dialogGeneration

    fun dismissSyncBanner() {
        val syncAtMillis = when (val banner = _uiState.value.syncBanner) {
            is HomeSyncBanner.Failed -> banner.syncAtMillis
            is HomeSyncBanner.Conflicts -> banner.syncAtMillis
            is HomeSyncBanner.Syncing, null -> return
        }
        dismissedSyncAtMillis = syncAtMillis
        _uiState.update { it.copy(syncBanner = null) }
    }

    private fun syncBannerFor(status: SyncStatus): HomeSyncBanner? {
        when (val active = status.activeWork) {
            SyncActiveWork.Queued -> return HomeSyncBanner.Syncing(completed = 0, total = 0)
            is SyncActiveWork.Running -> {
                val completed = if (active.phase == SyncPhase.SYNCING) active.completed else 0
                val total = if (active.phase == SyncPhase.SYNCING) active.total else 0
                return HomeSyncBanner.Syncing(completed = completed, total = total)
            }
            null -> Unit
        }
        val last = status.lastState ?: return null
        val syncAtMillis = last.lastSyncAtMillis ?: return null
        if (syncAtMillis == dismissedSyncAtMillis) return null
        return when {
            last.lastOutcome == SyncRunOutcome.FAILED.name -> HomeSyncBanner.Failed(syncAtMillis)
            last.lastConflicts > 0 -> HomeSyncBanner.Conflicts(last.lastConflicts, syncAtMillis)
            last.lastOutcome == SyncRunOutcome.PARTIAL.name -> HomeSyncBanner.Failed(syncAtMillis)
            else -> null
        }
    }

    companion object {
        fun factory(
            noteRepository: NoteRepository,
            folderRepository: FolderRepository,
            searchHistoryRepository: SearchHistoryRepository,
            semanticSearchRepository: SemanticSearchRepository,
            syncRepository: SyncRepository? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    noteRepository,
                    folderRepository,
                    searchHistoryRepository,
                    semanticSearchRepository,
                    syncRepository,
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

private fun VaultFileException.toNotice(): HomeNotice = when (failure) {
    VaultFileFailure.INVALID_PATH -> HomeNotice.InvalidMoveTarget
    VaultFileFailure.NAME_TAKEN, VaultFileFailure.ALREADY_EXISTS -> HomeNotice.NameTaken
    else -> HomeNotice.ActionFailed
}

private fun NoteListEntry.toHomeItem() = HomeNoteItem(
    summary = summary,
    coverPath = coverPath,
    markdownSnippet = markdownSnippet,
)
