package com.example.looma.feature.home

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteProperties
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.model.removeMarkdownSuffix
import java.util.Locale

enum class HomeTopBarMode { Root, Folder, Selection }

enum class HomeBusy { Idle, CreatingNote, RunningAction }

data class HomePendingOpen(val noteId: NoteId, val isNew: Boolean)

data class HomeSearchUiState(
    val query: String = "",
    val results: List<HomeSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val semanticSearch: HomeSemanticSearch = HomeSemanticSearch.Off,
    val recentSearches: List<String> = emptyList(),
)

sealed interface HomeError {
    data class UnreadableNotes(val count: Int) : HomeError
    data object LoadFailed : HomeError
    data object CreateFailed : HomeError
}

sealed interface HomeSyncBanner {
    data class Syncing(val completed: Int, val total: Int) : HomeSyncBanner
    data class Failed(val syncAtMillis: Long) : HomeSyncBanner
    data class Conflicts(val count: Int, val syncAtMillis: Long) : HomeSyncBanner
}

enum class HomeFileError {
    EMPTY_NAME,
    INVALID_NAME,
    NAME_TAKEN,
    RESTORE_CONFLICT,
    GENERIC,
}

sealed interface HomeSelection {
    data object None : HomeSelection

    data class Notes(val keys: Set<String>) : HomeSelection {
        companion object {
            fun of(keys: Set<String>): HomeSelection = if (keys.isEmpty()) None else Notes(keys)
        }
    }

    data class Folder(val item: HomeFolderItem) : HomeSelection

    val noteKeys: Set<String> get() = (this as? Notes)?.keys.orEmpty()
    val folder: HomeFolderItem? get() = (this as? Folder)?.item
}

sealed interface HomeUndo {
    data class RestoreNotes(val trashIds: List<String>, val vaultId: VaultId) : HomeUndo

    data class RenameNote(val id: NoteId, val name: String) : HomeUndo

    data class MoveNotes(val moves: List<MovedNote>) : HomeUndo

    data class RenameFolder(val path: FolderPath, val name: String) : HomeUndo

    data class MoveFolder(val path: FolderPath, val parent: FolderPath) : HomeUndo

    data class DeleteFolder(val path: FolderPath, val vaultId: VaultId) : HomeUndo
}

data class MovedNote(val id: NoteId, val originalPath: NotePath)

data class HomeMessage(
    val notice: HomeNotice,
    val undo: HomeUndo? = null,
    val generation: Long = 0L,
)

sealed interface HomeNotice {
    data class Deleted(val count: Int) : HomeNotice
    data class Restored(val count: Int) : HomeNotice
    data class Moved(val count: Int) : HomeNotice
    data class MovePartial(val succeeded: Int, val failed: Int) : HomeNotice
    data class DeletePartial(val succeeded: Int, val failed: Int) : HomeNotice
    data class UndoPartial(val succeeded: Int, val failed: Int) : HomeNotice

    data class Renamed(val linksUpdated: Int, val linksFailed: Int = 0) : HomeNotice
    data object FolderCreated : HomeNotice
    data object FolderRenamed : HomeNotice
    data object FolderMoved : HomeNotice

    data class FolderDeleted(val notes: Int) : HomeNotice
    data class FolderKept(val trashed: Int, val failed: Int) : HomeNotice
    data object NameTaken : HomeNotice
    data object ActionFailed : HomeNotice
    data object InvalidMoveTarget : HomeNotice
    data object Undone : HomeNotice
    data object UndoFailed : HomeNotice
    data object StalledRelocationUnfinished : HomeNotice
    data object StalledRelocationCompleted : HomeNotice
    data object StalledRelocationAbandoned : HomeNotice
}

sealed interface HomeFileDialog {
    data class Rename(
        val noteId: NoteId,
        val initialName: String,
        val error: HomeFileError? = null,
        val id: Long = 0L,
    ) : HomeFileDialog

    class LoadingProperties(val noteId: NoteId) : HomeFileDialog

    data class Properties(val properties: NoteProperties) : HomeFileDialog

    data class NewFolder(
        val error: HomeFileError? = null,
        val id: Long = 0L,
    ) : HomeFileDialog

    data class RenameFolder(
        val path: FolderPath,
        val initialName: String,
        val error: HomeFileError? = null,
        val id: Long = 0L,
    ) : HomeFileDialog

    data class DeleteFolder(
        val path: FolderPath,
        val folderName: String,
        val noteCount: Int,
        val error: HomeFileError? = null,
        val id: Long = 0L,
    ) : HomeFileDialog

    data class AbandonStalledRelocation(
        val stalled: StalledRelocation,
        val error: HomeFileError? = null,
        val id: Long = 0L,
    ) : HomeFileDialog
}

internal val HomeFileDialog.instanceId: Long?
    get() = when (this) {
        is HomeFileDialog.Rename -> id
        is HomeFileDialog.NewFolder -> id
        is HomeFileDialog.RenameFolder -> id
        is HomeFileDialog.DeleteFolder -> id
        is HomeFileDialog.AbandonStalledRelocation -> id
        is HomeFileDialog.LoadingProperties,
        is HomeFileDialog.Properties,
        -> null
    }

internal fun HomeFileDialog.withError(error: HomeFileError?): HomeFileDialog = when (this) {
    is HomeFileDialog.Rename -> copy(error = error)
    is HomeFileDialog.NewFolder -> copy(error = error)
    is HomeFileDialog.RenameFolder -> copy(error = error)
    is HomeFileDialog.DeleteFolder -> copy(error = error)
    is HomeFileDialog.AbandonStalledRelocation -> copy(error = error)
    is HomeFileDialog.LoadingProperties,
    is HomeFileDialog.Properties,
    -> this
}

data class HomeNoteItem(
    val summary: NoteSummary,
    val coverPath: String? = null,
    val markdownSnippet: String = "",
) {
    val id: NoteId get() = summary.id

    val listKey: String get() = "${id.vaultId.value}/${id.path.relativePath}"

    val fileName: String
        get() = id.path.relativePath
            .substringAfterLast('/')
            .removeMarkdownSuffix()
}

data class RecentReveal(val generation: Long, val noteId: NoteId)

internal fun RecentReveal.presentIn(recents: List<HomeRecentItem>): Boolean =
    recents.any { it.note.id == noteId }

internal fun HomeUiState.acknowledgeReveal(request: RecentReveal): HomeUiState =
    if (recentReveal == request) copy(recentReveal = null) else this

enum class HomeSort { LAST_MODIFIED, TITLE, TITLE_DESCENDING }

sealed interface HomeSemanticSearch {
    data object Off : HomeSemanticSearch
    data object Unsupported : HomeSemanticSearch
    data object Checking : HomeSemanticSearch
    data object NeedsModel : HomeSemanticSearch
    data object NeedsIndex : HomeSemanticSearch
    data object Ready : HomeSemanticSearch
    data object SearchFailed : HomeSemanticSearch

    val canSearch: Boolean get() = this == Ready || this == SearchFailed
}

data class HomeUiState(
    val vaultId: VaultId? = null,
    val vaultName: String = "Looma",
    val vaultRootPath: String? = null,
    val notes: List<HomeNoteItem> = emptyList(),
    val search: HomeSearchUiState = HomeSearchUiState(),
    val isGridMode: Boolean = true,
    val sort: HomeSort = HomeSort.LAST_MODIFIED,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val busy: HomeBusy = HomeBusy.Idle,
    val error: HomeError? = null,
    val pendingOpen: HomePendingOpen? = null,
    val recentReveal: RecentReveal? = null,
    val files: HomeFilesState = HomeFilesState(),
    val selection: HomeSelection = HomeSelection.None,
    val dialog: HomeFileDialog? = null,
    val message: HomeMessage? = null,
    val stalledRelocation: StalledRelocation? = null,
    val syncBanner: HomeSyncBanner? = null,
) {
    val topBarMode: HomeTopBarMode
        get() = when {
            selection != HomeSelection.None -> HomeTopBarMode.Selection
            files.isAtRoot -> HomeTopBarMode.Root
            else -> HomeTopBarMode.Folder
        }

    val isBrowsing: Boolean get() = !files.isAtRoot
    val isSelecting: Boolean get() = selection != HomeSelection.None
    val isBusy: Boolean get() = busy != HomeBusy.Idle
    val isCreating: Boolean get() = busy == HomeBusy.CreatingNote
    val isActionRunning: Boolean get() = busy == HomeBusy.RunningAction
    val selectedFolder: HomeFolderItem? get() = selection.folder
    val selectedKeys: Set<String> get() = selection.noteKeys
    val selectedNotes: List<HomeNoteItem>
        get() = if (selection is HomeSelection.Notes) {
            notes.filter { it.listKey in selection.keys }.orderedBy(sort)
        } else {
            emptyList()
        }

    internal fun finishProperties(
        request: HomeFileDialog.LoadingProperties,
        properties: NoteProperties?,
        messageGeneration: Long = 0L,
    ): HomeUiState {
        if (dialog !== request) return this
        if (vaultId != request.noteId.vaultId || selectedNotes.singleOrNull()?.id != request.noteId) {
            return copy(dialog = null)
        }
        return if (properties != null) {
            copy(dialog = HomeFileDialog.Properties(properties))
        } else {
            copy(dialog = null, message = HomeMessage(HomeNotice.ActionFailed, generation = messageGeneration))
        }
    }

    internal fun applyDialogError(dialogId: Long, error: HomeFileError): HomeUiState {
        val current = dialog ?: return this
        if (current.instanceId != dialogId) return this
        return copy(dialog = current.withError(error))
    }
}

internal fun <T> List<T>.orderedByNote(
    sort: HomeSort,
    noteOf: (T) -> HomeNoteItem,
): List<T> {
    if (size <= 1) return toList()
    return when (sort) {
        HomeSort.LAST_MODIFIED -> sortedWith(
            compareByDescending<T> { noteOf(it).summary.modifiedAt }.thenBy { noteOf(it).listKey },
        )
        HomeSort.TITLE -> map { it to noteOf(it).summary.title.lowercase(Locale.ROOT) }
            .sortedWith(compareBy<Pair<T, String>> { it.second }.thenBy { noteOf(it.first).listKey })
            .map { it.first }
        HomeSort.TITLE_DESCENDING -> map { it to noteOf(it).summary.title.lowercase(Locale.ROOT) }
            .sortedWith(compareByDescending<Pair<T, String>> { it.second }.thenBy { noteOf(it.first).listKey })
            .map { it.first }
    }
}

internal fun List<HomeNoteItem>.orderedBy(sort: HomeSort): List<HomeNoteItem> =
    orderedByNote(sort) { it }
