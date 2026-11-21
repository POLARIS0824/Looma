package com.example.looma.feature.ask

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
internal fun AskScreen(model: AskViewModel, navigateUp: () -> Unit, open: (Evidence) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val question = rememberTextFieldState()
    var investigate by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::install) }
    Scaffold(containerColor = LoomaTokens.colors.page, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.ask_title)) }, navigationIcon = {
            IconButton(onClick = navigateUp) {
                LoomaIcon(R.drawable.ic_arrow_back, stringResource(R.string.nav_back))
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Column(Modifier.widthIn(max = PageMaxWidth).fillMaxWidth().padding(horizontal = pageMargin())) {
                    OutlinedTextField(state = question, label = { Text(stringResource(R.string.ask_question)) },
                        modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                        isError = question.text.length > 1000,
                        supportingText = if (question.text.length > 1000) {
                            { Text(stringResource(R.string.ask_length)) }
                        } else null,
                    )
                    ListItem(trailingContent = { Switch(checked = investigate, enabled = !state.busy && state.hasModel,
                        onCheckedChange = { investigate = it }) }) { Text(stringResource(R.string.ask_investigate)) }
                    if (!state.busy) {
                        Button(onClick = { model.ask(question.text.toString(), investigate) },
                            enabled = question.text.isNotBlank() && question.text.length <= 1000) {
                            Text(stringResource(R.string.ask_submit))
                        }
                        TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Text(stringResource(if (state.hasModel) R.string.ask_replace else R.string.ask_import))
                        }
                    } else {
                        if (rememberDelayedVisibility(true)) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        if (state.step.isNotEmpty()) Text(stringResource(R.string.ask_tool, state.step))
                        TextButton(onClick = model::cancel) { Text(stringResource(android.R.string.cancel)) }
                    }
                    state.issue?.let {
                        Text(stringResource(when (it) {
                            AskIssue.Import -> R.string.ask_import_failed
                            AskIssue.Run -> R.string.ask_run_failed
                            AskIssue.Timeout -> R.string.ask_timeout
                            AskIssue.Changed -> R.string.ask_changed
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
                                TextButton(onClick = { model.open(answer.sources[id - 1], open) }, enabled = !state.busy) {
                                    Text("[$id] " + answer.sources[id - 1].id.path.relativePath)
                                }
                            }
                        }
                        if (!answer.semantic) Text(stringResource(R.string.ask_lexical))
                        if (answer.partial) Text(stringResource(R.string.ask_partial))
                    }
                }
            }
            itemsIndexed(state.answer?.sources.orEmpty()) { index, source ->
                Column(Modifier.widthIn(max = PageMaxWidth).fillMaxWidth().padding(horizontal = pageMargin())) {
                    TextButton(onClick = { model.open(source, open) }, enabled = !state.busy) {
                        Text("[" + (index + 1) + "] " + source.id.path.relativePath)
                    }
                    Text(source.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
