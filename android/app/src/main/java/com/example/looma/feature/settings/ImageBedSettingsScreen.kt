package com.example.looma.feature.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.image.ImageLinkFormatter
import com.example.looma.data.image.ImageNaming
import com.example.looma.data.settings.AppSettings
import com.example.looma.data.settings.ImageLinkFormat
import com.example.looma.data.settings.ImageUploadTarget
import com.example.looma.data.settings.S3ImageBedConfig
import com.example.looma.ui.components.FeatureAction
import com.example.looma.ui.components.FeatureActionStyle
import com.example.looma.ui.components.FeaturePage
import com.example.looma.ui.components.FeatureStatus
import com.example.looma.ui.components.FeatureStatusCard
import com.example.looma.ui.components.FeatureStatusTone
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.SectionHeader
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun ImageBedSettingsRoute(
    viewModel: ImageBedSettingsViewModel,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showConfigSheet by rememberSaveable { mutableStateOf(false) }
    var showNamingSheet by rememberSaveable { mutableStateOf(false) }
    var showLinkFormatDialog by rememberSaveable { mutableStateOf(false) }
    var showUrlSuffixDialog by rememberSaveable { mutableStateOf(false) }

    ImageBedSettingsScreen(
        uiState = uiState,
        testState = if (showConfigSheet) S3ConnectionTestState.Idle else uiState.testState,
        onSelectTarget = viewModel::setUploadTarget,
        onOpenConfig = {
            viewModel.resetTestState()
            showConfigSheet = true
        },
        onTestConnection = viewModel::testConnection,
        onOpenNaming = { showNamingSheet = true },
        onOpenLinkFormat = { showLinkFormatDialog = true },
        onOpenUrlSuffix = { showUrlSuffixDialog = true },
        onCompressEnabledChange = viewModel::setCompressEnabled,
        onCompressQualityChange = viewModel::setCompressQuality,
        onConvertToWebpChange = viewModel::setConvertToWebp,
        onOverwriteExistingChange = viewModel::setOverwriteExisting,
        onEncodeUrlChange = viewModel::setEncodeUrl,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )

    if (showConfigSheet) {
        ImageBedConfigSheet(
            config = uiState.settings.imageBed,
            credentialsUnavailable = uiState.settings.s3CredentialsUnavailable,
            testState = uiState.testState,
            onTestConnection = viewModel::testConnection,
            onResetTestState = viewModel::resetTestState,
            onSave = { config ->
                viewModel.saveConfig(config)
                showConfigSheet = false
            },
            onDismiss = {
                viewModel.resetTestState()
                showConfigSheet = false
            },
        )
    }

    if (showNamingSheet) {
        ImageNamingSheet(
            currentTemplate = uiState.settings.imageBed.namingTemplate,
            pathPrefix = uiState.settings.imageBed.pathPrefix,
            onSave = { template ->
                viewModel.setNamingTemplate(template)
                showNamingSheet = false
            },
            onDismiss = { showNamingSheet = false },
        )
    }

    if (showLinkFormatDialog) {
        LinkFormatDialog(
            currentFormat = uiState.settings.imageBed.linkFormat,
            currentCustomTemplate = uiState.settings.imageBed.customLinkTemplate,
            onSave = { format, customTemplate ->
                viewModel.setLinkFormat(
                    format = format,
                    customTemplate = if (format == ImageLinkFormat.Custom) customTemplate else null,
                )
                showLinkFormatDialog = false
            },
            onDismiss = { showLinkFormatDialog = false },
        )
    }

    if (showUrlSuffixDialog) {
        UrlSuffixDialog(
            currentSuffix = uiState.settings.imageBed.urlSuffix,
            onSave = { suffix ->
                viewModel.setUrlSuffix(suffix)
                showUrlSuffixDialog = false
            },
            onDismiss = { showUrlSuffixDialog = false },
        )
    }
}

@Composable
internal fun ImageBedSettingsScreen(
    uiState: ImageBedSettingsUiState,
    testState: S3ConnectionTestState,
    onSelectTarget: (ImageUploadTarget) -> Unit,
    onOpenConfig: () -> Unit,
    onTestConnection: (S3ImageBedConfig) -> Unit,
    onOpenNaming: () -> Unit,
    onOpenLinkFormat: () -> Unit,
    onOpenUrlSuffix: () -> Unit,
    onCompressEnabledChange: (Boolean) -> Unit,
    onCompressQualityChange: (Int) -> Unit,
    onConvertToWebpChange: (Boolean) -> Unit,
    onOverwriteExistingChange: (Boolean) -> Unit,
    onEncodeUrlChange: (Boolean) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = uiState.settings
    val config = settings.imageBed
    val isConfigured = !settings.s3CredentialsUnavailable && config.isConfigured

    FeaturePage(
        iconRes = R.drawable.ic_editor_image,
        title = stringResource(R.string.settings_image_bed_intro_title),
        subtitle = stringResource(R.string.settings_image_bed_intro),
        modifier = modifier,
        topBar = { scrollBehavior ->
            TopAppBar(
                title = { Text(stringResource(R.string.settings_image_bed)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp, shapes = IconButtonDefaults.shapes()) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_arrow_back,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        actions = {
            if (uiState.isLoaded) {
                ImageBedActions(
                    config = config,
                    isConfigured = isConfigured,
                    testState = testState,
                    onOpenConfig = onOpenConfig,
                    onTestConnection = onTestConnection,
                )
            }
        },
    ) {
        FeatureStatusCard(
            status = imageBedStatus(settings = settings),
            loading = !uiState.isLoaded,
        )
        if (testState !is S3ConnectionTestState.Idle) {
            Box(Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp)) {
                S3TestFeedback(testState = testState)
            }
        }
        Spacer(Modifier.height(32.dp))
        UploadDestinationSection(
            current = settings.imageUploadTarget,
            imageBedEnabled = isConfigured,
            onSelect = onSelectTarget,
        )
        Spacer(Modifier.height(24.dp))
        ImageBedStorageSection(
            settings = settings,
            onOpenConfig = { if (uiState.isLoaded) onOpenConfig() },
        )
        Spacer(Modifier.height(24.dp))
        UploadOptionsSection(
            config = config,
            onOpenNaming = onOpenNaming,
            onOpenLinkFormat = onOpenLinkFormat,
            onOpenUrlSuffix = onOpenUrlSuffix,
            onCompressEnabledChange = onCompressEnabledChange,
            onCompressQualityChange = onCompressQualityChange,
            onConvertToWebpChange = onConvertToWebpChange,
            onOverwriteExistingChange = onOverwriteExistingChange,
            onEncodeUrlChange = onEncodeUrlChange,
        )
    }
}

@Composable
private fun FlowRowScope.ImageBedActions(
    config: S3ImageBedConfig,
    isConfigured: Boolean,
    testState: S3ConnectionTestState,
    onOpenConfig: () -> Unit,
    onTestConnection: (S3ImageBedConfig) -> Unit,
) {
    FeatureAction(
        text = stringResource(
            if (isConfigured) R.string.settings_image_bed_edit_config
            else R.string.settings_image_bed_configure,
        ),
        onClick = onOpenConfig,
        style = if (isConfigured) FeatureActionStyle.Tonal else FeatureActionStyle.Filled,
    )
    if (isConfigured) {
        FeatureAction(
            text = stringResource(R.string.settings_sync_test),
            onClick = { onTestConnection(config) },
            style = FeatureActionStyle.Filled,
            enabled = testState !is S3ConnectionTestState.Testing,
        )
    }
}

@Composable
private fun imageBedStatus(settings: AppSettings): FeatureStatus {
    val config = settings.imageBed
    val title = stringResource(R.string.settings_image_bed)
    if (settings.s3CredentialsUnavailable) {
        return FeatureStatus(
            label = stringResource(R.string.settings_sync_status_unavailable),
            title = title,
            detail = stringResource(R.string.settings_image_bed_credentials_unavailable),
            tone = FeatureStatusTone.Error,
        )
    }
    if (settings.imageUploadTarget == ImageUploadTarget.LocalAttachment) {
        val note = if (config.isConfigured) {
            stringResource(
                R.string.settings_image_bed_configured,
                config.bucket,
                config.publicBaseUrl.ifBlank { config.endpoint },
            )
        } else {
            stringResource(R.string.settings_image_bed_not_configured)
        }
        return FeatureStatus(
            label = stringResource(R.string.settings_image_destination_local),
            title = stringResource(R.string.settings_image_destination_local),
            detail = stringResource(R.string.settings_image_destination_local_desc),
            note = note,
        )
    }
    if (!config.isConfigured) {
        return FeatureStatus(
            label = stringResource(R.string.settings_image_bed_not_configured),
            title = title,
            detail = stringResource(R.string.settings_image_bed_not_configured),
        )
    }
    return FeatureStatus(
        label = stringResource(R.string.settings_sync_status_ready),
        title = title,
        detail = stringResource(
            R.string.settings_image_bed_configured,
            config.bucket,
            config.publicBaseUrl.ifBlank { config.endpoint },
        ),
        note = stringResource(R.string.settings_image_destination_bed_desc),
    )
}

private data class UploadTargetOption(
    val target: ImageUploadTarget,
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
)

private val UploadTargetOptions = listOf(
    UploadTargetOption(
        target = ImageUploadTarget.LocalAttachment,
        iconRes = R.drawable.ic_folder,
        labelRes = R.string.settings_image_destination_local,
        descRes = R.string.settings_image_destination_local_desc,
    ),
    UploadTargetOption(
        target = ImageUploadTarget.ImageBed,
        iconRes = R.drawable.ic_cloud,
        labelRes = R.string.settings_image_destination_bed,
        descRes = R.string.settings_image_destination_bed_desc,
    ),
)

@Composable
private fun UploadDestinationSection(
    current: ImageUploadTarget,
    imageBedEnabled: Boolean,
    onSelect: (ImageUploadTarget) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.settings_image_destination))
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            UploadTargetOptions.forEachIndexed { index, option ->
                val selected = current == option.target
                val enabled = option.target != ImageUploadTarget.ImageBed || imageBedEnabled
                val supportingText = if (!enabled) {
                    stringResource(R.string.settings_image_destination_bed_unavailable)
                } else {
                    stringResource(option.descRes)
                }
                SegmentedListItem(
                    selected = selected,
                    onClick = { onSelect(option.target) },
                    shapes = ListItemDefaults.segmentedShapes(index, UploadTargetOptions.size),
                    enabled = enabled,
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
                    leadingContent = { LoomaIcon(iconRes = option.iconRes, contentDescription = null) },
                    supportingContent = { Text(supportingText) },
                    trailingContent = {
                        RadioButton(selected = selected, onClick = null, enabled = enabled)
                    },
                ) {
                    Text(stringResource(option.labelRes))
                }
            }
        }
    }
}

@Composable
private fun ImageBedStorageSection(
    settings: AppSettings,
    onOpenConfig: () -> Unit,
) {
    val description = when {
        settings.s3CredentialsUnavailable ->
            stringResource(R.string.settings_image_bed_credentials_unavailable)
        settings.imageBed.isConfigured -> stringResource(
            R.string.settings_image_bed_configured,
            settings.imageBed.bucket,
            settings.imageBed.endpoint,
        )
        else -> stringResource(R.string.settings_image_bed_not_configured)
    }
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.settings_image_bed_storage_section))
        SegmentedListItem(
            onClick = onOpenConfig,
            shapes = ListItemDefaults.segmentedShapes(0, 1),
            colors = ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
            leadingContent = { LoomaIcon(iconRes = R.drawable.ic_cloud, contentDescription = null) },
            supportingContent = { Text(description) },
        ) {
            Text(stringResource(R.string.settings_image_bed_sheet_title))
        }
    }
}

@Composable
private fun UploadOptionsSection(
    config: S3ImageBedConfig,
    onOpenNaming: () -> Unit,
    onOpenLinkFormat: () -> Unit,
    onOpenUrlSuffix: () -> Unit,
    onCompressEnabledChange: (Boolean) -> Unit,
    onCompressQualityChange: (Int) -> Unit,
    onConvertToWebpChange: (Boolean) -> Unit,
    onOverwriteExistingChange: (Boolean) -> Unit,
    onEncodeUrlChange: (Boolean) -> Unit,
) {
    val showQuality = config.compressEnabled || config.convertToWebp
    val totalItems = if (showQuality) 8 else 7
    var itemIndex = 0
    val cardColors = ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )

    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.settings_image_bed_options_section))
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            val namingPreview = remember(config.namingTemplate, config.pathPrefix) {
                ImageNaming.preview(config.namingTemplate, config.pathPrefix)
            }
            SegmentedListItem(
                onClick = onOpenNaming,
                shapes = ListItemDefaults.segmentedShapes(itemIndex++, totalItems),
                colors = cardColors,
                modifier = Modifier.fillMaxWidth(),
                supportingContent = {
                    Column {
                        Text(config.namingTemplate)
                        Text(stringResource(R.string.settings_image_naming_preview, namingPreview))
                    }
                },
            ) {
                Text(stringResource(R.string.settings_image_naming_rule))
            }

            val linkPreview = remember(config.linkFormat, config.customLinkTemplate) {
                ImageLinkFormatter.formatSnippet(
                    url = "https://example.com/photo.png",
                    alt = "photo",
                    format = config.linkFormat,
                    customTemplate = config.customLinkTemplate,
                )
            }
            SegmentedListItem(
                onClick = onOpenLinkFormat,
                shapes = ListItemDefaults.segmentedShapes(itemIndex++, totalItems),
                colors = cardColors,
                modifier = Modifier.fillMaxWidth(),
                supportingContent = {
                    Column {
                        Text(linkFormatLabel(config.linkFormat))
                        Text(linkPreview)
                    }
                },
            ) {
                Text(stringResource(R.string.settings_image_link_format))
            }

            SegmentedListItem(
                onClick = onOpenUrlSuffix,
                shapes = ListItemDefaults.segmentedShapes(itemIndex++, totalItems),
                colors = cardColors,
                modifier = Modifier.fillMaxWidth(),
                supportingContent = {
                    Text(
                        config.urlSuffix.ifBlank {
                            stringResource(R.string.settings_image_url_suffix_none)
                        },
                    )
                },
            ) {
                Text(stringResource(R.string.settings_image_url_suffix))
            }

            OptionSwitchRow(
                index = itemIndex++,
                count = totalItems,
                title = stringResource(R.string.settings_image_compress),
                description = stringResource(R.string.settings_image_compress_desc),
                checked = config.compressEnabled,
                onCheckedChange = onCompressEnabledChange,
            )

            if (showQuality) {
                QualitySliderRow(
                    index = itemIndex++,
                    count = totalItems,
                    quality = config.compressQuality,
                    onQualityChange = onCompressQualityChange,
                )
            }

            OptionSwitchRow(
                index = itemIndex++,
                count = totalItems,
                title = stringResource(R.string.settings_image_convert_webp),
                description = stringResource(R.string.settings_image_convert_webp_desc),
                checked = config.convertToWebp,
                onCheckedChange = onConvertToWebpChange,
            )

            OptionSwitchRow(
                index = itemIndex++,
                count = totalItems,
                title = stringResource(R.string.settings_image_overwrite_existing),
                description = stringResource(R.string.settings_image_overwrite_existing_desc),
                checked = config.overwriteExisting,
                onCheckedChange = onOverwriteExistingChange,
            )

            OptionSwitchRow(
                index = itemIndex,
                count = totalItems,
                title = stringResource(R.string.settings_image_encode_url),
                description = stringResource(R.string.settings_image_encode_url_desc),
                checked = config.encodeUrl,
                onCheckedChange = onEncodeUrlChange,
            )
        }
    }
}

@Composable
private fun OptionSwitchRow(
    index: Int,
    count: Int,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) {
        Text(title)
    }
}

private val QualityPresets = listOf(60, 75, 85, 95)

@Composable
private fun QualitySliderRow(
    index: Int,
    count: Int,
    quality: Int,
    onQualityChange: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ListItemDefaults.segmentedShapes(index, count).shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_image_compress_quality, quality),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                QualityPresets.forEach { preset ->
                    FilterChip(
                        selected = quality == preset,
                        onClick = { onQualityChange(preset) },
                        label = { Text("$preset%") },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageNamingSheet(
    currentTemplate: String,
    pathPrefix: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val templateState = rememberTextFieldState(currentTemplate)
    val sheetState = rememberExpandedSheetState()
    val preview = ImageNaming.preview(templateState.text.toString(), pathPrefix)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LoomaTokens.colors.sheet,
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
                text = stringResource(R.string.settings_image_naming_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_image_naming_presets),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ImageNaming.PRESETS.forEach { preset ->
                    val selected = templateState.text.toString() == preset.template
                    FilterChip(
                        selected = selected,
                        onClick = { templateState.setTextAndPlaceCursorAtEnd(preset.template) },
                        label = { Text(namingPresetLabel(preset.id)) },
                    )
                }
            }
            OutlinedTextField(
                state = templateState,
                label = { Text(stringResource(R.string.settings_image_naming_template_label)) },
                labelPosition = TextFieldLabelPosition.Inside(),
                shape = OutlinedTextFieldDefaults.roundedShape,
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.settings_image_naming_preview, preview),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_image_naming_variables),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ImageNaming.VARIABLES.forEach { variable ->
                    AssistChip(
                        onClick = {
                            templateState.setTextAndPlaceCursorAtEnd(
                                templateState.text.toString() + variable,
                            )
                        },
                        label = { Text(variable) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.dialog_cancel))
                }
                Button(
                    onClick = { onSave(templateState.text.toString()) },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.dialog_save))
                }
            }
        }
    }
}

@Composable
private fun LinkFormatDialog(
    currentFormat: ImageLinkFormat,
    currentCustomTemplate: String,
    onSave: (ImageLinkFormat, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(currentFormat) }
    val customState = rememberTextFieldState(currentCustomTemplate)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_image_link_format_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ImageLinkFormat.entries.forEach { format ->
                        val optionSelected = format == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .selectable(
                                    selected = optionSelected,
                                    onClick = { selected = format },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = optionSelected, onClick = null)
                            Text(
                                text = linkFormatLabel(format),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                if (selected == ImageLinkFormat.Custom) {
                    OutlinedTextField(
                        state = customState,
                        label = { Text(stringResource(R.string.settings_image_link_custom_template)) },
                        labelPosition = TextFieldLabelPosition.Inside(),
                        shape = OutlinedTextFieldDefaults.roundedShape,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        supportingText = { Text(stringResource(R.string.settings_image_link_custom_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(selected, customState.text.toString()) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.dialog_save))
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
private fun UrlSuffixDialog(
    currentSuffix: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val suffixState = rememberTextFieldState(currentSuffix)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_image_url_suffix)) },
        text = {
            OutlinedTextField(
                state = suffixState,
                label = { Text(stringResource(R.string.settings_image_url_suffix)) },
                labelPosition = TextFieldLabelPosition.Inside(),
                shape = OutlinedTextFieldDefaults.roundedShape,
                lineLimits = TextFieldLineLimits.SingleLine,
                supportingText = { Text(stringResource(R.string.settings_image_url_suffix_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(suffixState.text.toString()) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.dialog_save))
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
private fun namingPresetLabel(id: String): String = when (id) {
    "hash" -> stringResource(R.string.settings_image_naming_preset_hash)
    "date-hash" -> stringResource(R.string.settings_image_naming_preset_date_hash)
    "date-name" -> stringResource(R.string.settings_image_naming_preset_date_name)
    "timestamp-name" -> stringResource(R.string.settings_image_naming_preset_timestamp_name)
    "note-folder" -> stringResource(R.string.settings_image_naming_preset_note_folder)
    else -> id
}

@Composable
private fun linkFormatLabel(format: ImageLinkFormat): String = stringResource(
    when (format) {
        ImageLinkFormat.Markdown -> R.string.settings_image_link_format_markdown
        ImageLinkFormat.Html -> R.string.settings_image_link_format_html
        ImageLinkFormat.PlainUrl -> R.string.settings_image_link_format_plain
        ImageLinkFormat.Custom -> R.string.settings_image_link_format_custom
    },
)
