package com.example.looma.feature.note.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.note.NoteUiState
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun NoteSaveBanner(
    state: NoteUiState,
    onRetry: () -> Unit,
    onReload: () -> Unit,
    onOverwrite: () -> Unit,
) {
    var shown by remember { mutableStateOf(state) }
    if (state.saveFailed) shown = state
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = state.saveFailed,
        enter = expandVertically(motion.defaultSpatialSpec<IntSize>()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.fastSpatialSpec<IntSize>()) + fadeOut(motion.fastEffectsSpec()),
    ) {
        SaveBannerContent(
            state = shown,
            onRetry = onRetry,
            onReload = onReload,
            onOverwrite = onOverwrite,
        )
    }
}

@Composable
private fun SaveBannerContent(
    state: NoteUiState,
    onRetry: () -> Unit,
    onReload: () -> Unit,
    onOverwrite: () -> Unit,
) {
    val copy = state.conflictCopy
    val copyName = copy?.relativePath?.substringAfterLast('/')
    val message = when {
        state.isConflict && copyName != null -> stringResource(R.string.editor_conflict_conflict_copy, copyName)
        state.isConflict -> stringResource(R.string.editor_conflict)
        copyName != null -> stringResource(R.string.editor_save_failed_conflict_copy, copyName)
        else -> stringResource(R.string.editor_save_failed)
    }
    val content = MaterialTheme.colorScheme.onErrorContainer
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = content)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = content,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                LoomaIcon(
                    iconRes = R.drawable.ic_info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = LoomaTokens.type.supporting,
                    color = content,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (state.isConflict) {
                    TextButton(onClick = onReload, colors = buttonColors) {
                        Text(stringResource(R.string.editor_reload_from_disk))
                    }
                }
                TextButton(
                    onClick = if (state.isConflict) onOverwrite else onRetry,
                    colors = buttonColors,
                ) {
                    Text(stringResource(actionLabel(state)))
                }
            }
        }
    }
}

@Composable
internal fun NoteLeaveDialog(
    state: NoteUiState,
    onRetry: () -> Unit,
    onOverwrite: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_leave_title)) },
        text = { Text(stringResource(R.string.editor_leave_message)) },
        confirmButton = {
            TextButton(onClick = if (state.isConflict) onOverwrite else onRetry) {
                Text(stringResource(actionLabel(state)))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.editor_discard_leave))
            }
        },
    )
}

private fun actionLabel(state: NoteUiState): Int =
    if (state.isConflict) R.string.editor_overwrite else R.string.editor_retry_save
