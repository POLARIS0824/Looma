@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.example.looma.feature.trash

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.note.TrashBulkOutcome
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.components.LoadingPage
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.MessagePage
import com.example.looma.ui.components.PageMaxWidth
import com.example.looma.ui.components.pageMargin
import com.example.looma.ui.components.rememberDelayedVisibility
import com.example.looma.ui.format.formatDateTime
import com.example.looma.ui.theme.LoomaTokens
import kotlinx.coroutines.launch

@Composable
fun TrashRoute(
    viewModel: TrashViewModel,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val restoredText = stringResource(R.string.trash_restored)
    val failedText = stringResource(R.string.trash_restore_failed)
    val deletedText = stringResource(R.string.trash_deleted)
    val undoLabel = stringResource(R.string.action_undo)
    val undoneText = stringResource(R.string.trash_restore_undone)
    val undoFailedText = stringResource(R.string.action_undo_failed)
    val actionFailedText = stringResource(R.string.home_error_action)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }

    TrashScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        onRestoreClick = { trashId ->
            viewModel.restore(trashId) { restoredId ->
                scope.launch {
                    if (restoredId == null) {
                        snackbarHostState.showSnackbar(failedText)
                        return@launch
                    }
                    val result = snackbarHostState.showSnackbar(
                        message = restoredText,
                        actionLabel = undoLabel,
                    )
                    if (result != SnackbarResult.ActionPerformed) return@launch
                    viewModel.trashAgain(restoredId) { undone ->
                        scope.launch {
                            snackbarHostState.showSnackbar(if (undone) undoneText else undoFailedText)
                        }
                    }
                }
            }
        },
        onDeleteClick = { trashId ->
            viewModel.deletePermanently(trashId) { deleted ->
                scope.launch {
                    snackbarHostState.showSnackbar(if (deleted) deletedText else actionFailedText)
                }
            }
        },
        onRestoreAllClick = {
            viewModel.restoreAll { outcome ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        when {
                            outcome == null -> failedText
                            outcome.isFullSuccess -> resources.getQuantityString(
                                R.plurals.trash_restored_count,
                                outcome.total,
                                outcome.total,
                            )

                            else -> resources.getString(
                                R.string.trash_bulk_partial,
                                outcome.succeeded,
                                outcome.failed,
                            )
                        },
                    )
                }
            }
        },
        onEmptyTrashClick = {
            viewModel.emptyTrash { outcome ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        emptyTrashFeedback(
                            outcome = outcome,
                            actionFailedText = actionFailedText,
                            fullSuccess = { total ->
                                resources.getQuantityString(R.plurals.trash_emptied_count, total, total)
                            },
                            partial = { succeeded, failed ->
                                resources.getString(R.string.trash_empty_partial, succeeded, failed)
                            },
                        ),
                    )
                }
            }
        },
        onRetryClick = viewModel::load,
        modifier = modifier,
    )
}

@Composable
fun TrashScreen(
    state: TrashUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onRestoreClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onRestoreAllClick: () -> Unit,
    onEmptyTrashClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf<TrashConfirm?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LoomaTokens.colors.page,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp, shapes = IconButtonDefaults.shapes()) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_arrow_back,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = onRestoreAllClick, enabled = state.canRestoreAll, shapes = IconButtonDefaults.shapes()) {
                            LoomaIcon(
                                iconRes = R.drawable.ic_restore,
                                contentDescription = stringResource(R.string.trash_restore_all),
                            )
                        }
                        IconButton(
                            onClick = { confirm = TrashConfirm.EmptyTrash },
                            enabled = state.canEmptyTrash,
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            LoomaIcon(
                                iconRes = R.drawable.ic_delete,
                                contentDescription = stringResource(R.string.trash_empty_trash),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LoomaTokens.colors.page),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.isLoading && state.items.isEmpty() -> LoadingPage()

                state.loadFailed -> MessagePage(
                    title = stringResource(R.string.trash_load_failed),
                    isError = true,
                    actionLabel = stringResource(R.string.trash_retry),
                    onAction = onRetryClick,
                )

                state.isEmpty -> MessagePage(
                    title = stringResource(R.string.trash_empty),
                    supportingText = stringResource(R.string.trash_empty_hint),
                )

                else -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.busy == TrashBusy.Bulk) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).widthIn(max = PageMaxWidth).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = pageMargin(), vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.items, key = { it.trashId }) { item ->
                            TrashRow(
                                item = item,
                                isRestoring = state.busy == TrashBusy.Row(item.trashId),
                                actionsEnabled = state.busy == TrashBusy.None,
                                onRestoreClick = { onRestoreClick(item.trashId) },
                                onDeleteClick = { confirm = TrashConfirm.DeleteOne(item) },
                            )
                        }
                    }
                }
            }
        }
    }

    confirm?.let { pending ->
        when (pending) {
            is TrashConfirm.DeleteOne -> TrashDeleteDialog(
                onConfirm = {
                    onDeleteClick(pending.entry.trashId)
                    confirm = null
                },
                onDismiss = { confirm = null },
            )

            TrashConfirm.EmptyTrash -> TrashEmptyDialog(
                noteCount = state.items.size,
                onConfirm = {
                    onEmptyTrashClick()
                    confirm = null
                },
                onDismiss = { confirm = null },
            )
        }
    }
}

@Composable
private fun TrashDeleteDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trash_delete_title)) },
        text = { Text(stringResource(R.string.trash_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.trash_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun TrashEmptyDialog(
    noteCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trash_empty_confirm_title)) },
        text = {
            Text(pluralStringResource(R.plurals.trash_empty_confirm_message, noteCount, noteCount))
        },
        confirmButton = {
            TextButton(onClick = onConfirm, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.trash_empty_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun TrashRow(
    item: TrashEntry,
    isRestoring: Boolean,
    actionsEnabled: Boolean,
    onRestoreClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        colors = CardDefaults.cardColors(containerColor = LoomaTokens.colors.card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.excerpt.isNotBlank()) {
                    Text(
                        text = item.excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.trash_original_path, item.originalPath),
                    style = LoomaTokens.type.cardMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.trash_deleted_at, formatDateTime(item.deletedAt)),
                    style = LoomaTokens.type.cardMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier.width(96.dp).height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (rememberDelayedVisibility(isRestoring)) {
                    LoadingIndicator(Modifier.size(40.dp))
                } else {
                    Row {
                        IconButton(
                            onClick = onRestoreClick,
                            enabled = actionsEnabled,
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            LoomaIcon(
                                iconRes = R.drawable.ic_restore,
                                contentDescription = stringResource(R.string.trash_restore),
                            )
                        }
                        IconButton(
                            onClick = onDeleteClick,
                            enabled = actionsEnabled,
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            LoomaIcon(
                                iconRes = R.drawable.ic_delete,
                                contentDescription = stringResource(R.string.trash_delete),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun emptyTrashFeedback(
    outcome: TrashBulkOutcome?,
    actionFailedText: String,
    fullSuccess: (Int) -> String,
    partial: (Int, Int) -> String,
): String = when {
    outcome == null || outcome.succeeded == 0 -> actionFailedText
    outcome.isFullSuccess -> fullSuccess(outcome.total)
    else -> partial(outcome.succeeded, outcome.failed)
}
