package com.example.looma.feature.ask

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.looma.domain.model.NoteId
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.assistant.AnswerStatus
import com.example.looma.data.assistant.Evidence
import com.example.looma.ui.components.*
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun AskScreen(model: AskViewModel, navigateUp: () -> Unit, openNote: (NoteId) -> Unit, open: (Evidence) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = state.saving) { }
    val question = rememberTextFieldState()
    var preview by rememberSaveable { mutableStateOf(false) }
    var investigate by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::install) }
    Scaffold(containerColor = LoomaTokens.colors.page, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.ask_title)) }, navigationIcon = {
            IconButton(onClick = navigateUp, enabled = !state.saving, shapes = IconButtonDefaults.shapes()) {
                LoomaIcon(R.drawable.ic_arrow_back, stringResource(R.string.nav_back))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Column(Modifier.widthIn(max = PageMaxWidth).fillMaxWidth().padding(horizontal = pageMargin())) {
                    OutlinedTextField(
                        state = question,
                        label = { Text(stringResource(R.string.ask_question)) },
                        labelPosition = TextFieldLabelPosition.Inside(),
                        shape = OutlinedTextFieldDefaults.roundedShape,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        isError = question.text.length > 1000,
                        supportingText = if (question.text.length > 1000) {
                            { Text(stringResource(R.string.ask_length)) }
                        } else null,
                    )
                    ListItem(
                        checked = investigate,
                        onCheckedChange = { investigate = it },
                        enabled = !state.busy && state.hasModel,
                        trailingContent = {
                            Switch(checked = investigate, enabled = !state.busy && state.hasModel, onCheckedChange = null)
                        },
                    ) { Text(stringResource(R.string.ask_investigate)) }
                    if (!state.busy) {
                        Button(onClick = { model.ask(question.text.toString(), investigate) },
                            enabled = question.text.isNotBlank() && question.text.length <= 1000,
                            shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.ask_submit))
                        }
                        TextButton(onClick = { picker.launch(arrayOf("*/*")) }, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(if (state.hasModel) R.string.ask_replace else R.string.ask_import))
                        }
                    } else {
                        if (rememberDelayedVisibility(true)) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        if (state.step.isNotEmpty()) Text(stringResource(R.string.ask_tool, state.step))
                        if (state.saving) Text(stringResource(R.string.ask_saving))
                        else TextButton(onClick = model::cancel, shapes = ButtonDefaults.shapes()) { Text(stringResource(android.R.string.cancel)) }
                    }
                    state.issue?.let {
                        Text(stringResource(when (it) {
                            AskIssue.Import -> R.string.ask_import_failed
                            AskIssue.Run -> R.string.ask_run_failed
                            AskIssue.Timeout -> R.string.ask_timeout
                            AskIssue.Changed -> R.string.ask_changed
                            AskIssue.Save -> R.string.ask_save_failed
                        }), color = MaterialTheme.colorScheme.error)
                    }
                    state.answer?.let { answer ->
                        val label = when (answer.status) {
                            AnswerStatus.NoEvidence -> R.string.ask_none
                            AnswerStatus.NeedsModel -> R.string.ask_needs_model
                            AnswerStatus.Insufficient -> R.string.ask_insufficient
                            AnswerStatus.ModelFailed -> R.string.ask_model_failed
                            AnswerStatus.Answered -> null
                        }
                        label?.let { Text(stringResource(it)) }
                        for (claim in answer.claims) {
                            Text(claim.text)
                            for (id in claim.citations) {
                                TextButton(onClick = { model.open(answer.sources[id - 1], open) }, enabled = !state.busy, shapes = ButtonDefaults.shapes()) {
                                    Text("[$id] " + answer.sources[id - 1].id.path.relativePath)
                                }
                            }
                        }
                        if (answer.claims.isNotEmpty()) {
                            val saved = state.saved
                            TextButton(enabled = !state.busy, shapes = ButtonDefaults.shapes(), onClick = {
                                if (saved == null) preview = true else openNote(saved)
                            }) { Text(stringResource(if (saved == null) R.string.ask_save_note else R.string.ask_open_saved)) }
                        }
                        if (!answer.semantic) Text(stringResource(R.string.ask_lexical))
                        if (answer.partial) Text(stringResource(R.string.ask_partial))
                    }
                }
            }
            itemsIndexed(state.answer?.sources.orEmpty()) { index, source ->
                Column(Modifier.widthIn(max = PageMaxWidth).fillMaxWidth().padding(horizontal = pageMargin())) {
                    TextButton(onClick = { model.open(source, open) }, enabled = !state.busy, shapes = ButtonDefaults.shapes()) {
                        Text("[" + (index + 1) + "] " + source.id.path.relativePath)
                    }
                    Text(source.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
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
                        lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
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
            dismissButton = { TextButton(onClick = { preview = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

}
