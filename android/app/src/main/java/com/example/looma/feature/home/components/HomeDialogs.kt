package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.model.NoteProperties
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.feature.home.HomeDialogActions
import com.example.looma.feature.home.HomeFileDialog
import com.example.looma.feature.home.HomeFileError
import com.example.looma.ui.format.formatDateTime
import com.example.looma.ui.format.formatFileSize
import com.example.looma.ui.theme.TABULAR_FIGURES

@Composable
internal fun HomeFileDialogs(
    dialog: HomeFileDialog?,
    isActionRunning: Boolean,
    actions: HomeDialogActions,
) {
    when (dialog) {
        null, is HomeFileDialog.LoadingProperties -> Unit

        is HomeFileDialog.Rename -> key(dialog.id) {
            HomeTextInputDialog(
                title = stringResource(R.string.home_rename_title),
                hint = stringResource(R.string.home_rename_hint),
                initialValue = dialog.initialName,
                confirmLabel = stringResource(R.string.dialog_save),
                error = dialog.error,
                busy = isActionRunning,
                onConfirm = { name -> actions.onConfirmRename(dialog, name) },
                onDismiss = actions.onDialogDismiss,
            )
        }

        is HomeFileDialog.Properties -> HomePropertiesDialog(
            properties = dialog.properties,
            onDismiss = actions.onDialogDismiss,
        )

        is HomeFileDialog.NewFolder -> key(dialog.id) {
            HomeTextInputDialog(
                title = stringResource(R.string.home_new_folder_title),
                hint = stringResource(R.string.home_new_folder_hint),
                initialValue = "",
                confirmLabel = stringResource(R.string.dialog_create),
                error = dialog.error,
                busy = isActionRunning,
                onConfirm = actions.onConfirmNewFolder,
                onDismiss = actions.onDialogDismiss,
            )
        }

        is HomeFileDialog.RenameFolder -> key(dialog.id) {
            HomeTextInputDialog(
                title = stringResource(R.string.home_folder_rename_title),
                hint = stringResource(R.string.home_folder_rename_hint),
                initialValue = dialog.initialName,
                confirmLabel = stringResource(R.string.dialog_save),
                error = dialog.error,
                busy = isActionRunning,
                onConfirm = actions.onConfirmRenameFolder,
                onDismiss = actions.onDialogDismiss,
            )
        }

        is HomeFileDialog.DeleteFolder -> HomeDeleteFolderDialog(
            folderName = dialog.folderName,
            noteCount = dialog.noteCount,
            error = dialog.error,
            busy = isActionRunning,
            onConfirm = actions.onConfirmDeleteFolder,
            onDismiss = actions.onDialogDismiss,
        )

        is HomeFileDialog.AbandonStalledRelocation -> HomeAbandonStalledRelocationDialog(
            stalled = dialog.stalled,
            error = dialog.error,
            busy = isActionRunning,
            onConfirm = actions.onConfirmAbandonStalledRelocation,
            onDismiss = actions.onDialogDismiss,
        )
    }
}

@Composable
internal fun HomeTextInputDialog(
    title: String,
    hint: String,
    initialValue: String,
    confirmLabel: String,
    error: HomeFileError?,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val value = rememberSaveable(initialValue, saver = TextFieldState.Saver) { TextFieldState(initialValue) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        if (initialValue.isNotEmpty()) {
            value.edit { selection = TextRange(0, length) }
        }
    }
    val supporting: (@Composable () -> Unit)? = if (error != null) {
        { Text(fileErrorText(error)) }
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            OutlinedTextField(
                state = value,
                label = { Text(hint) },
                lineLimits = TextFieldLineLimits.SingleLine,
                isError = error != null,
                supportingText = supporting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = { if (!busy) onConfirm(value.text.toString()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && !busy) {
                                onConfirm(value.text.toString())
                            }
                            true
                        } else {
                            false
                        }
                    },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.text.toString()) },
                enabled = !busy,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
internal fun HomePropertiesDialog(
    properties: NoteProperties,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_info_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PropertyRow(
                    label = stringResource(R.string.home_info_path),
                    value = properties.path.relativePath.replace('/', '›'),
                )
                PropertyRow(
                    label = stringResource(R.string.home_info_size),
                    value = formatFileSize(context, properties.sizeBytes),
                )
                PropertyRow(
                    label = stringResource(R.string.home_info_modified),
                    value = formatDateTime(properties.modifiedAt),
                )
                PropertyRow(
                    label = stringResource(R.string.home_info_words),
                    value = properties.words.toString(),
                )
                PropertyRow(
                    label = stringResource(R.string.home_info_characters),
                    value = properties.characters.toString(),
                )
                if (properties.checkboxesTotal > 0) {
                    PropertyRow(
                        label = stringResource(R.string.home_info_checklist),
                        value = stringResource(
                            R.string.home_info_checklist_value,
                            properties.checkboxesDone,
                            properties.checkboxesTotal,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.dialog_confirm)) }
        },
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (value.any(Char::isDigit)) {
                MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR_FIGURES)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
internal fun HomeDeleteFolderDialog(
    folderName: String,
    noteCount: Int,
    error: HomeFileError?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.home_folder_delete_title, folderName),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (noteCount > 0) {
                        pluralStringResource(
                            R.plurals.home_folder_delete_message,
                            noteCount,
                            noteCount,
                        )
                    } else {
                        stringResource(R.string.home_folder_delete_message_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (noteCount > 0) {
                    Text(
                        text = stringResource(R.string.home_folder_delete_retains_files),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (error != null) {
                    Text(
                        text = fileErrorText(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                Text(
                    text = stringResource(R.string.home_folder_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
internal fun HomeAbandonStalledRelocationDialog(
    stalled: StalledRelocation,
    error: HomeFileError?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.home_stalled_relocation_abandon_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.home_stalled_relocation_abandon_message,
                        stalled.sourcePath,
                        stalled.destinationPath,
                        stalled.journalId,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (error != null) {
                    Text(
                        text = fileErrorText(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                Text(
                    text = stringResource(R.string.home_stalled_relocation_abandon),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun fileErrorText(error: HomeFileError): String = stringResource(
    when (error) {
        HomeFileError.EMPTY_NAME -> R.string.home_error_empty_name
        HomeFileError.INVALID_NAME -> R.string.home_error_invalid_name
        HomeFileError.NAME_TAKEN -> R.string.home_error_duplicate_name
        HomeFileError.RESTORE_CONFLICT -> R.string.home_error_duplicate_name
        HomeFileError.GENERIC -> R.string.home_error_action
    },
)
