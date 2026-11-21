package com.example.looma.feature.note.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.looma.R
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

@Composable
internal fun WeaveAction(
    vault: Vault,
    id: NoteId,
    repository: VaultEvidence,
    editor: NoteViewModel,
    open: (Evidence) -> Unit,
) {
    var showing by remember(id) { mutableStateOf(false) }
    IconButton(onClick = { showing = true }, shapes = IconButtonDefaults.shapes()) {
        LoomaIcon(R.drawable.ic_sparkle, stringResource(R.string.weave_title))
    }
    if (!showing) return
    var items by remember { mutableStateOf(emptyList<Connection>()) }
    var selected by remember { mutableStateOf<Connection?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id, refresh) {
        loading = true
        failed = false
        try {
            items = repository.related(vault, id, editor.editor.text.toString())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    val proposal = selected
    AlertDialog(
        onDismissRequest = { if (!applying) showing = false },
        title = { Text(stringResource(if (proposal == null) R.string.weave_title else R.string.weave_confirm)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    loading -> DelayedLoadingIndicator(true)
                    failed -> Text(stringResource(R.string.weave_changed))
                    proposal != null -> {
                        Text(proposal.evidence.text)
                        Text(stringResource(R.string.weave_diff, proposal.reference), style = MaterialTheme.typography.titleMedium)
                    }
                    items.isEmpty() -> Text(stringResource(R.string.weave_empty))
                    else -> for (item in items) {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    val resolved = repository.resolve(vault, item.evidence)
                                    if (resolved != null && editor.prepareToOpenLinkedNote()) {
                                        showing = false
                                        open(resolved)
                                    } else failed = true
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) { failed = true }
                            }
                        }) { Text(item.evidence.id.path.relativePath) }
                        item.evidence.similarity?.let { Text(stringResource(R.string.weave_similarity, it)) }
                        if (item.keywords.isNotEmpty()) Text(stringResource(R.string.weave_keywords, item.keywords.joinToString(" · ")))
                        if (item.tags.isNotEmpty()) Text(item.tags.joinToString(" ") { "#$it" })
                        Text(item.evidence.text, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { selected = item }) { Text(stringResource(R.string.weave_preview)) }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            when {
                failed -> TextButton(onClick = { selected = null; refresh++ }) { Text(stringResource(R.string.weave_retry)) }
                proposal != null -> Button(enabled = !applying, onClick = {
                    applying = true
                    scope.launch {
                        try {
                            if (editor.confirmWeave(vault, proposal, repository)) showing = false else failed = true
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { failed = true }
                        finally { applying = false }
                    }
                }) { Text(stringResource(R.string.weave_insert)) }
            }
        },
        dismissButton = {
            TextButton(enabled = !applying, onClick = { showing = false }) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
