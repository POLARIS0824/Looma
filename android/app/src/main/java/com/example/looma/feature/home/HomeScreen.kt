@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.example.looma.feature.home

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.looma.R
import com.example.looma.data.vault.VaultStorageAccess
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.feature.home.components.HomeFileDialogs
import com.example.looma.feature.home.components.HomeFilesGrid
import com.example.looma.feature.home.components.HomeListControls
import com.example.looma.feature.home.components.HomeSearchField
import com.example.looma.feature.home.components.HomeSearchOverlay
import com.example.looma.feature.home.components.HomeTopBarHost
import com.example.looma.feature.home.components.rememberHomeSearchState
import com.example.looma.ui.components.ErrorBanner
import com.example.looma.ui.components.LoadingPage
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.MessagePage
import com.example.looma.ui.components.noteGridMargin
import com.example.looma.ui.components.rememberDelayedVisibility
import com.example.looma.ui.theme.LoomaTokens

@Immutable
data class HomeSearchActions(
    val onQueryChange: (String) -> Unit = {},
    val onSearchSubmit: () -> Unit = {},
    val onSearchResultClick: (HomeSearchResult) -> Unit = {},
    val onSemanticSearchToggle: (Boolean) -> Unit = {},
    val onManageSemanticSearch: () -> Unit = {},
    val onRetrySemanticSearch: () -> Unit = {},
    val onSearchOpened: () -> Unit = {},
    val onSearchClosed: () -> Unit = {},
)

@Immutable
data class HomeBrowseActions(
    val onToggleLayout: () -> Unit = {},
    val onSortSelect: (HomeSort) -> Unit = {},
    val onNoteClick: (HomeNoteItem) -> Unit = {},
    val onNoteLongClick: (HomeNoteItem) -> Unit = {},
    val onFolderClick: (HomeFolderItem) -> Unit = {},
    val onFolderLongClick: (HomeFolderItem) -> Unit = {},
    val onBackgroundClick: () -> Unit = {},
    val onNavigateUp: () -> Unit = {},
    val onCreateNoteClick: () -> Unit = {},
    val onCreateFolderClick: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRecentRevealed: (RecentReveal) -> Unit = {},
    val onMenuClick: () -> Unit = {},
    val onSettingsClick: () -> Unit = {},
    val onRetryClick: () -> Unit = {},
    val onAbandonStalledRelocationClick: () -> Unit = {},
    val onOpenSyncSettings: () -> Unit = {},
    val onDismissSyncBanner: () -> Unit = {},
    val onUndo: (HomeUndo) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@Immutable
data class HomeSelectionActions(
    val onClearSelection: () -> Unit = {},
    val onRenameClick: () -> Unit = {},
    val onRenameFolderClick: () -> Unit = {},
    val onMoveClick: () -> Unit = {},
    val onInfoClick: () -> Unit = {},
    val onDeleteClick: () -> Unit = {},
    val onDeleteFolderClick: () -> Unit = {},
)

@Immutable
data class HomeDialogActions(
    val onConfirmRename: (HomeFileDialog.Rename, String) -> Unit = { _, _ -> },
    val onConfirmNewFolder: (String) -> Unit = {},
    val onConfirmRenameFolder: (String) -> Unit = {},
    val onConfirmDeleteFolder: () -> Unit = {},
    val onConfirmAbandonStalledRelocation: () -> Unit = {},
    val onDialogDismiss: () -> Unit = {},
)

@Immutable
data class HomeActions(
    val search: HomeSearchActions = HomeSearchActions(),
    val browse: HomeBrowseActions = HomeBrowseActions(),
    val selection: HomeSelectionActions = HomeSelectionActions(),
    val dialog: HomeDialogActions = HomeDialogActions(),
)

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onOpenNote: (NoteId) -> Unit,
    onOpenSearchResult: (HomeSearchResult) -> Unit,
    onCreatedNote: (NoteId) -> Unit,
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onManageSemanticSearch: () -> Unit,
    modifier: Modifier = Modifier,
    loadingFeedbackEnabled: Boolean = true,
    onOpenSyncSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val untitledTitle = stringResource(R.string.home_untitled_note)

    val moveTargetPicker = rememberVaultFolderPicker(
        vaultRoot = state.vaultRootPath,
        onPicked = viewModel::moveSelectionTo,
        onRejected = { viewModel.moveSelectionTo(null) },
    )

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.unloadSemanticModel() }

    LaunchedEffect(state.pendingOpen) {
        val pending = state.pendingOpen ?: return@LaunchedEffect
        if (pending.isNew) onCreatedNote(pending.noteId) else onOpenNote(pending.noteId)
        viewModel.onPendingOpenHandled()
    }

    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = state.topBarMode != HomeTopBarMode.Root,
        onBackCompleted = {
            if (state.isSelecting) viewModel.clearSelection() else viewModel.navigateUp()
        },
    )

    HomeScreen(
        state = state,
        loadingFeedbackEnabled = loadingFeedbackEnabled,
        homeVisible = LocalNavAnimatedContentScope.current.transition.targetState == EnterExitState.Visible,
        actions = HomeActions(
            search = HomeSearchActions(
                onQueryChange = viewModel::setQuery,
                onSearchSubmit = viewModel::recordSearch,
                onSearchResultClick = { result ->
                    viewModel.recordSearch()
                    onOpenSearchResult(result)
                },
                onSemanticSearchToggle = viewModel::setSemanticSearch,
                onManageSemanticSearch = onManageSemanticSearch,
                onRetrySemanticSearch = viewModel::retrySemanticSearch,
                onSearchOpened = viewModel::onSearchOpened,
                onSearchClosed = viewModel::onSearchClosed,
            ),
            browse = HomeBrowseActions(
                onToggleLayout = viewModel::toggleLayout,
                onSortSelect = viewModel::setSort,
                onNoteClick = { viewModel.onSelectionEvent(HomeSelectionEvent.NoteTapped(it)) },
                onNoteLongClick = { viewModel.onSelectionEvent(HomeSelectionEvent.NoteLongPressed(it)) },
                onFolderClick = { viewModel.onSelectionEvent(HomeSelectionEvent.FolderTapped(it)) },
                onFolderLongClick = { viewModel.onSelectionEvent(HomeSelectionEvent.FolderLongPressed(it)) },
                onBackgroundClick = { viewModel.onSelectionEvent(HomeSelectionEvent.BackgroundTapped) },
                onNavigateUp = viewModel::navigateUp,
                onCreateNoteClick = { viewModel.createNote(untitledTitle) },
                onCreateFolderClick = viewModel::startNewFolder,
                onRefresh = viewModel::refreshFromUser,
                onRecentRevealed = viewModel::onRecentRevealed,
                onMenuClick = onMenuClick,
                onSettingsClick = onSettingsClick,
                onRetryClick = viewModel::refresh,
                onAbandonStalledRelocationClick = viewModel::startAbandonStalledRelocation,
                onOpenSyncSettings = onOpenSyncSettings,
                onDismissSyncBanner = viewModel::dismissSyncBanner,
                onUndo = viewModel::undo,
                onMessageShown = viewModel::onMessageShown,
            ),
            selection = HomeSelectionActions(
                onClearSelection = viewModel::clearSelection,
                onRenameClick = viewModel::startRename,
                onRenameFolderClick = viewModel::startFolderRename,
                onMoveClick = { moveTargetPicker.launch(null) },
                onInfoClick = viewModel::startProperties,
                onDeleteClick = viewModel::deleteSelected,
                onDeleteFolderClick = viewModel::startFolderDelete,
            ),
            dialog = HomeDialogActions(
                onConfirmRename = viewModel::confirmRename,
                onConfirmNewFolder = viewModel::confirmNewFolder,
                onConfirmRenameFolder = viewModel::confirmRenameFolder,
                onConfirmDeleteFolder = viewModel::confirmDeleteFolder,
                onConfirmAbandonStalledRelocation = viewModel::confirmAbandonStalledRelocation,
                onDialogDismiss = viewModel::dismissDialog,
            ),
        ),
        modifier = modifier,
    )
}

@Composable
private fun rememberVaultFolderPicker(
    vaultRoot: String?,
    onPicked: (FolderPath) -> Unit,
    onRejected: () -> Unit,
): ManagedActivityResultLauncher<Uri?, Uri?> {
    val context = LocalContext.current
    val storageAccess = remember(context) { VaultStorageAccess(context) }
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = storageAccess.resolveFolderWithin(uri, vaultRoot)
        if (target != null) onPicked(target) else onRejected()
    }
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    loadingFeedbackEnabled: Boolean = true,
    homeVisible: Boolean = true,
) {
    val topBarMode = state.topBarMode
    val search = rememberHomeSearchState(initialQuery = state.search.query)
    val rootScrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()
    val gridState = key(state.files.path) { rememberLazyStaggeredGridState() }
    val folderScrollBehavior = key(state.files.path) {
        TopAppBarDefaults.enterAlwaysScrollBehavior(scrollableState = gridState)
    }
    LaunchedEffect(state.files.path) {
        if (state.files.isAtRoot) {
            rootScrollBehavior.scrollState.scrollOffset = 0f
            rootScrollBehavior.scrollState.contentOffset = 0f
        }
    }
    LaunchedEffect(topBarMode) {
        if (topBarMode == HomeTopBarMode.Root) rootScrollBehavior.scrollState.scrollOffset = 0f
    }
    val scrollConnection = when (topBarMode) {
        HomeTopBarMode.Selection -> null
        HomeTopBarMode.Root -> rootScrollBehavior.nestedScrollConnection
        HomeTopBarMode.Folder -> folderScrollBehavior.nestedScrollConnection
    }
    var sortSheetVisible by rememberSaveable { mutableStateOf(false) }
    val inputField: @Composable () -> Unit = {
        HomeSearchField(
            vaultName = state.vaultName,
            search = search,
            onSearchSubmit = actions.search.onSearchSubmit,
            showCollapsedActions = topBarMode == HomeTopBarMode.Root,
        ) {
            HomeListControls(
                isGridMode = state.isGridMode,
                onToggleLayout = actions.browse.onToggleLayout,
                onSortClick = { sortSheetVisible = true },
            )
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()
    val undoLabel = stringResource(R.string.action_undo)
    val messageText = state.message?.let { homeNoticeText(it.notice) }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val text = messageText ?: return@LaunchedEffect
        val undo = message.undo
        val result = snackbarHostState.showSnackbar(
            message = text,
            actionLabel = if (undo != null) undoLabel else null,
            duration = if (undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && undo != null) {
            actions.browse.onUndo(undo)
        }
        actions.browse.onMessageShown()
    }
    val onRecentRevealed = remember(rootScrollBehavior, actions.browse.onRecentRevealed) {
        { request: RecentReveal ->
            rootScrollBehavior.scrollState.scrollOffset = 0f
            rootScrollBehavior.scrollState.contentOffset = 0f
            actions.browse.onRecentRevealed(request)
        }
    }

    Box(modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize().then(scrollConnection?.let { Modifier.nestedScroll(it) } ?: Modifier),
            containerColor = LoomaTokens.colors.page,
            topBar = {
                HomeTopBarHost(
                    mode = topBarMode,
                    selection = state.selection,
                    selectedNoteCount = state.selectedNotes.size,
                    crumbs = state.files.crumbs,
                    vaultName = state.vaultName,
                    isGridMode = state.isGridMode,
                    searchBarState = search.bar,
                    inputField = inputField,
                    rootScrollBehavior = rootScrollBehavior,
                    folderScrollBehavior = folderScrollBehavior,
                    onMenuClick = actions.browse.onMenuClick,
                    onAvatarClick = actions.browse.onSettingsClick,
                    onNavigateUp = actions.browse.onNavigateUp,
                    onToggleLayout = actions.browse.onToggleLayout,
                    onSortClick = { sortSheetVisible = true },
                    selectionActions = actions.selection,
                )
            },
            floatingActionButton = {
                if (topBarMode != HomeTopBarMode.Selection) {
                    HomeActionMenu(
                        isCreating = state.isCreating,
                        onCreateFolderClick = actions.browse.onCreateFolderClick,
                        onCreateNoteClick = actions.browse.onCreateNoteClick,
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = actions.browse.onRefresh,
                state = pullToRefreshState,
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullToRefreshState,
                        isRefreshing = state.isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
                HomeContent(
                    loadingFeedbackEnabled = loadingFeedbackEnabled,
                    state = state,
                    actions = actions.browse,
                    onRecentRevealed = onRecentRevealed,
                    gridState = gridState,
                )
            }
        }

        if (topBarMode == HomeTopBarMode.Root) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(LoomaTokens.colors.page),
            )
        }
    }

    HomeSearchOverlay(
        searchState = state.search,
        isGridMode = state.isGridMode,
        actions = actions.search,
        search = search,
        inputField = inputField,
        homeVisible = homeVisible,
    )

    if (sortSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { sortSheetVisible = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
            containerColor = LoomaTokens.colors.sheet,
        ) {
            HomeSortSheet(
                current = state.sort,
                onSelect = { sort ->
                    actions.browse.onSortSelect(sort)
                    sortSheetVisible = false
                },
            )
        }
    }

    HomeFileDialogs(
        dialog = state.dialog,
        isActionRunning = state.isActionRunning,
        actions = actions.dialog,
    )
}

@Composable
private fun homeNoticeText(notice: HomeNotice): String = when (notice) {
    is HomeNotice.Deleted -> stringResource(R.string.home_snackbar_deleted, notice.count)
    is HomeNotice.Restored -> stringResource(R.string.home_snackbar_restored, notice.count)
    is HomeNotice.Moved -> stringResource(R.string.home_snackbar_moved, notice.count)
    is HomeNotice.MovePartial -> stringResource(R.string.home_move_partial, notice.succeeded, notice.failed)
    is HomeNotice.DeletePartial -> stringResource(R.string.home_delete_partial, notice.succeeded, notice.failed)
    is HomeNotice.UndoPartial -> stringResource(R.string.home_undo_partial, notice.succeeded, notice.failed)
    is HomeNotice.Renamed -> when {
        notice.linksFailed > 0 -> stringResource(R.string.home_snackbar_renamed_failed, notice.linksFailed)
        notice.linksUpdated > 0 -> stringResource(R.string.home_snackbar_renamed_links, notice.linksUpdated)
        else -> stringResource(R.string.home_snackbar_renamed)
    }
    HomeNotice.FolderCreated -> stringResource(R.string.home_snackbar_folder_created)
    HomeNotice.FolderRenamed -> stringResource(R.string.home_folder_snackbar_renamed)
    HomeNotice.FolderMoved -> stringResource(R.string.home_folder_snackbar_moved)
    is HomeNotice.FolderDeleted -> if (notice.notes > 0) {
        pluralStringResource(R.plurals.home_folder_snackbar_deleted, notice.notes, notice.notes)
    } else {
        stringResource(R.string.home_folder_snackbar_deleted_empty)
    }
    is HomeNotice.FolderKept -> when {
        notice.failed > 0 -> stringResource(R.string.home_folder_kept_partial, notice.trashed, notice.failed)
        notice.trashed > 0 -> pluralStringResource(R.plurals.home_folder_kept_trashed, notice.trashed, notice.trashed)
        else -> stringResource(R.string.home_folder_kept_no_notes)
    }
    HomeNotice.NameTaken -> stringResource(R.string.home_error_duplicate_name)
    HomeNotice.ActionFailed -> stringResource(R.string.home_error_action)
    HomeNotice.InvalidMoveTarget -> stringResource(R.string.home_move_invalid_target)
    HomeNotice.Undone -> stringResource(R.string.action_undone)
    HomeNotice.UndoFailed -> stringResource(R.string.action_undo_failed)
    HomeNotice.StalledRelocationUnfinished -> stringResource(R.string.home_stalled_relocation_unfinished)
    HomeNotice.StalledRelocationCompleted -> stringResource(R.string.home_stalled_relocation_completed)
    HomeNotice.StalledRelocationAbandoned -> stringResource(R.string.home_stalled_relocation_abandoned)
}

@Composable
private fun HomeSortSheet(current: HomeSort, onSelect: (HomeSort) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = stringResource(R.string.home_sort_sheet_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            HomeSortOption(
                label = stringResource(R.string.home_sort_last_modified),
                selected = current == HomeSort.LAST_MODIFIED,
                index = 0,
                count = 3,
                onClick = { onSelect(HomeSort.LAST_MODIFIED) },
            )
            HomeSortOption(
                label = stringResource(R.string.home_sort_title),
                selected = current == HomeSort.TITLE,
                index = 1,
                count = 3,
                onClick = { onSelect(HomeSort.TITLE) },
            )
            HomeSortOption(
                label = stringResource(R.string.home_sort_title_descending),
                selected = current == HomeSort.TITLE_DESCENDING,
                index = 2,
                count = 3,
                onClick = { onSelect(HomeSort.TITLE_DESCENDING) },
            )
        }
    }
}

@Composable
private fun HomeSortOption(
    label: String,
    selected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        checked = selected,
        onCheckedChange = { onClick() },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = LoomaTokens.colors.card,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        leadingContent = if (selected) {
            {
                LoomaIcon(
                    iconRes = R.drawable.ic_check,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            null
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    actions: HomeBrowseActions,
    onRecentRevealed: (RecentReveal) -> Unit,
    loadingFeedbackEnabled: Boolean,
    gridState: LazyStaggeredGridState,
) {
    Column(Modifier.fillMaxSize()) {
        val stalled = state.stalledRelocation
        val error = state.error
        val syncBanner = state.syncBanner
        if (stalled != null || error != null || syncBanner != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = noteGridMargin(), vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                stalled?.let {
                    StalledRelocationBanner(
                        stalled = it,
                        isActionRunning = state.isActionRunning,
                        onAbandonClick = actions.onAbandonStalledRelocationClick,
                    )
                }
                error?.let { HomeErrorBanner(error = it, onRetryClick = actions.onRetryClick) }
                syncBanner?.let {
                    HomeSyncBannerView(
                        banner = it,
                        onViewClick = actions.onOpenSyncSettings,
                        onDismissClick = actions.onDismissSyncBanner,
                    )
                }
            }
        }
        when {
            state.isLoading && state.notes.isEmpty() -> LoadingPage(loading = loadingFeedbackEnabled)

            state.files.isAtRoot && state.files.isEmpty -> MessagePage(
                title = stringResource(R.string.home_empty_vault),
            )

            else -> key(state.files.path) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (state.isSelecting) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = actions.onBackgroundClick,
                                )
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    HomeFilesGrid(
                        files = state.files,
                        isGridMode = state.isGridMode,
                        selection = state.selection,
                        recentReveal = state.recentReveal,
                        onRecentRevealed = onRecentRevealed,
                        onFolderClick = actions.onFolderClick,
                        onFolderLongClick = actions.onFolderLongClick,
                        onNoteClick = actions.onNoteClick,
                        onNoteLongClick = actions.onNoteLongClick,
                        gridState = gridState,
                    )
                }
            }
        }
    }
}

@Composable
private fun StalledRelocationBanner(
    stalled: StalledRelocation,
    isActionRunning: Boolean,
    onAbandonClick: () -> Unit,
) {
    ErrorBanner(
        message = stringResource(
            R.string.home_stalled_relocation_banner,
            stalled.sourcePath,
            stalled.destinationPath,
        ),
        actionLabel = stringResource(R.string.home_stalled_relocation_abandon),
        actionEnabled = !isActionRunning,
        onAction = onAbandonClick,
    )
}

@Composable
private fun HomeErrorBanner(error: HomeError, onRetryClick: () -> Unit) {
    val message = when (error) {
        is HomeError.UnreadableNotes -> stringResource(R.string.home_error_unreadable, error.count)
        HomeError.LoadFailed -> stringResource(R.string.home_error_load)
        HomeError.CreateFailed -> stringResource(R.string.home_error_create)
    }
    ErrorBanner(
        message = message,
        actionLabel = stringResource(R.string.home_retry),
        onAction = onRetryClick,
    )
}

@Composable
private fun HomeSyncBannerView(
    banner: HomeSyncBanner,
    onViewClick: () -> Unit,
    onDismissClick: () -> Unit,
) {
    when (banner) {
        is HomeSyncBanner.Syncing -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = LoomaTokens.colors.card,
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_cloud_sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = if (banner.total > 0) {
                                stringResource(
                                    R.string.home_sync_running_progress,
                                    banner.completed,
                                    banner.total,
                                )
                            } else {
                                stringResource(R.string.home_sync_running)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onViewClick,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.home_sync_view))
                        }
                    }
                    if (banner.total > 0) {
                        LinearWavyProgressIndicator(
                            progress = { (banner.completed.toFloat() / banner.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                        )
                    } else {
                        LinearWavyProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                        )
                    }
                }
            }
        }
        is HomeSyncBanner.Failed -> {
            ErrorBanner(
                message = stringResource(R.string.home_sync_failed),
                actionLabel = stringResource(R.string.home_sync_view),
                onAction = {
                    onDismissClick()
                    onViewClick()
                },
            )
        }
        is HomeSyncBanner.Conflicts -> {
            ErrorBanner(
                message = stringResource(R.string.home_sync_conflicts, banner.count),
                actionLabel = stringResource(R.string.home_sync_view),
                onAction = {
                    onDismissClick()
                    onViewClick()
                },
            )
        }
    }
}

@Composable
private fun HomeActionMenu(
    isCreating: Boolean,
    onCreateFolderClick: () -> Unit,
    onCreateNoteClick: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val menuOpen = expanded && !isCreating
    val showProgress = rememberDelayedVisibility(isCreating)
    // Scaffold already keeps 16dp from the edge; cancel the menu's own 16dp padding.
    val layoutDirection = LocalLayoutDirection.current
    val edgeOffset = if (layoutDirection == LayoutDirection.Rtl) (-16).dp else 16.dp

    FloatingActionButtonMenu(
        expanded = menuOpen,
        modifier = Modifier.offset(x = edgeOffset),
        button = {
            ToggleFloatingActionButton(
                checked = menuOpen,
                onCheckedChange = { open -> if (!isCreating) expanded = open },
                containerSize = ToggleFloatingActionButtonDefaults.containerSizeMedium(),
                containerCornerRadius = ToggleFloatingActionButtonDefaults.containerCornerRadiusMedium(),
            ) {
                if (showProgress) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = LocalContentColor.current,
                    )
                } else {
                    val open = checkedProgress > 0.5f
                    LoomaIcon(
                        iconRes = if (open) R.drawable.ic_close else R.drawable.ic_add,
                        contentDescription = stringResource(
                            if (open) R.string.home_close_menu_description
                            else R.string.home_create_menu_description,
                        ),
                        modifier = with(ToggleFloatingActionButtonDefaults) {
                            Modifier.animateIcon(
                                { checkedProgress },
                                size = iconSize(initialSize = 36.dp, finalSize = 24.dp),
                            )
                        },
                    )
                }
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onCreateFolderClick()
            },
            text = { Text(stringResource(R.string.home_new_folder_description)) },
            icon = { LoomaIcon(R.drawable.ic_folder_plus, contentDescription = null) },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onCreateNoteClick()
            },
            text = { Text(stringResource(R.string.home_create_note_description)) },
            icon = { LoomaIcon(R.drawable.ic_add, contentDescription = null) },
        )
    }
}
