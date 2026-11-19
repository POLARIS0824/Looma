package com.example.looma.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneInfo
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberNavigationEventState
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState.InProgress
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.NavigationEventState
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.looma.LaunchGate
import com.example.looma.data.folder.FolderRepository
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.image.ImageUploader
import com.example.looma.data.search.SearchHistoryRepository
import com.example.looma.data.search.SemanticSearchRepository
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import com.example.looma.feature.home.HomeRoute
import com.example.looma.feature.home.HomeViewModel
import com.example.looma.feature.note.NoteRoute
import com.example.looma.feature.note.NoteViewModel
import com.example.looma.feature.note.titleEditFor
import com.example.looma.feature.settings.SemanticSearchSettingsRoute
import com.example.looma.feature.settings.SemanticSearchSettingsViewModel
import com.example.looma.feature.settings.SettingsRoute
import com.example.looma.feature.settings.SettingsViewModel
import com.example.looma.feature.trash.TrashRoute
import com.example.looma.feature.trash.TrashViewModel
import com.example.looma.feature.vault.VaultSelection
import com.example.looma.feature.vault.VaultSetupRoute
import com.example.looma.feature.vault.VaultSetupViewModel
import com.example.looma.feature.vault.VaultState
import com.example.looma.ui.components.DelayedLoadingIndicator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val LocalSceneBackState = compositionLocalOf<NavigationEventState<SceneInfo<NavKey>>> {
    error("Missing scene back state")
}

@Composable
fun LoomaNavGraph(
    noteRepository: NoteRepository,
    folderRepository: FolderRepository,
    searchHistoryRepository: SearchHistoryRepository,
    semanticSearchRepository: SemanticSearchRepository,
    settingsRepository: SettingsRepository,
    imageUploader: ImageUploader,
    vaultSetupViewModel: VaultSetupViewModel,
    launchGate: LaunchGate,
    modifier: Modifier = Modifier,
) {
    val setupState by vaultSetupViewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vaultSetupViewModel.refreshVaultStatus() }
    val status = setupState.vaultState

    LaunchedEffect(status) {
        if (status !is VaultState.Checking && status !is VaultState.Ready) {
            launchGate.release()
        }
    }

    if (status is VaultState.Checking) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DelayedLoadingIndicator(loading = launchGate.isReleased, size = 44.dp)
        }
        return
    }

    val start: NavKey = if (status is VaultState.Ready) LoomaRoute.Home else LoomaRoute.VaultSetup
    val backStack = rememberNavBackStack(start)
    val reset = setupState.vaultStackReset(backStack)

    LaunchedEffect(setupState.selection as? VaultSelection.Activated, reset) {
        val latest = vaultSetupViewModel.uiState.value
        val root = latest.vaultStackReset(backStack) ?: return@LaunchedEffect
        backStack.clear()
        backStack.add(root)
        if (root == LoomaRoute.Home) {
            (latest.selection as? VaultSelection.Activated)?.let(vaultSetupViewModel::onActivationHandled)
        }
    }
    if (reset == LoomaRoute.VaultSetup) return

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var homeDrawerGestureEnabled by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val current = backStack.lastOrNull()
    LaunchedEffect(status, current) {
        if (status is VaultState.Ready && current != LoomaRoute.Home) {
            launchGate.release()
        }
    }
    val destination = current.toDestination()
    val vault = (status as? VaultState.Ready)?.vault

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    LoomaDrawer(
        drawerState = drawerState,
        current = destination,
        vaultName = vault?.name.orEmpty(),
        gesturesEnabled = destination != null && (destination != LoomaDestination.Notes || homeDrawerGestureEnabled),
        onSelect = { target ->
            scope.launch { drawerState.close() }
            selectDrawerDestination(backStack, target)
        },
        modifier = modifier.fillMaxSize(),
    ) {
        val entries = rememberDecoratedNavEntries(
            backStack = backStack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),

            entryProvider = entryProvider {

                entry<LoomaRoute.VaultSetup> {
                    VaultSetupRoute(
                        viewModel = vaultSetupViewModel,
                        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    )
                }

                entry<LoomaRoute.Home>(clazzContentKey = { "home/${vault?.id?.value.orEmpty()}" }) {
                    val homeViewModel: HomeViewModel = viewModel(
                        factory = HomeViewModel.factory(
                            noteRepository = noteRepository,
                            folderRepository = folderRepository,
                            searchHistoryRepository = searchHistoryRepository,
                            semanticSearchRepository = semanticSearchRepository,
                        ),
                    )
                    LaunchedEffect(homeViewModel) {
                        homeViewModel.uiState.first { !it.isLoading }
                        launchGate.release()
                    }
                    HomeRoute(
                        viewModel = homeViewModel,
                        loadingFeedbackEnabled = launchGate.isReleased,
                        onDrawerGestureEnabledChange = { homeDrawerGestureEnabled = it },
                        onOpenNote = { noteId -> backStack.add(LoomaRoute.Note.from(noteId)) },
                        onOpenSearchResult = { result ->
                            backStack.add(LoomaRoute.Note.from(result.id, passage = result.passage))
                        },
                        onCreatedNote = { noteId ->
                            backStack.add(LoomaRoute.Note.from(noteId, isNew = true))
                        },
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onSettingsClick = {
                            if (backStack.lastOrNull() != LoomaRoute.Settings) {
                                backStack.add(LoomaRoute.Settings)
                            }
                        },
                        onManageSemanticSearch = {
                            if (backStack.lastOrNull() == LoomaRoute.Home) {
                                backStack.add(LoomaRoute.SemanticSearchSettings)
                            }
                        },
                    )
                }

                entry<LoomaRoute.Trash> {
                    val trashViewModel: TrashViewModel = viewModel(
                        factory = TrashViewModel.factory(noteRepository),
                    )
                    TrashRoute(
                        viewModel = trashViewModel,
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }

                entry<LoomaRoute.Settings> {
                    val settingsViewModel: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.factory(settingsRepository),
                    )
                    SettingsRoute(
                        vaultViewModel = vaultSetupViewModel,
                        settingsViewModel = settingsViewModel,
                        onChangeVaultClick = {
                            if (backStack.lastOrNull() != LoomaRoute.VaultSetup) {
                                backStack.add(LoomaRoute.VaultSetup)
                            }
                        },
                        onSemanticSearchClick = {
                            if (backStack.lastOrNull() == LoomaRoute.Settings) {
                                backStack.add(LoomaRoute.SemanticSearchSettings)
                            }
                        },
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }

                entry<LoomaRoute.SemanticSearchSettings> {
                    val semanticSettingsViewModel: SemanticSearchSettingsViewModel = viewModel(
                        factory = SemanticSearchSettingsViewModel.factory(semanticSearchRepository),
                    )
                    SemanticSearchSettingsRoute(
                        viewModel = semanticSettingsViewModel,
                        vaultName = vault?.name.orEmpty(),
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }

                entry<LoomaRoute.Note>(clazzContentKey = { it.visitId }) { route ->
                    val noteId = NoteId(
                        vaultId = VaultId(route.vaultId),
                        path = NotePath(route.relativePath),
                    )
                    val noteViewModel: NoteViewModel = viewModel(
                        factory = NoteViewModel.factory(
                            repository = noteRepository,
                            settingsRepository = settingsRepository,
                            imageUploader = imageUploader,
                            initialId = noteId,
                            isNewNote = route.isNew,
                        ),
                    )
                    val titleBuffer = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState() }
                    val titleFocused = remember { mutableStateOf(false) }
                    val isActive = {
                        (backStack.lastOrNull() as? LoomaRoute.Note)?.visitId == route.visitId
                    }
                    val navigateUp = {
                        if (isActive() && backStack.size > 1) {
                            backStack.removeLastOrNull()
                        }
                        Unit
                    }
                    if (isActive()) {
                        NoteBackHandler(
                            viewModel = noteViewModel,
                            titleBuffer = titleBuffer,
                            titleFocused = titleFocused,
                            enabled = backStack.size > 1 && !drawerState.isOpen,
                            vaultBusy = setupState.isBusy,
                            canLeave = { isActive() && !vaultSetupViewModel.uiState.value.isBusy },
                            onNavigateUp = navigateUp,
                        )
                    }
                    NoteRoute(
                        viewModel = noteViewModel,
                        titleBuffer = titleBuffer,
                        onTitleFocusChange = { titleFocused.value = it },
                        noteId = noteId,
                        retargetVersion = route.retargetVersion,
                        passage = route.passage,
                        onNavigateUp = navigateUp,
                        onOpenNote = { noteId -> backStack.add(LoomaRoute.Note.from(noteId)) },
                        onRenameNavigate = { from, to ->
                            backStack.retargetNoteVisits(from, to, route.visitId)
                        },
                    )
                }
            },
        )
        val onBack = {
            if (backStack.size > 1 &&
                backStack.lastOrNull() !is LoomaRoute.Note &&
                !vaultSetupViewModel.uiState.value.isBusy
            ) {
                backStack.removeLastOrNull()
            }
            Unit
        }
        val sceneState = rememberSceneState(
            entries = entries,
            sceneStrategies = listOf(SinglePaneSceneStrategy()),
            onBack = onBack,
        )
        val noteVisitId = (current as? LoomaRoute.Note)?.visitId
        val sceneBackState = key(noteVisitId) { rememberNavigationEventState(sceneState) }
        if (noteVisitId == null) {
            NavigationBackHandler(
                state = sceneBackState,
                isBackEnabled = backStack.size > 1 && !drawerState.isOpen,
                onBackCompleted = onBack,
            )
        }
        CompositionLocalProvider(LocalSceneBackState provides sceneBackState) {
            NavDisplay(
                sceneState = sceneState,
                navigationEventState = sceneBackState,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun NoteBackHandler(
    viewModel: NoteViewModel,
    titleBuffer: TextFieldState,
    titleFocused: State<Boolean>,
    enabled: Boolean,
    vaultBusy: Boolean,
    canLeave: () -> Boolean,
    onNavigateUp: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val sceneBackState = LocalSceneBackState.current
    val saveBackState = rememberNavigationEventState(NavigationEventInfo.None)
    val noteState by viewModel.uiState.collectAsStateWithLifecycle()
    val needsSave by remember(viewModel, noteState, titleBuffer, titleFocused) {
        derivedStateOf(structuralEqualityPolicy()) {
            viewModel.requiresSaveBeforeLeaving(noteState) ||
                (titleFocused.value && !noteState.isLoading && !noteState.loadFailed &&
                    titleEditFor(titleBuffer.text.toString(), noteState.fileName, lastCommitted = null)
                        .renameTo != null)
        }
    }
    val gestureState by remember(sceneBackState, saveBackState) {
        derivedStateOf(structuralEqualityPolicy()) {
            when {
                sceneBackState.transitionState is InProgress -> sceneBackState
                saveBackState.transitionState is InProgress -> saveBackState
                else -> null
            }
        }
    }
    val backState = gestureState ?: if (needsSave || vaultBusy) saveBackState else sceneBackState
    NavigationBackHandler(state = backState, isBackEnabled = enabled) {
        if (!canLeave()) return@NavigationBackHandler
        viewModel.requestLeave(
            clearFocus = { focusManager.clearFocus(force = true) },
            onAlreadySaved = onNavigateUp,
        )
    }
}

private fun NavKey?.toDestination(): LoomaDestination? = when (this) {
    LoomaRoute.Home -> LoomaDestination.Notes
    LoomaRoute.Trash -> LoomaDestination.Trash
    LoomaRoute.Settings -> LoomaDestination.Settings
    else -> null
}

internal fun selectDrawerDestination(backStack: MutableList<NavKey>, target: LoomaDestination) {
    when (target) {
        LoomaDestination.Notes -> {
            backStack.clear()
            backStack.add(LoomaRoute.Home)
        }
        LoomaDestination.Trash,
        LoomaDestination.Settings,
        -> {
            val route = if (target == LoomaDestination.Trash) LoomaRoute.Trash else LoomaRoute.Settings
            if (backStack.lastOrNull() == route) return
            backStack.clear()
            backStack.add(LoomaRoute.Home)
            backStack.add(route)
        }
    }
}
