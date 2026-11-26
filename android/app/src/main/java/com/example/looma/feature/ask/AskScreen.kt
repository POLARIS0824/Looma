package com.example.looma.feature.ask

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.assistant.Evidence
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.ask.components.AskComposer
import com.example.looma.feature.ask.components.AskConversation
import com.example.looma.feature.ask.components.AskEmpty
import com.example.looma.feature.ask.components.AskModelSheet
import com.example.looma.feature.ask.components.AskQuestionLimit
import com.example.looma.feature.ask.components.AskSheetPage
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun AskScreen(
    model: AskViewModel,
    navigateUp: () -> Unit,
    openNote: (NoteId) -> Unit,
    open: (Evidence) -> Unit,
    manageModels: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.refreshModels() }
    BackHandler(enabled = state.saving) { }
    val input = rememberTextFieldState()
    val focus = LocalFocusManager.current
    var preview by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<AskSheetPage?>(null) }
    var sheetPage by rememberSaveable { mutableStateOf<AskSheetPage?>(null) }
    val openSheet = { page: AskSheetPage ->
        sheet = page
        sheetPage = page
    }
    val phase = state.phase
    Scaffold(
        containerColor = LoomaTokens.colors.page,
        topBar = {
            TopAppBar(
                title = { Text(state.question, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    if (state.question.isNotEmpty() && !state.busy) {
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
                sheetOpen = sheet != null,
                effortOpen = sheet != null && sheetPage == AskSheetPage.Effort,
                onSend = {
                    val question = input.text.toString()
                    if (question.isNotBlank() && question.length <= AskQuestionLimit && !state.busy) {
                        model.ask(question)
                        input.clearText()
                        focus.clearFocus()
                    }
                },
                onStop = model::cancel,
                onModel = { openSheet(AskSheetPage.Model) },
                onEffort = { openSheet(AskSheetPage.Effort) },
            )
        },
    ) { padding ->
        if (state.question.isEmpty()) {
            AskEmpty(
                vaultName = state.vaultName,
                hasModel = state.models.isNotEmpty(),
                issue = state.issue,
                onManageModels = manageModels,
                contentPadding = padding,
            )
        } else {
            AskConversation(
                question = state.question,
                answer = state.answer,
                step = (phase as? AskPhase.Running)?.step,
                saving = state.saving,
                saved = state.saved,
                busy = state.busy,
                issue = state.issue,
                onOpen = { model.open(it, open) },
                onSave = { preview = true },
                onOpenSaved = openNote,
                onRetry = model::retry,
                contentPadding = padding,
            )
        }
    }
    sheet?.let { start ->
        AskModelSheet(
            start = start,
            models = state.models,
            choice = state.choice,
            onPage = { sheetPage = it },
            onSelect = model::select,
            onEffort = model::setEffort,
            onManageModels = {
                sheet = null
                sheetPage = null
                manageModels()
            },
            onDismiss = {
                sheet = null
                sheetPage = null
            },
        )
    }
    val answer = state.answer
    if (preview && answer != null && answer.claims.isNotEmpty()) {
        val title = rememberTextFieldState(initialText = state.question.lineSequence().first().take(72))
        val sources = stringResource(R.string.ask_sources)
        val markdown = remember(answer, state.question, sources) { answer.asMarkdown(state.question, sources) }
        AlertDialog(onDismissRequest = { preview = false }, title = { Text(stringResource(R.string.ask_save_note)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        state = title,
                        label = { Text(stringResource(R.string.ask_filename)) },
                        labelPosition = TextFieldLabelPosition.Inside(),
                        shape = OutlinedTextFieldDefaults.roundedShape,
                        lineLimits = TextFieldLineLimits.SingleLine,
                    )
                    Text(markdown, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(enabled = title.text.isNotBlank() && !state.busy, shapes = ButtonDefaults.shapes(), onClick = {
                    model.save(title.text.toString(), markdown)
                    preview = false
                }) { Text(stringResource(R.string.ask_save_note)) }
            },
            dismissButton = {
                TextButton(onClick = { preview = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
