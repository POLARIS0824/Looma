package com.example.looma.feature.note.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.looma.R
import com.example.looma.data.assistant.addition
import com.example.looma.data.assistant.Connection
import com.example.looma.data.assistant.Evidence
import com.example.looma.data.assistant.VaultEvidence
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.Vault
import com.example.looma.feature.note.NoteViewModel
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.components.LoomaIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.text.TextRange
import com.example.looma.domain.markdown.MarkdownNavigation
import com.example.looma.domain.markdown.NotePassage

@Composable
internal fun WeaveAction(
    vault: Vault,
    id: NoteId,
    repository: VaultEvidence,
    editor: NoteViewModel,
    focus: () -> TextRange?,
    open: (Evidence) -> Unit,
) {
    var showing by remember(id) { mutableStateOf(false) }
    var range by remember(id) { mutableStateOf<TextRange?>(null) }
    IconButton(onClick = { range = focus(); showing = true }, shapes = IconButtonDefaults.shapes()) {
        LoomaIcon(R.drawable.ic_sparkle, stringResource(R.string.weave_title))
    }
    if (!showing) return
    var dialogState by remember { mutableStateOf<WeaveDialogState>(WeaveDialogState.Loading) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var passage by remember { mutableStateOf<NotePassage?>(null) }
    var scoped by remember { mutableStateOf(range?.collapsed == false) }
    val applying = (dialogState as? WeaveDialogState.Preview)?.applying == true
    LaunchedEffect(id, refresh, scoped) {
        dialogState = WeaveDialogState.Loading
        dialogState = try {
            val source = editor.editor.snapshot()
            passage = passage?.takeIf { it.source === source } ?: range?.let { r ->
                withContext(Dispatchers.Default) { MarkdownNavigation.scan(source).passage(source, r.min, r.max) }
            }
            WeaveDialogState.Ready(repository.related(vault, id, source, passage.takeIf { scoped }))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            WeaveDialogState.Failed
        }
    }
    AlertDialog(
        onDismissRequest = { if (!applying) showing = false },
        title = { Text(stringResource(if (dialogState is WeaveDialogState.Preview) R.string.weave_confirm else R.string.weave_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (dialogState !is WeaveDialogState.Preview && passage != null) {
                    FilterChip(
                        selected = scoped,
                        onClick = { scoped = !scoped },
                        label = { Text(stringResource(R.string.weave_focus)) },
                        shapes = FilterChipDefaults.shapes(),
                    )
                    if (scoped) passage?.let { Text(it.excerpt(160)) }
                }
                when (val current = dialogState) {
                    WeaveDialogState.Loading -> DelayedLoadingIndicator(true)
                    WeaveDialogState.Failed -> Text(stringResource(R.string.weave_changed))
                    is WeaveDialogState.Preview -> {
                        val proposal = current.proposal
                        Text(proposal.evidence.text)
                        val focusPassage = proposal.passage
                        val diff = if (focusPassage == null) stringResource(R.string.weave_diff, proposal.reference)
                            else focusPassage.insertionPreview(proposal.addition(focusPassage.source))
                        Text(diff, style = MaterialTheme.typography.titleMedium)
                    }
                    is WeaveDialogState.Ready -> if (current.items.isEmpty()) {
                        Text(stringResource(R.string.weave_empty))
                    } else {
                        for (item in current.items) {
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        val resolved = repository.resolve(vault, item.evidence)
                                        if (resolved != null && editor.prepareToOpenLinkedNote()) {
                                            showing = false
                                            open(resolved)
                                        } else dialogState = WeaveDialogState.Failed
                                    } catch (cancel: CancellationException) { throw cancel }
                                    catch (_: Exception) { dialogState = WeaveDialogState.Failed }
                                }
                            }, shapes = ButtonDefaults.shapes()) { Text(item.evidence.id.path.relativePath) }
                            item.evidence.similarity?.let { Text(stringResource(R.string.weave_similarity, it)) }
                            if (item.keywords.isNotEmpty()) Text(stringResource(R.string.weave_keywords, item.keywords.joinToString(" · ")))
                            if (item.tags.isNotEmpty()) Text(item.tags.joinToString(" ") { "#$it" })
                            Text(item.evidence.text, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { dialogState = WeaveDialogState.Preview(item) }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.weave_preview)) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (val current = dialogState) {
                WeaveDialogState.Failed -> TextButton(onClick = { refresh++ }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.weave_retry)) }
                is WeaveDialogState.Preview -> Button(enabled = !current.applying, shapes = ButtonDefaults.shapes(), onClick = {
                    dialogState = current.copy(applying = true)
                    scope.launch {
                        try {
                            if (editor.confirmWeave(vault, current.proposal, repository)) showing = false else dialogState = WeaveDialogState.Failed
                        } catch (cancel: CancellationException) {
                            dialogState = current.copy(applying = false)
                            throw cancel
                        } catch (_: Exception) {
                            dialogState = WeaveDialogState.Failed
                        }
                    }
                }) { Text(stringResource(R.string.weave_insert)) }
                WeaveDialogState.Loading, is WeaveDialogState.Ready -> Unit
            }
        },
        dismissButton = {
            TextButton(enabled = !applying, onClick = { showing = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private sealed interface WeaveDialogState {
    data object Loading : WeaveDialogState
    data class Ready(val items: List<Connection>) : WeaveDialogState
    data class Preview(val proposal: Connection, val applying: Boolean = false) : WeaveDialogState
    data object Failed : WeaveDialogState
}
