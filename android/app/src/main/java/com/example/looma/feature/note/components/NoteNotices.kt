package com.example.looma.feature.note.components

import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.note.NoteUiState
import com.example.looma.ui.components.ErrorBanner
import com.example.looma.ui.components.ErrorBannerAction
import com.example.looma.ui.components.pageMargin

@Composable
internal fun NoteSaveBanner(
    state: NoteUiState,
    onRetry: () -> Unit,
    onReload: () -> Unit,
    onOverwrite: () -> Unit,
) {
    var shown by remember { mutableStateOf(state) }
    if (state.showSaveBanner) shown = state
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = state.showSaveBanner,
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
        state.exceedsSizeLimit && copyName != null -> stringResource(R.string.editor_too_large_recovered, copyName)
        state.exceedsSizeLimit -> stringResource(R.string.editor_too_large)
        state.isConflict && copyName != null -> stringResource(R.string.editor_conflict_conflict_copy, copyName)
        state.isConflict -> stringResource(R.string.editor_conflict)
        copyName != null -> stringResource(R.string.editor_save_failed_conflict_copy, copyName)
        else -> stringResource(R.string.editor_save_failed)
    }
    ErrorBanner(
        message = message,
        modifier = Modifier.padding(horizontal = pageMargin(), vertical = 4.dp),
    ) {
        if (state.isConflict) {
            ErrorBannerAction(
                label = stringResource(R.string.editor_reload_from_disk),
                onClick = onReload,
            )
        }
        ErrorBannerAction(
            label = stringResource(actionLabel(state)),
            onClick = if (state.isConflict) onOverwrite else onRetry,
        )
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
            TextButton(onClick = if (state.isConflict) onOverwrite else onRetry, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(actionLabel(state)))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.editor_discard_leave))
            }
        },
    )
}

private fun actionLabel(state: NoteUiState): Int =
    if (state.isConflict) R.string.editor_overwrite else R.string.editor_retry_save
