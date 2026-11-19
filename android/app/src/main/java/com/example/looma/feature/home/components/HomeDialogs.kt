@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.looma.R
import com.example.looma.domain.model.NoteProperties
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.feature.home.HomeDialogActions
import com.example.looma.feature.home.HomeFileDialog
import com.example.looma.feature.home.HomeFileError
import com.example.looma.ui.format.formatDateTime
import com.example.looma.ui.format.formatFileSize
import com.example.looma.ui.theme.LoomaTokens

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
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val supporting: (@Composable () -> Unit)? = if (error != null) {
        { Text(fileErrorText(error)) }
    } else {
        null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().imePadding().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(hint) },
                        singleLine = true,
                        isError = error != null,
                        supportingText = supporting,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!busy) onConfirm(value) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.dialog_cancel))
                        }
                        TextButton(onClick = { onConfirm(value) }, enabled = !busy) {
                            Text(confirmLabel)
                        }
                    }
                }
            }
        }
    }
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_confirm)) }
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
            style = LoomaTokens.type.cardBody,
            color = LoomaTokens.colors.muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = LoomaTokens.type.cardBody,
            color = LoomaTokens.colors.title,
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
                    style = LoomaTokens.type.cardBody,
                    color = LoomaTokens.colors.title,
                )
                if (noteCount > 0) {
                    Text(
                        text = stringResource(R.string.home_folder_delete_retains_files),
                        style = LoomaTokens.type.cardBody,
                        color = LoomaTokens.colors.title,
                    )
                }
                if (error != null) {
                    Text(
                        text = fileErrorText(error),
                        style = LoomaTokens.type.cardBody,
                        color = LoomaTokens.colors.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(
                    text = stringResource(R.string.home_folder_delete_confirm),
                    color = LoomaTokens.colors.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
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
                    style = LoomaTokens.type.cardBody,
                    color = LoomaTokens.colors.title,
                )
                if (error != null) {
                    Text(
                        text = fileErrorText(error),
                        style = LoomaTokens.type.cardBody,
                        color = LoomaTokens.colors.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(
                    text = stringResource(R.string.home_stalled_relocation_abandon),
                    color = LoomaTokens.colors.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
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
