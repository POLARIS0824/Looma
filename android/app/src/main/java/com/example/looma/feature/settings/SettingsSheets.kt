@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.looma.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.data.settings.S3ImageBedConfig
import com.example.looma.domain.markdown.ToolbarItem
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens
import java.io.IOException
import kotlinx.coroutines.launch

@Composable
internal fun ImageBedSheet(
    config: S3ImageBedConfig,
    onSave: suspend (S3ImageBedConfig) -> Unit,
    credentialsUnavailable: Boolean,
    onDismiss: () -> Unit,
) {
    var endpoint by remember { mutableStateOf(config.endpoint) }
    var region by remember { mutableStateOf(config.region) }
    var bucket by remember { mutableStateOf(config.bucket) }
    var accessKey by remember { mutableStateOf(config.accessKey) }
    var secretKey by remember { mutableStateOf(config.secretKey) }
    var pathPrefix by remember { mutableStateOf(config.pathPrefix) }
    var publicBaseUrl by remember { mutableStateOf(config.publicBaseUrl) }
    var pathStyle by remember { mutableStateOf(config.pathStyle) }
    var publicRead by remember { mutableStateOf(config.publicRead) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val sheetState = rememberExpandedSheetState()
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_image_bed_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                color = LoomaTokens.colors.title,
            )
            if (credentialsUnavailable) {
                Text(stringResource(R.string.settings_image_bed_credentials_unavailable))
            }
            Text(
                text = stringResource(R.string.settings_image_bed_credentials_hint),
                style = MaterialTheme.typography.bodySmall,
                color = LoomaTokens.colors.muted,
            )
            ImageBedField(
                value = endpoint,
                label = stringResource(R.string.settings_image_bed_endpoint),
                onChange = { endpoint = it },
            )
            ImageBedField(
                value = region,
                label = stringResource(R.string.settings_image_bed_region),
                onChange = { region = it },
            )
            ImageBedField(
                value = bucket,
                label = stringResource(R.string.settings_image_bed_bucket),
                onChange = { bucket = it },
            )
            ImageBedField(
                value = accessKey,
                label = stringResource(R.string.settings_image_bed_access_key),
                onChange = { accessKey = it },
            )
            ImageBedField(
                value = secretKey,
                label = stringResource(R.string.settings_image_bed_secret_key),
                onChange = { secretKey = it },
                secret = true,
            )
            ImageBedField(
                value = pathPrefix,
                label = stringResource(R.string.settings_image_bed_path_prefix),
                onChange = { pathPrefix = it },
            )
            ImageBedField(
                value = publicBaseUrl,
                label = stringResource(R.string.settings_image_bed_public_url),
                onChange = { publicBaseUrl = it },
            )
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SettingsSwitchRow(
                    index = 0,
                    count = 2,
                    title = stringResource(R.string.settings_image_bed_path_style),
                    description = stringResource(R.string.settings_image_bed_path_style_desc),
                    checked = pathStyle,
                    enabled = true,
                    onCheckedChange = { pathStyle = it },
                )
                SettingsSwitchRow(
                    index = 1,
                    count = 2,
                    title = stringResource(R.string.settings_image_bed_public_read),
                    description = stringResource(R.string.settings_image_bed_public_read_desc),
                    checked = publicRead,
                    enabled = true,
                    onCheckedChange = { publicRead = it },
                )
            }
            if (saveFailed) {
                Text(
                    text = stringResource(R.string.settings_image_bed_save_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.dialog_cancel)) }
                TextButton(
                    enabled = !saving,
                    onClick = {
                        val updatedConfig = S3ImageBedConfig(
                            endpoint = endpoint.trim(),
                            region = region.trim(),
                            bucket = bucket.trim(),
                            accessKey = accessKey.trim(),
                            secretKey = secretKey,
                            pathPrefix = pathPrefix.trim(),
                            publicBaseUrl = publicBaseUrl.trim(),
                            pathStyle = pathStyle,
                            publicRead = publicRead,
                        )
                        saving = true
                        saveFailed = false
                        scope.launch {
                            try {
                                onSave(updatedConfig)
                            } catch (_: IOException) {
                                saveFailed = true
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.dialog_save)) }
            }
        }
    }
}

@Composable
private fun ImageBedField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = if (secret) {
            KeyboardOptions(keyboardType = KeyboardType.Password)
        } else {
            KeyboardOptions.Default
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ToolbarOrderSheet(
    current: List<ToolbarItem>,
    onSave: (List<ToolbarItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var order by remember { mutableStateOf(current.filter { it.isAvailableInToolbar() }) }

    val sheetState = rememberExpandedSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(
                text = stringResource(R.string.settings_toolbar_order),
                style = MaterialTheme.typography.headlineSmall,
                color = LoomaTokens.colors.title,
            )
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                order.forEachIndexed { index, item ->
                    val label = stringResource(item.labelRes())
                    val upLabel = stringResource(R.string.toolbar_move_up, label)
                    val downLabel = stringResource(R.string.toolbar_move_down, label)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, modifier = Modifier.weight(1f))
                        IconButton(
                            enabled = index > 0,
                            onClick = {
                                order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                            },
                        ) {
                            LoomaIcon(iconRes = R.drawable.ic_arrow_up, contentDescription = upLabel)
                        }
                        IconButton(
                            enabled = index < order.lastIndex,
                            onClick = {
                                order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                            },
                        ) {
                            LoomaIcon(iconRes = R.drawable.ic_arrow_down, contentDescription = downLabel)
                        }
                    }
                }
            }
            TextButton(onClick = {
                order = ToolbarItem.DEFAULT_ORDER.filter { it.isAvailableInToolbar() }
            }) { Text(stringResource(R.string.toolbar_reset)) }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                Button(onClick = {
                    onSave(order + current.filterNot { it.isAvailableInToolbar() })
                }) { Text(stringResource(R.string.dialog_save)) }
            }
        }
    }
}

private fun ToolbarItem.isAvailableInToolbar(): Boolean =
    this != ToolbarItem.InsertFile

private fun ToolbarItem.labelRes(): Int = when (this) {
    ToolbarItem.Undo -> R.string.editor_undo
    ToolbarItem.Redo -> R.string.editor_redo
    ToolbarItem.WikiLink -> R.string.editor_wikilink
    ToolbarItem.Bold -> R.string.editor_bold
    ToolbarItem.Italic -> R.string.editor_italic
    ToolbarItem.Strikethrough -> R.string.editor_strikethrough
    ToolbarItem.Highlight -> R.string.editor_highlight
    ToolbarItem.Link -> R.string.editor_link
    ToolbarItem.InlineCode -> R.string.editor_inline_code
    ToolbarItem.Heading1 -> R.string.editor_h1
    ToolbarItem.Heading2 -> R.string.editor_h2
    ToolbarItem.Heading3 -> R.string.editor_h3
    ToolbarItem.BulletList -> R.string.editor_bullet_list
    ToolbarItem.NumberedList -> R.string.editor_numbered_list
    ToolbarItem.Checklist -> R.string.editor_checklist
    ToolbarItem.Quote -> R.string.editor_quote
    ToolbarItem.CodeBlock -> R.string.editor_code_block
    ToolbarItem.Table -> R.string.editor_table
    ToolbarItem.Divider -> R.string.editor_divider
    ToolbarItem.InsertImage -> R.string.editor_insert_image
    ToolbarItem.InsertFile -> error("Unavailable toolbar item")
}
