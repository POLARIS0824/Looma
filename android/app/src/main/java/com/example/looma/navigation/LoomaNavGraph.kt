package com.example.looma.navigation

import androidx.activity.compose.BackHandler
import com.example.looma.LoomaApp
import com.example.looma.feature.ask.AskScreen
import com.example.looma.feature.ask.AskViewModel
import com.example.looma.feature.note.components.WeaveAction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
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
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import com.example.looma.feature.home.HomeRoute
import com.example.looma.feature.home.HomeViewModel
import com.example.looma.feature.models.ApiConnectionEditRoute
import com.example.looma.feature.models.ApiConnectionEditViewModel
import com.example.looma.feature.models.ApiConnectionsRoute
import com.example.looma.feature.models.LocalModelsRoute
import com.example.looma.feature.models.ModelSettingsRoute
import com.example.looma.feature.models.ModelSettingsViewModel
import com.example.looma.feature.note.NoteRoute
import com.example.looma.feature.note.NoteViewModel
import com.example.looma.feature.note.titleEditFor
import com.example.looma.feature.settings.SemanticSearchSettingsRoute
import com.example.looma.feature.settings.SemanticSearchSettingsViewModel
import com.example.looma.feature.settings.SettingsRoute
import com.example.looma.feature.settings.SettingsViewModel
import com.example.looma.feature.settings.SyncSettingsRoute
import com.example.looma.feature.settings.SyncSettingsViewModel
import com.example.looma.feature.trash.TrashRoute
import com.example.looma.feature.trash.TrashViewModel
import com.example.looma.feature.vault.VaultSelection
import com.example.looma.feature.vault.VaultSetupRoute
import com.example.looma.feature.vault.VaultSetupViewModel
import com.example.looma.feature.vault.VaultState
import com.example.looma.ui.components.LoadingPage
import com.example.looma.ui.components.isCompactWindow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val LocalSceneBackState = compositionLocalOf<NavigationEventState<SceneInfo<NavKey>>> {
    error("Missing scene back state")
}

@Composable
internal fun LoomaNavGraph(
    app: LoomaApp,
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
        LoadingPage(modifier = modifier, loading = launchGate.isReleased)
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

    val railState = rememberWideNavigationRailState()
    val compact = isCompactWindow()
    val scope = rememberCoroutineScope()
    val current = backStack.lastOrNull()
    LaunchedEffect(status, current) {
        if (status is VaultState.Ready && current != LoomaRoute.Home) {
            launchGate.release()
        }
    }
    val destination = current.toDestination()
    val vault = (status as? VaultState.Ready)?.vault
    val modalRailOpen = compact && railState.targetValue == WideNavigationRailValue.Expanded
    LaunchedEffect(compact) {
        if (compact) railState.snapTo(WideNavigationRailValue.Collapsed)
    }

    BackHandler(enabled = modalRailOpen) {
        scope.launch { railState.collapse() }
    }

    LoomaNavigationRail(
        state = railState,
        compact = compact,
        current = destination,
        vaultName = vault?.name.orEmpty(),
        onSelect = { target ->
            if (compact) scope.launch { railState.collapse() }
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
                    val homeViewModel: HomeViewModel = viewModel {
                        HomeViewModel(
                            noteRepository = app.noteRepository,
                            folderRepository = app.folderRepository,
                            searchHistoryRepository = app.searchHistoryRepository,
                            semanticSearchRepository = app.semanticSearchRepository,
                            syncRepository = app.syncRepository,
                        )
                    }
                    LaunchedEffect(homeViewModel) {
                        homeViewModel.uiState.first { !it.isLoading }
                        launchGate.release()
                    }
                    HomeRoute(
                        viewModel = homeViewModel,
                        loadingFeedbackEnabled = launchGate.isReleased,
                        onOpenNote = { noteId -> backStack.add(LoomaRoute.Note.from(noteId)) },
                        onOpenSearchResult = { result ->
                            backStack.add(LoomaRoute.Note.from(result.id, passage = result.passage))
                        },
                        onCreatedNote = { noteId ->
                            backStack.add(LoomaRoute.Note.from(noteId, isNew = true))
                        },
                        onMenuClick = { scope.launch { railState.toggle() } },
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
                        onOpenSyncSettings = {
                            if (backStack.lastOrNull() == LoomaRoute.Home) {
                                backStack.add(LoomaRoute.SyncSettings)
                            }
                        },
                    )
                }

                entry<LoomaRoute.Ask>(clazzContentKey = { "ask/${vault?.id?.value.orEmpty()}" }) {
                    val active = vault ?: return@entry
                    val model = viewModel<AskViewModel> {
                        AskViewModel(
                            vault = active,
                            executor = app.agentRuntimeExecutor,
                            tools = app.askToolsProvider,
                            models = app.answerModels,
                            notes = app.noteRepository,
                            settings = app.settingsRepository,
                            history = app.askHistoryRepository,
                            skills = app.skillCatalog,
                        )
                    }
                    AskScreen(
                        model = model,
                        navigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                        openNote = { backStack.add(LoomaRoute.Note.from(it)) },
                        manageModels = {
                            if (backStack.lastOrNull() == LoomaRoute.Ask) backStack.add(LoomaRoute.ModelSettings)
                        },
                    )
                }

                entry<LoomaRoute.Trash> {
                    val trashViewModel: TrashViewModel = viewModel {
                        TrashViewModel(app.noteRepository)
                    }
                    TrashRoute(
                        viewModel = trashViewModel,
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }

                entry<LoomaRoute.Settings> {
                    val settingsViewModel: SettingsViewModel = viewModel {
                        SettingsViewModel(app.settingsRepository, app.syncRepository)
                    }
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
                        onSyncClick = {
                            if (backStack.lastOrNull() == LoomaRoute.Settings) {
                                backStack.add(LoomaRoute.SyncSettings)
                            }
                        },
                        onModelsClick = {
                            if (backStack.lastOrNull() == LoomaRoute.Settings) {
                                backStack.add(LoomaRoute.ModelSettings)
                            }
                        },
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }

                entry<LoomaRoute.SemanticSearchSettings> {
                    val semanticSettingsViewModel: SemanticSearchSettingsViewModel = viewModel {
                        SemanticSearchSettingsViewModel(app.semanticSearchRepository)
                    }
                    SemanticSearchSettingsRoute(
                        viewModel = semanticSettingsViewModel,
                        vaultName = vault?.name.orEmpty(),
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }

                entry<LoomaRoute.SyncSettings> {
                    val syncSettingsViewModel: SyncSettingsViewModel = viewModel {
                        SyncSettingsViewModel(app.syncRepository, app.settingsRepository)
                    }
                    SyncSettingsRoute(
                        viewModel = syncSettingsViewModel,
                        vaultName = vault?.name.orEmpty(),
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }

                entry<LoomaRoute.ModelSettings> {
                    val modelViewModel: ModelSettingsViewModel = viewModel {
                        ModelSettingsViewModel(app.modelSettingsRepository)
                    }
                    ModelSettingsRoute(
                        viewModel = modelViewModel,
                        onOpenLocal = {
                            if (backStack.lastOrNull() == LoomaRoute.ModelSettings) backStack.add(LoomaRoute.LocalModels)
                        },
                        onOpenApi = {
                            if (backStack.lastOrNull() == LoomaRoute.ModelSettings) backStack.add(LoomaRoute.ApiConnections)
                        },
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }

                entry<LoomaRoute.LocalModels> {
                    val modelViewModel: ModelSettingsViewModel = viewModel {
                        ModelSettingsViewModel(app.modelSettingsRepository)
                    }
                    LocalModelsRoute(
                        viewModel = modelViewModel,
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }

                entry<LoomaRoute.ApiConnections> {
                    val modelViewModel: ModelSettingsViewModel = viewModel {
                        ModelSettingsViewModel(app.modelSettingsRepository)
                    }
                    ApiConnectionsRoute(
                        viewModel = modelViewModel,
                        onEdit = { id ->
                            if (backStack.lastOrNull() == LoomaRoute.ApiConnections) {
                                backStack.add(LoomaRoute.ApiConnectionEdit(id))
                            }
                        },
                        onNavigateUp = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }

                entry<LoomaRoute.ApiConnectionEdit> { route ->
                    val editViewModel: ApiConnectionEditViewModel = viewModel {
                        ApiConnectionEditViewModel(app.modelSettingsRepository, route.connectionId)
                    }
                    ApiConnectionEditRoute(
                        viewModel = editViewModel,
                        onDuplicated = { id ->
                            if (backStack.lastOrNull() == route) {
                                backStack[backStack.lastIndex] = LoomaRoute.ApiConnectionEdit(id)
                            }
                        },
                        onNavigateUp = {
                            if (backStack.lastOrNull() == route && backStack.size > 1) backStack.removeLastOrNull()
                        },
                    )
                }

                entry<LoomaRoute.Note>(clazzContentKey = { it.visitId }) { route ->
                    val noteId = NoteId(
                        vaultId = VaultId(route.vaultId),
                        path = NotePath(route.relativePath),
                    )
                    val noteViewModel: NoteViewModel = viewModel(
                        factory = NoteViewModel.factory(
                            repository = app.noteRepository,
                            settingsRepository = app.settingsRepository,
                            imageUploader = app.imageUploader,
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
                            enabled = backStack.size > 1 && !modalRailOpen,
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
                        weaveAction = { focus ->
                            if (vault != null) WeaveAction(
                                vault, noteId, app.evidence, noteViewModel, focus,
                                open = { backStack.add(LoomaRoute.Note.from(it.id, passage = it.anchor)) },
                                onOpenSemanticSettings = {
                                    if (backStack.lastOrNull() != LoomaRoute.SemanticSearchSettings) {
                                        backStack.add(LoomaRoute.SemanticSearchSettings)
                                    }
                                },
                            )
                        },
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
                isBackEnabled = backStack.size > 1 && !modalRailOpen,
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
    LoomaRoute.Ask -> LoomaDestination.Ask
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
        LoomaDestination.Ask,
        LoomaDestination.Trash,
        LoomaDestination.Settings,
        -> {
            val route = when (target) {
                LoomaDestination.Ask -> LoomaRoute.Ask
                LoomaDestination.Trash -> LoomaRoute.Trash
                else -> LoomaRoute.Settings
            }
            if (backStack.lastOrNull() == route) return
            backStack.clear()
            backStack.add(LoomaRoute.Home)
            backStack.add(route)
        }
    }
}
