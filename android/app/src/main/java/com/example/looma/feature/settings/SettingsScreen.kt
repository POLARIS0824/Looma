@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.looma.feature.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.settings.AppLanguage
import com.example.looma.data.settings.AppLocale
import com.example.looma.data.settings.AppSettings
import com.example.looma.data.settings.ImageUploadTarget
import com.example.looma.data.settings.S3ImageBedConfig
import com.example.looma.data.settings.ThemePreset
import com.example.looma.domain.markdown.ToolbarItem
import com.example.looma.feature.vault.VaultSetupError
import com.example.looma.feature.vault.VaultSetupViewModel
import com.example.looma.feature.vault.rememberVaultPickerActions
import com.example.looma.feature.vault.vaultErrorText
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens
import com.example.looma.ui.theme.presetScheme
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun SettingsRoute(
    vaultViewModel: VaultSetupViewModel,
    settingsViewModel: SettingsViewModel,
    onChangeVaultClick: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vaultState by vaultViewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val picker = rememberVaultPickerActions(vaultViewModel)
    var hasStorageAccess by remember { mutableStateOf(picker.hasAccess()) }
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vaultViewModel.refreshVaultStatus()
        hasStorageAccess = picker.hasAccess()
    }

    SettingsScreen(
        vaultName = vaultState.currentFolderName,
        vaultPath = vaultState.currentFolderPath,
        vaultError = vaultState.error,
        hasStorageAccess = hasStorageAccess,
        isVaultBusy = vaultState.isBusy,
        versionName = versionName,
        settings = settings,
        language = AppLocale.current(),
        onChangeVaultClick = onChangeVaultClick,
        onStorageAccessClick = picker.chooseFolder,
        onDynamicColorChange = settingsViewModel::setDynamicColor,
        onThemePresetSelect = settingsViewModel::setThemePreset,
        onLanguageSelect = AppLocale::apply,
        onKeepEmptyNotesChange = settingsViewModel::setKeepEmptyNotes,
        onShowInlineTitleChange = settingsViewModel::setShowInlineTitle,
        onToolbarOrderChange = settingsViewModel::setToolbarOrder,
        onImageUploadTargetChange = settingsViewModel::setImageUploadTarget,
        onImageBedChange = settingsViewModel::setS3ImageBed,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@Composable
fun SettingsScreen(
    vaultName: String?,
    vaultPath: String?,
    vaultError: VaultSetupError?,
    hasStorageAccess: Boolean,
    isVaultBusy: Boolean,
    versionName: String,
    settings: AppSettings,
    language: AppLanguage,
    onChangeVaultClick: () -> Unit,
    onStorageAccessClick: () -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onThemePresetSelect: (ThemePreset) -> Unit,
    onLanguageSelect: (AppLanguage) -> Unit,
    onKeepEmptyNotesChange: (Boolean) -> Unit,
    onShowInlineTitleChange: (Boolean) -> Unit,
    onToolbarOrderChange: (List<ToolbarItem>) -> Unit,
    onImageUploadTargetChange: (ImageUploadTarget) -> Unit,
    onImageBedChange: suspend (S3ImageBedConfig) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var themeDialogVisible by remember { mutableStateOf(false) }
    var languageDialogVisible by remember { mutableStateOf(false) }
    var toolbarSheetVisible by remember { mutableStateOf(false) }
    var imageTargetDialogVisible by remember { mutableStateOf(false) }
    var imageBedSheetVisible by remember { mutableStateOf(false) }
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicColorActive = settings.dynamicColor && dynamicColorSupported
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LoomaTokens.colors.page,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_arrow_back,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LoomaTokens.colors.page),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsGroup(stringResource(R.string.settings_section_vault)) {
                    SettingsRow(
                        index = 0,
                        count = 1,
                        title = vaultName ?: stringResource(R.string.settings_vault_none),
                        description = vaultPath,
                        trailing = {
                            TextButton(onClick = onChangeVaultClick, enabled = !isVaultBusy) {
                                Text(stringResource(R.string.settings_vault_change))
                            }
                        },
                    )
                    vaultError?.let { failure ->
                        Text(
                            text = vaultErrorText(failure),
                            style = LoomaTokens.type.supporting,
                            color = LoomaTokens.colors.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                SettingsGroup(stringResource(R.string.settings_section_appearance)) {
                    SettingsSwitchRow(
                        index = 0,
                        count = 2,
                        title = stringResource(R.string.settings_dynamic_color),
                        description = stringResource(
                            if (dynamicColorSupported) {
                                R.string.settings_dynamic_color_desc
                            } else {
                                R.string.settings_dynamic_color_unavailable
                            },
                        ),
                        checked = dynamicColorActive,
                        enabled = dynamicColorSupported,
                        onCheckedChange = onDynamicColorChange,
                    )
                    SettingsRow(
                        index = 1,
                        count = 2,
                        title = stringResource(R.string.settings_theme),
                        description = if (dynamicColorActive) {
                            stringResource(R.string.settings_theme_wallpaper)
                        } else {
                            themeName(settings.themePreset)
                        },
                        hint = if (dynamicColorActive) {
                            stringResource(R.string.settings_theme_wallpaper_hint)
                        } else {
                            null
                        },
                        trailing = {
                            ThemeSwatch(
                                preset = settings.themePreset,
                                useWallpaper = dynamicColorActive,
                            )
                        },
                        onClick = { themeDialogVisible = true },
                    )
                }

                SettingsGroup(stringResource(R.string.settings_section_language)) {
                    SettingsRow(
                        index = 0,
                        count = 1,
                        title = stringResource(R.string.settings_language),
                        description = languageName(language),
                        onClick = { languageDialogVisible = true },
                    )
                }

                SettingsGroup(stringResource(R.string.settings_section_notes)) {
                    SettingsRow(
                        index = 0,
                        count = 3,
                        title = stringResource(R.string.settings_toolbar_order),
                        description = stringResource(R.string.settings_toolbar_order_desc),
                        onClick = { toolbarSheetVisible = true },
                    )
                    SettingsSwitchRow(
                        index = 1,
                        count = 3,
                        title = stringResource(R.string.settings_keep_empty_notes),
                        description = stringResource(R.string.settings_keep_empty_notes_desc),
                        checked = settings.keepEmptyNotes,
                        enabled = true,
                        onCheckedChange = onKeepEmptyNotesChange,
                    )
                    SettingsSwitchRow(
                        index = 2,
                        count = 3,
                        title = stringResource(R.string.settings_show_inline_title),
                        description = stringResource(R.string.settings_show_inline_title_desc),
                        checked = settings.showInlineTitle,
                        enabled = true,
                        onCheckedChange = onShowInlineTitleChange,
                    )
                }

                SettingsGroup(stringResource(R.string.settings_section_images)) {
                    SettingsRow(
                        index = 0,
                        count = 2,
                        title = stringResource(R.string.settings_image_destination),
                        description = imageTargetName(settings.imageUploadTarget),
                        onClick = { imageTargetDialogVisible = true },
                    )
                    SettingsRow(
                        index = 1,
                        count = 2,
                        title = stringResource(R.string.settings_image_bed),
                        description = if (settings.s3CredentialsUnavailable) {
                            stringResource(R.string.settings_image_bed_credentials_unavailable)
                        } else if (settings.imageBed.isConfigured) {
                            stringResource(
                                R.string.settings_image_bed_configured,
                                settings.imageBed.bucket,
                                settings.imageBed.endpoint,
                            )
                        } else {
                            stringResource(R.string.settings_image_bed_not_configured)
                        },
                        onClick = { imageBedSheetVisible = true },
                    )
                }

                SettingsGroup(stringResource(R.string.settings_section_storage)) {
                    SettingsRow(
                        index = 0,
                        count = 1,
                        title = stringResource(
                            if (hasStorageAccess) {
                                R.string.settings_storage_allowed
                            } else {
                                R.string.settings_storage_denied
                            },
                        ),
                        trailing = if (!hasStorageAccess) {
                            {
                                TextButton(onClick = onStorageAccessClick) {
                                    Text(stringResource(R.string.settings_storage_grant))
                                }
                            }
                        } else {
                            null
                        },
                    )
                }

                SettingsGroup(stringResource(R.string.settings_section_about)) {
                    SettingsRow(
                        index = 0,
                        count = 1,
                        title = stringResource(R.string.app_name),
                        description = stringResource(R.string.settings_version, versionName),
                    )
                }
            }
        }
    }

    if (themeDialogVisible) {
        ThemePresetDialog(
            current = settings.themePreset,
            onSelect = {
                onThemePresetSelect(it)
                themeDialogVisible = false
            },
            onDismiss = { themeDialogVisible = false },
        )
    }

    if (languageDialogVisible) {
        LanguageDialog(
            current = language,
            onSelect = {
                onLanguageSelect(it)
                languageDialogVisible = false
            },
            onDismiss = { languageDialogVisible = false },
        )
    }
    if (toolbarSheetVisible) {
        ToolbarOrderSheet(
            current = settings.toolbarOrder,
            onSave = { onToolbarOrderChange(it); toolbarSheetVisible = false },
            onDismiss = { toolbarSheetVisible = false },
        )
    }
    if (imageTargetDialogVisible) {
        ImageTargetDialog(
            current = settings.imageUploadTarget,
            onSelect = {
                onImageUploadTargetChange(it)
                imageTargetDialogVisible = false
            },
            onDismiss = { imageTargetDialogVisible = false },
        )
    }
    if (imageBedSheetVisible) {
        ImageBedSheet(
            config = settings.imageBed,
            onSave = {
                onImageBedChange(it)
                imageBedSheetVisible = false
            },
            credentialsUnavailable = settings.s3CredentialsUnavailable,
            onDismiss = { imageBedSheetVisible = false },
        )
    }
}

@Composable
private fun ImageTargetDialog(
    current: ImageUploadTarget,
    onSelect: (ImageUploadTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_image_destination_dialog_title)) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ImageUploadTarget.entries.forEach { target ->
                    val selected = target == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(
                                selected = selected,
                                onClick = { onSelect(target) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = imageTargetName(target),
                                style = LoomaTokens.type.body,
                                color = LoomaTokens.colors.title,
                            )
                            Text(
                                text = stringResource(target.descriptionRes()),
                                style = LoomaTokens.type.supporting,
                                color = LoomaTokens.colors.muted,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun ImageBedSheet(
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

    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }) {
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
private fun imageTargetName(target: ImageUploadTarget): String = stringResource(
    when (target) {
        ImageUploadTarget.LocalAttachment -> R.string.settings_image_destination_local
        ImageUploadTarget.ImageBed -> R.string.settings_image_destination_bed
    },
)

private fun ImageUploadTarget.descriptionRes(): Int = when (this) {
    ImageUploadTarget.LocalAttachment -> R.string.settings_image_destination_local_desc
    ImageUploadTarget.ImageBed -> R.string.settings_image_destination_bed_desc
}

@Composable
private fun ThemePresetDialog(
    current: ThemePreset,
    onSelect: (ThemePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme_dialog_title)) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ThemePreset.entries.forEach { preset ->
                    val selected = preset == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(
                                selected = selected,
                                onClick = { onSelect(preset) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            text = themeName(preset),
                            style = LoomaTokens.type.body,
                            color = LoomaTokens.colors.title,
                            modifier = Modifier.weight(1f),
                        )
                        ThemeSwatch(preset = preset, useWallpaper = false)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun LanguageDialog(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_dialog_title)) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppLanguage.entries.forEach { language ->
                    val selected = language == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(
                                selected = selected,
                                onClick = { onSelect(language) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            text = languageName(language),
                            style = LoomaTokens.type.body,
                            color = LoomaTokens.colors.title,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun ThemeSwatch(preset: ThemePreset, useWallpaper: Boolean) {
    val scheme = if (useWallpaper) {
        MaterialTheme.colorScheme
    } else {
        presetScheme(preset, dark = false)
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(scheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (useWallpaper) {
            Box(Modifier.size(12.dp).background(scheme.tertiary, CircleShape))
        }
    }
}

@Composable
private fun themeName(preset: ThemePreset): String = stringResource(
    when (preset) {
        ThemePreset.Default -> R.string.settings_theme_default
        ThemePreset.Indigo -> R.string.settings_theme_indigo
        ThemePreset.Teal -> R.string.settings_theme_teal
        ThemePreset.Rose -> R.string.settings_theme_rose
        ThemePreset.Amber -> R.string.settings_theme_amber
    },
)

@Composable
private fun languageName(language: AppLanguage): String = when (language) {
    AppLanguage.System -> stringResource(R.string.settings_language_system)
    AppLanguage.English -> "English"
    AppLanguage.Chinese -> "简体中文"
}

@Composable
private fun settingsListColors(): ListItemColors =
    ListItemDefaults.segmentedColors(containerColor = LoomaTokens.colors.card)

@Composable
private fun SettingsSwitchRow(
    index: Int,
    count: Int,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = Modifier.fillMaxWidth().semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        enabled = enabled,
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
        colors = settingsListColors(),
    ) {
        Text(title)
    }
}

@Composable
private fun SettingsRow(
    index: Int,
    count: Int,
    title: String,
    description: String? = null,
    hint: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val supporting: (@Composable () -> Unit)? = description?.let { text ->
        {
            Column {
                Text(text)
                hint?.let { Text(it) }
            }
        }
    }
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    if (onClick != null) {
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            modifier = Modifier.fillMaxWidth(),
            supportingContent = supporting,
            trailingContent = trailing,
            colors = settingsListColors(),
        ) {
            Text(title)
        }
    } else {
        SegmentedListItem(
            shapes = shapes,
            modifier = Modifier.fillMaxWidth(),
            supportingContent = supporting,
            trailingContent = trailing,
            colors = settingsListColors(),
        ) {
            Text(title)
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = LoomaTokens.type.sectionTitle,
            color = LoomaTokens.colors.accent,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            content()
        }
    }
}

@Composable
private fun ToolbarOrderSheet(
    current: List<ToolbarItem>,
    onSave: (List<ToolbarItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var order by remember { mutableStateOf(current.filter { it.isAvailableInToolbar() }) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
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
