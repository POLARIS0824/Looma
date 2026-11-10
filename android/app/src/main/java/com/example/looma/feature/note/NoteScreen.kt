@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.example.looma.feature.note

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.note.resolveImageSource
import com.example.looma.domain.markdown.MarkdownEditing
import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.markdown.TextSelection
import com.example.looma.domain.markdown.withCheckboxChecked
import com.example.looma.domain.model.NoteId
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
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.markdown.LocalImageSourceResolver
import com.example.looma.ui.markdown.MarkdownBody
import com.example.looma.ui.theme.LoomaTheme
import com.example.looma.ui.theme.LoomaTokens
import kotlinx.coroutines.launch

@Composable
fun NoteRoute(
    viewModel: NoteViewModel,
    onNavigateUp: () -> Unit,
    onOpenNote: (NoteId) -> Unit,
    onRenameNavigate: (NoteId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requestLeave = viewModel::requestLeave
    val navigateUp by rememberUpdatedState(onNavigateUp)
    LaunchedEffect(state.leaveReady) {
        if (state.leaveReady) {
            viewModel.onLeaveReadyHandled()
            navigateUp()
        }
    }
    LaunchedEffect(state.renameTarget) {
        val target = state.renameTarget ?: return@LaunchedEffect
        viewModel.onRenameNavigated()
        onRenameNavigate(target)
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
        viewModel.syncWithDisk()
        viewModel.refreshWiki()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onEnterBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onEnterBackground() }

    BackHandler(enabled = state.needsLeaveGuard || state.willDiscardEmpty) {
        viewModel.requestLeave()
    }

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
            CompositionLocalProvider(LocalImageSourceResolver provides resolver) {
                NoteWorkspace(
                    state = state,
                    onContentChanged = viewModel::onContentChanged,
                    onCommitTitle = viewModel::commitTitle,
                    onTitleErrorShown = viewModel::onTitleErrorShown,
                    onRenameNoticeShown = viewModel::onRenameNoticeShown,
                    onRequestLeave = requestLeave,
                    onRetrySave = viewModel::retrySave,
                    onOverwrite = viewModel::overwrite,
                    onReloadFromDisk = viewModel::reloadFromDisk,
                    onOpenNote = onOpenNote,
                    onPickImage = pickImage,
                    onImageInserted = viewModel::onImageInserted,
                    onImageErrorShown = viewModel::onImageErrorShown,
                    editorActive = editorActive,
                    modifier = modifier,
                )
            }
        }
    }

    if (state.leaveBlocked) {
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
    onContentChanged: (String) -> Unit,
    onCommitTitle: (String) -> Unit,
    onTitleErrorShown: () -> Unit,
    onRenameNoticeShown: () -> Unit,
    onRequestLeave: () -> Unit,
    onRetrySave: () -> Unit,
    onOverwrite: () -> Unit,
    onReloadFromDisk: () -> Unit,
    onOpenNote: (NoteId) -> Unit,
    onPickImage: () -> Unit,
    onImageInserted: () -> Unit,
    onImageErrorShown: () -> Unit,
    editorActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val contentBuffer = rememberEditorBuffer(state.reloadToken, state.content)
    var mode by rememberSaveable { mutableStateOf(NoteMode.Edit) }
    var linkPanelOpen by rememberSaveable { mutableStateOf(false) }
    val readScroll = rememberScrollState()
    val editScroll = rememberScrollState()
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
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        onRequestLeave()
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

    val notifyContentChanged by rememberUpdatedState(onContentChanged)
    LaunchedEffect(contentBuffer) {
        var lastContent = state.content
        snapshotFlow { contentBuffer.text.toString() }.collect { content ->
            if (content != lastContent) {
                lastContent = content
                notifyContentChanged(content)
            }
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

    val contentText = contentBuffer.text.toString()
    val selection = contentBuffer.selection
    val formatState = remember(contentText, selection) {
        MarkdownEditing.formatState(
            text = contentText,
            selection = TextSelection(selection.start, selection.end),
        )
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
                    mode = if (mode == NoteMode.Read) NoteMode.Edit else NoteMode.Read
                },
                scrollBehavior = appBarScroll,
                title = {
                    if (!state.showInlineTitle) {
                        if (mode == NoteMode.Edit) {
                            NoteTitleField(
                                fileName = state.fileName,
                                isNewNote = state.isNewNote,
                                textStyle = LoomaTokens.type.sectionTitle,
                                focusRequester = titleFocus,
                                onCommit = onCommitTitle,
                                error = state.titleError,
                                onDone = { bodyFocus.requestFocus() },
                            )
                        } else {
                            Text(
                                text = state.fileName,
                                style = LoomaTokens.type.sectionTitle,
                                color = LoomaTokens.colors.title,
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
                modifier = Modifier.weight(1f),
                transitionSpec = { modeEnter togetherWith modeExit using null },
                label = "note-mode",
            ) { shownMode ->
                when (shownMode) {
                    NoteMode.Read -> NoteReadBody(
                        content = contentText,
                        fileName = state.fileName,
                        scrollState = readScroll,
                        wiki = state.wiki,
                        onWikiLinkClick = onWikiLinkClick,
                        onOpenNote = onOpenNote,
                        title = {
                            if (state.showInlineTitle) {
                                Text(
                                    text = state.fileName,
                                    style = LoomaTokens.type.noteTitle,
                                    color = LoomaTokens.colors.title,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                        onCheckboxToggle = { position, checked ->
                            val document = MarkdownIndex.index(contentBuffer.text.toString(), state.fileName)
                            if (document.withCheckboxChecked(position, checked) != null) {
                                contentBuffer.edit {
                                    replace(
                                        position,
                                        position + MARKER_LENGTH,
                                        if (checked) CHECKED_MARKER else UNCHECKED_MARKER,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

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
                                    fileName = state.fileName,
                                    isNewNote = state.isNewNote,
                                    textStyle = LoomaTokens.type.noteTitle,
                                    focusRequester = titleFocus,
                                    onCommit = onCommitTitle,
                                    error = state.titleError,
                                    onDone = { bodyFocus.requestFocus() },
                                    modifier = Modifier
                                        .padding(horizontal = 20.dp, vertical = 4.dp)
                                        .widthIn(max = NoteColumnMaxWidth)
                                        .fillMaxWidth(),
                                )
                            }
                            NoteBodyEditor(
                                active = editorActive,
                                buffer = contentBuffer,
                                focusRequester = bodyFocus,
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
    modifier: Modifier = Modifier,
) {
    val placeholder = stringResource(R.string.editor_placeholder)
    NoteTextField(
        state = buffer,
        active = active,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .focusRequester(focusRequester),
        textStyle = LoomaTokens.type.noteBody.copy(
            color = LoomaTokens.colors.title,
        ),
        decorator = TextFieldDecorator { innerTextField ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = NoteColumnMaxWidth).fillMaxWidth()) {
                    if (buffer.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = LoomaTokens.type.noteBody,
                            color = LoomaTokens.colors.muted,
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
    content: String,
    fileName: String,
    scrollState: ScrollState,
    wiki: WikiContext,
    onWikiLinkClick: (String) -> Unit,
    onOpenNote: (NoteId) -> Unit,
    title: @Composable () -> Unit,
    onCheckboxToggle: (position: Int, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val document = remember(content, fileName) {
        MarkdownIndex.index(content, fileName)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = NoteColumnMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            title()
            MarkdownBody(
                document = document,
                onCheckboxToggle = onCheckboxToggle,
                wiki = wiki,
                onWikiLinkClick = onWikiLinkClick,
            )
            BacklinksSection(backlinks = wiki.backlinks, onOpen = onOpenNote)
        }
    }
}

private val NoteColumnMaxWidth = 640.dp

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

@Composable
private fun rememberEditorBuffer(reloadToken: Int, text: String): TextFieldState {
    val selectionOnlySaver = remember(text) {
        Saver<TextFieldState, IntArray>(
            save = { buffer -> intArrayOf(buffer.selection.start, buffer.selection.end) },
            restore = { (start, end) ->
                TextFieldState(
                    initialText = text,
                    initialSelection = TextRange(start.coerceIn(0, text.length), end.coerceIn(0, text.length)),
                )
            },
        )
    }
    return rememberSaveable(reloadToken, saver = selectionOnlySaver) {
        TextFieldState(initialText = text, initialSelection = TextRange(text.length))
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

private const val MARKER_LENGTH = 3
private const val CHECKED_MARKER = "[x]"
private const val UNCHECKED_MARKER = "[ ]"

@Composable
private fun NoteLoadingScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
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
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            DelayedLoadingIndicator(loading = true)
        }
    }
}

@Composable
private fun NoteLoadErrorScreen(
    onNavigateUp: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.editor_load_failed),
                style = LoomaTokens.type.supporting,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.editor_reload)) }
        }
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun NoteWorkspacePreview() {
    LoomaTheme(dynamicColor = false) {
        NoteWorkspace(
            state = NoteUiState(
                content = "# 周会记录\n\n- [ ] 补充**设计稿**\n- [x] 对齐接口\n\n> 记得同步给 @所有人",
                savedContent = "# 周会记录\n\n- [ ] 补充**设计稿**\n- [x] 对齐接口\n\n> 记得同步给 @所有人",
                isLoading = false,
                saveState = NoteSaveState.Saved,
                fileName = "周会记录",
            ),
            onContentChanged = {},
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
