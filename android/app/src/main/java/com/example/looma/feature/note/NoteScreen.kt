package com.example.looma.feature.note

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.note.resolveImageSource
import com.example.looma.domain.markdown.CHECKED
import com.example.looma.domain.markdown.MARKER_LENGTH
import com.example.looma.domain.markdown.MarkdownEditing
import com.example.looma.domain.markdown.PreparedMarkdown
import com.example.looma.domain.markdown.TextSelection
import com.example.looma.domain.markdown.UNCHECKED
import com.example.looma.domain.markdown.prepareMarkdown
import com.example.looma.domain.markdown.prepareMarkdownOpening
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.search.PassageAnchor
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.domain.wiki.WikiNote
import com.example.looma.feature.note.components.BacklinksSection
import com.example.looma.feature.note.components.NoteFormatBar
import com.example.looma.feature.note.components.NoteLeaveDialog
import com.example.looma.feature.note.components.NoteSaveBanner
import com.example.looma.feature.note.components.NoteTitleField
import com.example.looma.feature.note.components.NoteTopBar
import com.example.looma.feature.note.components.NoteTextField
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.compose.animation.EnterExitState
import com.example.looma.feature.note.components.WikiLinkPanel
import com.example.looma.feature.note.components.wikiReferenceFor
import com.example.looma.ui.components.LoadingPage
import com.example.looma.ui.components.MessagePage
import com.example.looma.ui.components.PageMaxWidth
import com.example.looma.ui.components.pageMargin
import com.example.looma.ui.markdown.LocalImageSourceResolver
import com.example.looma.ui.markdown.LocalImageSourceRetryResolver
import com.example.looma.ui.markdown.MarkdownBody
import com.example.looma.ui.theme.LoomaTheme
import com.example.looma.ui.theme.LoomaTokens
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

@Composable
fun NoteRoute(
    viewModel: NoteViewModel,
    titleBuffer: TextFieldState,
    noteId: NoteId,
    retargetVersion: Long,
    onTitleFocusChange: (Boolean) -> Unit,
    onNavigateUp: () -> Unit,
    onOpenNote: (NoteId) -> Unit,
    onRenameNavigate: (NoteId, NoteId) -> Unit,
    modifier: Modifier = Modifier,
    passage: PassageAnchor? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigateUp by rememberUpdatedState(onNavigateUp)
    val leaveReady = state.leave == NoteLeave.Ready
    LaunchedEffect(state.rename, leaveReady) {
        state.rename?.let { rename ->
            onRenameNavigate(rename.from, rename.to)
            viewModel.onRenameNavigated()
        }
        if (leaveReady && viewModel.consumeLeaveReady()) {
            navigateUp()
        }
    }
    val editorActive = LocalNavAnimatedContentScope.current.transition.targetState == EnterExitState.Visible
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::uploadImage) }
    val pickImage = {
        imagePicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.onRouteStarted(noteId, retargetVersion)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onEnterBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onEnterBackground() }

    when {
        state.isLoading -> NoteLoadingScreen(
            onNavigateUp = onNavigateUp,
            modifier = modifier,
        )

        state.loadFailed -> NoteLoadErrorScreen(
            onNavigateUp = onNavigateUp,
            onRetry = viewModel::load,
            modifier = modifier,
        )

        else -> {
            val imageContext = state.imageContext
            val resolver = remember(imageContext) {
                if (imageContext == null) {
                    { _: String -> null }
                } else {
                    { link: String ->
                        resolveImageSource(imageContext.vaultRoot, imageContext.noteFile, link)
                    }
                }
            }
            val retryResolver = remember(imageContext) {
                imageContext?.let { context ->
                    { link: String ->
                        resolveImageSource(context.vaultRoot, context.noteFile, link, refreshAttachments = true)
                    }
                }
            }
            CompositionLocalProvider(
                LocalImageSourceResolver provides resolver,
                LocalImageSourceRetryResolver provides retryResolver,
            ) {
                NoteWorkspace(
                    state = state,
                    contentBuffer = viewModel.editor,
                    titleBuffer = titleBuffer,
                    onTitleFocusChange = onTitleFocusChange,
                    onCommitTitle = viewModel::commitTitle,
                    onTitleErrorShown = viewModel::onTitleErrorShown,
                    onRenameNoticeShown = viewModel::onRenameNoticeShown,
                    onRequestLeave = { clearFocus -> viewModel.requestLeave(clearFocus) },
                    onRetrySave = viewModel::retrySave,
                    onOverwrite = viewModel::overwrite,
                    onReloadFromDisk = viewModel::reloadFromDisk,
                    onOpenNote = { id ->
                        if (viewModel.prepareToOpenLinkedNote()) onOpenNote(id)
                    },
                    onPickImage = pickImage,
                    onImageInserted = viewModel::onImageInserted,
                    onImageErrorShown = viewModel::onImageErrorShown,
                    editorActive = editorActive,
                    passage = passage,
                    matchesPassage = viewModel::matchesPassage,
                    modifier = modifier,
                )
            }
        }
    }

    if (state.leave == NoteLeave.Blocked) {
        NoteLeaveDialog(
            state = state,
            onRetry = { viewModel.saveThenLeave() },
            onOverwrite = { viewModel.saveThenLeave(force = true) },
            onDiscard = {
                viewModel.onLeaveHandled()
                onNavigateUp()
            },
            onDismiss = viewModel::onLeaveHandled,
        )
    }
}

@Composable
private fun NoteWorkspace(
    state: NoteUiState,
    contentBuffer: TextFieldState,
    titleBuffer: TextFieldState,
    onCommitTitle: (String) -> Unit,
    onTitleErrorShown: () -> Unit,
    onRenameNoticeShown: () -> Unit,
    onRequestLeave: (() -> Unit) -> Unit,
    onRetrySave: () -> Unit,
    onOverwrite: () -> Unit,
    onReloadFromDisk: () -> Unit,
    onOpenNote: (NoteId) -> Unit,
    onPickImage: () -> Unit,
    onImageInserted: () -> Unit,
    onImageErrorShown: () -> Unit,
    onTitleFocusChange: (Boolean) -> Unit = {},
    editorActive: Boolean = true,
    modifier: Modifier = Modifier,
    passage: PassageAnchor? = null,
    matchesPassage: (PassageAnchor) -> Boolean = { false },
) {
    val formatState by remember(contentBuffer) {
        derivedStateOf {
            val selection = contentBuffer.selection
            MarkdownEditing.formatState(
                text = contentBuffer.text,
                selection = TextSelection(selection.start, selection.end),
            )
        }
    }
    var mode by rememberSaveable {
        mutableStateOf(
            if (passage != null || contentBuffer.text.length >= LARGE_NOTE_READ_THRESHOLD) NoteMode.Read else NoteMode.Edit,
        )
    }
    var passageOpened by rememberSaveable(passage) { mutableStateOf(false) }
    var linkPanelOpen by rememberSaveable { mutableStateOf(false) }
    var initialTitleFocusTaken by rememberSaveable { mutableStateOf(false) }
    val readScroll = rememberLazyListState()
    val editScroll = rememberScrollState()
    val modeScroll = remember(contentBuffer, readScroll, editScroll) { NoteModeScroll(readScroll, editScroll) }
    var pendingModeScroll by remember(contentBuffer) { mutableStateOf<ModeScrollRequest?>(null) }
    var modeScrollReturn by remember(contentBuffer) { mutableStateOf<ModeScrollReturn?>(null) }
    val modeScrollObserver = remember(contentBuffer) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                    pendingModeScroll = null
                    modeScrollReturn = null
                }
                return Offset.Zero
            }
        }
    }
    val bodyFocus = remember { FocusRequester() }
    val titleFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val motion = MaterialTheme.motionScheme
    val modeEnter = fadeIn(motion.defaultEffectsSpec())
    val modeExit = fadeOut(motion.fastEffectsSpec())
    val appBarState = rememberTopAppBarState()
    val appBarScroll = TopAppBarDefaults.pinnedScrollBehavior(
        scrollableState = if (mode == NoteMode.Read) readScroll else editScroll,
        state = appBarState,
    )
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val leaveWorkspace = {
        onRequestLeave {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    val onWikiLinkClick: (String) -> Unit = { target ->
        val id = state.wiki.resolve(target)
        if (id != null) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            onOpenNote(id)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.note_link_missing, target))
            }
        }
    }

    var preview by remember(contentBuffer) { mutableStateOf<PreparedMarkdown?>(null) }
    LaunchedEffect(contentBuffer, editorActive) {
        if (!editorActive) return@LaunchedEffect
        snapshotFlow { contentBuffer.text.toString() }.collectLatest { source ->
            if (preview?.source == source && preview !is PreparedMarkdown.Opening) return@collectLatest
            if (mode == NoteMode.Edit) {
                withTimeoutOrNull(READ_PREVIEW_IDLE_MILLIS) {
                    snapshotFlow { mode }.first { it == NoteMode.Read }
                }
            }
            if (source.length >= LARGE_NOTE_READ_THRESHOLD && preview?.source != source) {
                prepareMarkdownOpening(source)?.let { preview = it }
            }
            preview = prepareMarkdown(source)
        }
    }

    LaunchedEffect(mode) {
        appBarState.contentOffset = 0f
        if (mode == NoteMode.Read) {
            linkPanelOpen = false
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    LaunchedEffect(passageOpened, preview, mode, state.showInlineTitle, editorActive) {
        if (passage == null || passageOpened || !editorActive) return@LaunchedEffect
        if (!matchesPassage(passage)) {
            passageOpened = true
            scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.note_passage_changed)) }
            return@LaunchedEffect
        }
        val reading = (preview as? PreparedMarkdown.Ready)?.takeIf { it.source == contentBuffer.text.toString() }
            ?: return@LaunchedEffect
        if (mode == NoteMode.Read) {
            modeScroll.openPassage(passage.start, reading.blocks, reading.index.bodyStart, state.showInlineTitle)
        }
        passageOpened = true
    }

    val request = pendingModeScroll
    val readRequest = request is ModeScrollRequest.Read || request is ModeScrollRequest.ReadOffset
    val requestedPreview = if (readRequest) preview else null
    val editRestoreSource = when (request) {
        is ModeScrollRequest.Edit, is ModeScrollRequest.EditOffset -> contentBuffer.text.toString()
        else -> null
    }
    LaunchedEffect(request, requestedPreview, editRestoreSource, state.showInlineTitle) {
        val currentPreview = (requestedPreview as? PreparedMarkdown.Ready)?.takeIf {
            it.source == contentBuffer.text.toString()
        }
        when (request) {
            is ModeScrollRequest.Read -> {
                currentPreview ?: return@LaunchedEffect
                modeScroll.restoreRead(
                    position = request.position,
                    blocks = currentPreview.blocks,
                    bodyStart = currentPreview.index.bodyStart,
                    hasHeader = state.showInlineTitle,
                )
            }
            is ModeScrollRequest.ReadOffset -> {
                currentPreview ?: return@LaunchedEffect
                readScroll.scrollToItem(request.itemIndex, request.offset)
            }
            is ModeScrollRequest.Edit -> modeScroll.restoreEdit(request.anchor, editRestoreSource ?: return@LaunchedEffect)
            is ModeScrollRequest.EditOffset -> modeScroll.restoreEdit(
                request.offset,
                editRestoreSource ?: return@LaunchedEffect,
            )
            null -> return@LaunchedEffect
        }
        if (pendingModeScroll === request) pendingModeScroll = null
    }

    LaunchedEffect(state.imageInsertion) {
        val insertion = state.imageInsertion ?: return@LaunchedEffect
        insertSnippet(contentBuffer, insertion.snippet)
        onImageInserted()
        bodyFocus.requestFocus()
    }

    LaunchedEffect(state.imageUploadError) {
        val error = state.imageUploadError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(imageErrorText(resources, error))
        onImageErrorShown()
    }

    LaunchedEffect(state.titleError) {
        val error = state.titleError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(titleErrorText(resources, error))
        onTitleErrorShown()
    }

    LaunchedEffect(state.renameNotice) {
        val notice = state.renameNotice ?: return@LaunchedEffect
        val text = if (notice.linksFailed > 0) {
            resources.getString(R.string.editor_rename_links_failed, notice.linksFailed)
        } else {
            resources.getString(R.string.editor_rename_links, notice.linksUpdated)
        }
        snackbarHostState.showSnackbar(text)
        onRenameNoticeShown()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(appBarScroll.nestedScrollConnection),
        containerColor = LoomaTokens.colors.noteCanvas,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.ime.exclude(ScaffoldDefaults.contentWindowInsets),
                ),
            )
        },
        topBar = {
            NoteTopBar(
                mode = mode,
                onNavigateUp = leaveWorkspace,
                onToggleMode = {
                    val next = if (mode == NoteMode.Read) NoteMode.Edit else NoteMode.Read
                    if (next == NoteMode.Read) {
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                    }
                    val text = contentBuffer.text.toString()
                    val returnPosition = modeScrollReturn?.takeIf { it.source == text }?.position
                    pendingModeScroll = returnPosition ?: run {
                        when (mode) {
                            NoteMode.Read -> preview?.visibleBlocks()?.let {
                                modeScroll.captureRead(
                                    blocks = it.blocks,
                                    bodyStart = it.index.bodyStart,
                                    hasHeader = state.showInlineTitle,
                                )?.let { anchor -> ModeScrollRequest.Edit(anchor) }
                            }
                            NoteMode.Edit -> modeScroll.captureEdit(text)
                                ?.let { position -> ModeScrollRequest.Read(position) }
                        }
                    }
                    modeScrollReturn = if (returnPosition == null) {
                        val position = when (mode) {
                            NoteMode.Read -> ModeScrollRequest.ReadOffset(
                                readScroll.firstVisibleItemIndex,
                                readScroll.firstVisibleItemScrollOffset,
                            )
                            NoteMode.Edit -> ModeScrollRequest.EditOffset(editScroll.value)
                        }
                        ModeScrollReturn(text, position)
                    } else {
                        null
                    }
                    if (next == NoteMode.Read && preview?.source != text) preview = null
                    mode = next
                },
                scrollBehavior = appBarScroll,
                title = {
                    if (!state.showInlineTitle) {
                        if (mode == NoteMode.Edit) {
                            NoteTitleField(
                                buffer = titleBuffer,
                                fileName = state.fileName,
                                isNewNote = state.isNewNote,
                                isRenaming = state.isRenaming,
                                textStyle = MaterialTheme.typography.titleMediumEmphasized,
                                focusRequester = titleFocus,
                                onCommit = onCommitTitle,
                                onFocusChange = onTitleFocusChange,
                                requestInitialFocus = state.isNewNote && !initialTitleFocusTaken,
                                onInitialFocusShown = { initialTitleFocusTaken = true },
                                error = state.titleError,
                                onDone = { bodyFocus.requestFocus() },
                            )
                        } else {
                            Text(
                                text = state.fileName,
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NoteSaveBanner(
                state = state,
                onRetry = onRetrySave,
                onReload = onReloadFromDisk,
                onOverwrite = onOverwrite,
            )

            AnimatedContent(
                targetState = mode,
                modifier = Modifier.weight(1f).nestedScroll(modeScrollObserver),
                transitionSpec = { modeEnter togetherWith modeExit using null },
                label = "note-mode",
            ) { shownMode ->
                when (shownMode) {
                    NoteMode.Read -> {
                        val renderedPreview = preview
                        val previewIsCurrent = renderedPreview != null && renderedPreview.source == contentBuffer.text.toString()
                        val canShowPreview = when {
                            renderedPreview is PreparedMarkdown.Opening ->
                                previewIsCurrent && (passage == null || passageOpened) && when (request) {
                                    is ModeScrollRequest.Read -> request.position.atTop
                                    is ModeScrollRequest.ReadOffset -> request.itemIndex == 0
                                    else -> readScroll.firstVisibleItemIndex == 0
                                }
                            readRequest -> previewIsCurrent
                            else -> true
                        }
                        NoteReadBody(
                            preview = renderedPreview.takeIf { canShowPreview },
                            previewIsCurrent = previewIsCurrent,
                            scrollState = readScroll,
                            wiki = state.wiki,
                            onWikiLinkClick = onWikiLinkClick,
                            onOpenNote = onOpenNote,
                            title = if (state.showInlineTitle) {
                                {
                                    Text(
                                        text = state.fileName,
                                        style = MaterialTheme.typography.headlineLargeEmphasized,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            onCheckboxToggle = { rendered, position, checked ->
                                if (
                                    rendered.source == contentBuffer.text.toString() &&
                                    rendered.index.checkboxes.any { it.range.first == position && it.checked != checked }
                                ) {
                                    contentBuffer.edit {
                                        replace(
                                            position,
                                            position + MARKER_LENGTH,
                                            if (checked) CHECKED else UNCHECKED,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    NoteMode.Edit -> Column(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(editScroll),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (state.showInlineTitle) {
                                NoteTitleField(
                                    buffer = titleBuffer,
                                    fileName = state.fileName,
                                    isNewNote = state.isNewNote,
                                    isRenaming = state.isRenaming,
                                    textStyle = MaterialTheme.typography.headlineLargeEmphasized,
                                    focusRequester = titleFocus,
                                    onCommit = onCommitTitle,
                                    onFocusChange = onTitleFocusChange,
                                    requestInitialFocus = state.isNewNote && !initialTitleFocusTaken,
                                    onInitialFocusShown = { initialTitleFocusTaken = true },
                                    error = state.titleError,
                                    onDone = { bodyFocus.requestFocus() },
                                    modifier = Modifier
                                        .padding(horizontal = pageMargin(), vertical = 4.dp)
                                        .widthIn(max = PageMaxWidth)
                                        .fillMaxWidth(),
                                )
                            }
                            NoteBodyEditor(
                                active = editorActive,
                                buffer = contentBuffer,
                                focusRequester = bodyFocus,
                                onTextLayout = { modeScroll.editLayout = it },
                                onTextTopInContent = { modeScroll.editTextOffset = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (linkPanelOpen) {
                            WikiLinkPanel(
                                candidates = state.wiki.linkables,
                                onPick = { note ->
                                    insertWikiLink(
                                        buffer = contentBuffer,
                                        reference = wikiReferenceFor(note, state.wiki.linkables),
                                    )
                                    linkPanelOpen = false
                                    bodyFocus.requestFocus()
                                },
                                onDismiss = {
                                    linkPanelOpen = false
                                    bodyFocus.requestFocus()
                                },
                            )
                        }
                        NoteFormatBar(
                            formatState = formatState,
                            toolbarOrder = state.toolbarOrder,
                            canUndo = contentBuffer.undoState.canUndo,
                            canRedo = contentBuffer.undoState.canRedo,
                            isUploadingImage = state.isUploadingImage,
                            wikiLinkOpen = linkPanelOpen,
                            onCommand = { command ->
                                applyMarkdownCommand(contentBuffer, command)
                                bodyFocus.requestFocus()
                            },
                            onUndo = {
                                contentBuffer.undoState.undo()
                                bodyFocus.requestFocus()
                            },
                            onRedo = {
                                contentBuffer.undoState.redo()
                                bodyFocus.requestFocus()
                            },
                            onWikiLink = {
                                linkPanelOpen = !linkPanelOpen
                                if (linkPanelOpen) {
                                    focusManager.clearFocus()
                                } else {
                                    bodyFocus.requestFocus()
                                }
                            },
                            onInsertImage = onPickImage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteBodyEditor(
    active: Boolean,
    buffer: TextFieldState,
    focusRequester: FocusRequester,
    onTextLayout: (TextLayoutResult) -> Unit,
    onTextTopInContent: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placeholder = stringResource(R.string.editor_placeholder)
    NoteTextField(
        state = buffer,
        active = active,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = pageMargin(), vertical = BodyVerticalPadding)
            .onGloballyPositioned { onTextTopInContent(it.positionInParent().y.roundToInt()) }
            .focusRequester(focusRequester),
        textStyle = LoomaTokens.type.noteBody.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        onTextLayout = onTextLayout,
        decorator = TextFieldDecorator { innerTextField ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = PageMaxWidth).fillMaxWidth()) {
                    if (buffer.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = LoomaTokens.type.noteBody,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
private fun NoteReadBody(
    preview: PreparedMarkdown?,
    previewIsCurrent: Boolean,
    scrollState: LazyListState,
    wiki: WikiContext,
    onWikiLinkClick: (String) -> Unit,
    onOpenNote: (NoteId) -> Unit,
    title: (@Composable () -> Unit)?,
    onCheckboxToggle: (PreparedMarkdown.Ready, position: Int, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = when (preview) {
        null -> {
            LoadingPage(modifier = modifier)
            return
        }
        is PreparedMarkdown.Failed -> {
            MessagePage(
                title = stringResource(R.string.editor_load_failed),
                isError = true,
                modifier = modifier,
            )
            return
        }
        is PreparedMarkdown.Opening -> preview.preview
        is PreparedMarkdown.Ready -> preview
    }
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        MarkdownBody(
            preview = ready,
            onCheckboxToggle = if (previewIsCurrent && preview is PreparedMarkdown.Ready) {
                { position, checked -> onCheckboxToggle(preview, position, checked) }
            } else {
                null
            },
            modifier = Modifier
                .widthIn(max = PageMaxWidth)
                .fillMaxWidth(),
            wiki = wiki,
            onWikiLinkClick = if (previewIsCurrent) onWikiLinkClick else { _ -> },
            scrollState = scrollState,
            header = title,
            footer = if (preview is PreparedMarkdown.Opening || wiki.backlinks.isEmpty()) null else {
                {
                    BacklinksSection(
                        backlinks = wiki.backlinks,
                        onOpen = onOpenNote,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
        )
    }
}

private val BodyVerticalPadding = 4.dp

private fun PreparedMarkdown.visibleBlocks(): PreparedMarkdown.Ready? = when (this) {
    is PreparedMarkdown.Ready -> this
    is PreparedMarkdown.Opening -> preview
    is PreparedMarkdown.Failed -> null
}

private fun insertWikiLink(buffer: TextFieldState, reference: String) {
    insertSnippet(buffer, "[[$reference]]")
}

private fun insertSnippet(buffer: TextFieldState, snippet: String) {
    val selection = buffer.selection
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    buffer.edit {
        replace(start, end, snippet)
        this.selection = TextRange(start + snippet.length)
    }
}

private fun titleErrorText(resources: Resources, error: NoteTitleError): String = when (error) {
    NoteTitleError.InvalidName -> resources.getString(R.string.editor_rename_invalid)
    NoteTitleError.NameTaken -> resources.getString(R.string.editor_rename_taken)
    NoteTitleError.Stalled -> resources.getString(R.string.editor_rename_stalled)
    NoteTitleError.Blocked -> resources.getString(R.string.editor_rename_blocked)
    NoteTitleError.Failed -> resources.getString(R.string.editor_rename_failed)
}

private fun imageErrorText(resources: Resources, error: NoteImageError): String = when (error) {
    NoteImageError.NotConfigured -> resources.getString(R.string.note_image_error_not_configured)
    NoteImageError.UnsupportedImage -> resources.getString(R.string.note_image_error_unsupported)
    NoteImageError.ImageTooLarge -> resources.getString(R.string.note_image_error_too_large)
    NoteImageError.Storage -> resources.getString(R.string.note_image_error_storage)
    NoteImageError.Network -> resources.getString(R.string.note_image_error_network)
    is NoteImageError.Rejected -> resources.getString(
        R.string.note_image_error_rejected,
        error.detail,
    )
    NoteImageError.Unknown -> resources.getString(R.string.note_image_error_unknown)
}

private sealed interface ModeScrollRequest {
    data class Read(val position: EditScrollPosition) : ModeScrollRequest
    data class ReadOffset(val itemIndex: Int, val offset: Int) : ModeScrollRequest
    data class Edit(val anchor: ReadingAnchor) : ModeScrollRequest
    data class EditOffset(val offset: Int) : ModeScrollRequest
}

private data class ModeScrollReturn(val source: String, val position: ModeScrollRequest)

private const val LARGE_NOTE_READ_THRESHOLD = 256 * 1024
private const val READ_PREVIEW_IDLE_MILLIS = 1_500L

@Composable
private fun NoteStatusScaffold(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LoomaTokens.colors.noteCanvas,
        topBar = {
            NoteTopBar(
                mode = NoteMode.Read,
                onNavigateUp = onNavigateUp,
                onToggleMode = null,
            )
        },
        content = content,
    )
}

@Composable
private fun NoteLoadingScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NoteStatusScaffold(onNavigateUp = onNavigateUp, modifier = modifier) { innerPadding ->
        LoadingPage(modifier = Modifier.padding(innerPadding))
    }
}

@Composable
private fun NoteLoadErrorScreen(
    onNavigateUp: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NoteStatusScaffold(onNavigateUp = onNavigateUp, modifier = modifier) { innerPadding ->
        MessagePage(
            title = stringResource(R.string.editor_load_failed),
            isError = true,
            actionLabel = stringResource(R.string.editor_reload),
            onAction = onRetry,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun NoteWorkspacePreview() {
    val content = "# 鍛ㄤ細璁板綍\n\n- [ ] 琛ュ厖**璁捐绋?*\n- [x] 瀵归綈鎺ュ彛\n\n> 璁板緱鍚屾缁?@鎵€鏈変汉"
    LoomaTheme(dynamicColor = false) {
        NoteWorkspace(
            state = NoteUiState(
                isLoading = false,
                fileName = "鍛ㄤ細璁板綍",
            ),
            contentBuffer = remember { TextFieldState(content) },
            titleBuffer = remember { TextFieldState("鍛ㄤ細璁板綍") },
            onCommitTitle = {},
            onTitleErrorShown = {},
            onRenameNoticeShown = {},
            onRequestLeave = {},
            onRetrySave = {},
            onOverwrite = {},
            onReloadFromDisk = {},
            onOpenNote = {},
            onPickImage = {},
            onImageInserted = {},
            onImageErrorShown = {},
        )
    }
}
