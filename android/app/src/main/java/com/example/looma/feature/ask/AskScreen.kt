package com.example.looma.feature.ask

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.assistant.GeneratorAvailability
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.ask.components.AskComposer
import com.example.looma.feature.ask.components.AskConversation
import com.example.looma.feature.ask.components.AskEmpty
import com.example.looma.feature.ask.components.AskHistorySheet
import com.example.looma.feature.ask.components.AskModelSheet
import com.example.looma.feature.ask.components.AskQuestionLimit
import com.example.looma.feature.ask.components.AskSkillsSheet
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun AskScreen(
    model: AskViewModel,
    navigateUp: () -> Unit,
    openNote: (NoteId) -> Unit,
    manageModels: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.refreshModels() }
    BackHandler(enabled = state.saving) { }

    val input = rememberTextFieldState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    var saveTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var pendingModelSheet by remember { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var skillsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pendingModelSheet) {
        if (!pendingModelSheet) return@LaunchedEffect
        focus.clearFocus(force = true)
        keyboard?.hide()
        withTimeoutOrNull(350) {
            snapshotFlow { ime.getBottom(density) }
                .first { it == 0 }
        }
        pendingModelSheet = false
        sheetOpen = true
    }

    val firstUserMessage = state.messages.firstOrNull { it.role == ChatRole.User }?.text
    val topBarTitle = firstUserMessage ?: stringResource(R.string.ask_title)
    val phase = state.phase

    Scaffold(
        containerColor = LoomaTokens.colors.page,
        topBar = {
            TopAppBar(
                title = { Text(topBarTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = navigateUp, enabled = !state.saving, shapes = IconButtonDefaults.shapes()) {
                        LoomaIcon(R.drawable.ic_arrow_back, stringResource(R.string.nav_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LoomaTokens.colors.page,
                    scrolledContainerColor = LoomaTokens.colors.page,
                ),
                actions = {
                    IconButton(
                        onClick = {
                            model.refreshSkills()
                            skillsOpen = true
                        },
                        enabled = !state.saving,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        LoomaIcon(R.drawable.ic_sparkle, stringResource(R.string.ask_skills))
                    }
                    IconButton(
                        onClick = { historyOpen = true },
                        enabled = !state.saving,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        LoomaIcon(R.drawable.ic_history, stringResource(R.string.ask_history))
                    }
                    if (state.messages.isNotEmpty() && !state.saving) {
                        IconButton(onClick = model::newChat, shapes = IconButtonDefaults.shapes()) {
                            LoomaIcon(R.drawable.ic_new_chat, stringResource(R.string.ask_new_chat))
                        }
                    }
                },
            )
        },
        bottomBar = {
            AskComposer(
                input = input,
                choice = state.choice,
                running = phase is AskPhase.Running,
                busy = state.busy,
                sheetOpen = sheetOpen || pendingModelSheet,
                onSend = {
                    val question = input.text.toString()
                    if (question.isNotBlank() && question.length <= AskQuestionLimit && !state.busy) {
                        model.ask(question)
                        input.clearText()
                        focus.clearFocus()
                    }
                },
                onStop = model::cancel,
                onModel = { pendingModelSheet = true },
            )
        },
    ) { padding ->
        if (state.messages.isEmpty()) {
            AskEmpty(
                vaultName = state.vaultName,
                hasModel = state.models.any { it.availability !is GeneratorAvailability.Unavailable },
                issue = state.issue,
                onManageModels = manageModels,
                contentPadding = padding,
            )
        } else {
            AskConversation(
                messages = state.messages,
                status = (phase as? AskPhase.Running)?.status,
                saving = state.saving,
                busy = state.busy,
                compacted = state.compactionNotice != null,
                issue = state.issue,
                onSaveMessage = { message -> saveTarget = message.id },
                onOpenSaved = openNote,
                onRetry = model::retry,
                onManageModels = manageModels,
                contentPadding = padding,
            )
        }
    }

    if (historyOpen) {
        AskHistorySheet(
            conversations = state.conversations,
            currentConversationId = state.conversationId,
            busy = state.busy,
            onSelect = { id ->
                historyOpen = false
                model.loadConversation(id)
            },
            onDelete = model::deleteConversation,
            onDismiss = { historyOpen = false },
        )
    }

    if (skillsOpen) {
        AskSkillsSheet(
            skills = state.skills,
            busy = state.busy,
            onToggle = model::setSkillEnabled,
            onDismiss = { skillsOpen = false },
        )
    }

    if (sheetOpen) {
        AskModelSheet(
            models = state.models,
            choice = state.choice,
            onSelect = model::select,
            onEffort = model::setEffort,
            onRoute = model::setRoute,
            onPrepareModel = model::prepareSelectedModel,
            onManageModels = {
                focus.clearFocus(force = true)
                keyboard?.hide()
                sheetOpen = false
                manageModels()
            },
            onDismiss = { sheetOpen = false },
        )
    }

    val messageToSave = saveTarget?.let { id ->
        state.messages.firstOrNull { it.id == id && it.role == ChatRole.Assistant && it.text.isNotBlank() }
    }
    if (messageToSave != null) {
        val defaultTitle = remember(messageToSave.id) {
            val idx = state.messages.indexOfFirst { it.id == messageToSave.id }
            val prompt = state.messages.take(idx.coerceAtLeast(0)).lastOrNull { it.role == ChatRole.User }?.text
            (prompt ?: messageToSave.text).lineSequence().firstOrNull().orEmpty().take(72)
        }
        val title = rememberTextFieldState(initialText = defaultTitle)
        AlertDialog(
            onDismissRequest = { saveTarget = null },
            title = { Text(stringResource(R.string.ask_save_note)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        state = title,
                        label = { Text(stringResource(R.string.ask_filename)) },
                        labelPosition = TextFieldLabelPosition.Inside(),
                        shape = OutlinedTextFieldDefaults.roundedShape,
                        lineLimits = TextFieldLineLimits.SingleLine,
                    )
                    Text(messageToSave.text, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.text.isNotBlank() && !state.busy,
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        model.saveMessage(messageToSave.id, title.text.toString())
                        saveTarget = null
                    },
                ) {
                    Text(stringResource(R.string.ask_save_note))
                }
            },
            dismissButton = {
                TextButton(onClick = { saveTarget = null }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
