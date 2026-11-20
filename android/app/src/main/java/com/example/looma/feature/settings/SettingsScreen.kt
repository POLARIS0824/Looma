package com.example.looma.feature.settings

import androidx.compose.material3.MaterialTheme
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.example.looma.ui.components.ErrorBanner
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.PageMaxWidth
import com.example.looma.ui.components.pageMargin
import com.example.looma.ui.theme.LoomaTokens

@Composable
fun SettingsRoute(
    vaultViewModel: VaultSetupViewModel,
    settingsViewModel: SettingsViewModel,
    onChangeVaultClick: () -> Unit,
    onSemanticSearchClick: () -> Unit,
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
        onSemanticSearchClick = onSemanticSearchClick,
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
    onSemanticSearchClick: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overlay by remember { mutableStateOf<SettingsOverlay?>(null) }
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
                    IconButton(onClick = onNavigateUp, shapes = IconButtonDefaults.shapes()) {
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
                .padding(horizontal = pageMargin(), vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = PageMaxWidth).fillMaxWidth().padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(SettingsGroupGap),
            ) {
                SettingsGroup(stringResource(R.string.settings_section_vault)) {
                    SettingsRow(
                        index = 0,
                        count = 1,
                        title = vaultName ?: stringResource(R.string.settings_vault_none),
                        description = vaultPath,
                        trailing = {
                            TextButton(
                                onClick = onChangeVaultClick,
                                enabled = !isVaultBusy,
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(R.string.settings_vault_change))
                            }
                        },
                    )
                    vaultError?.let { failure ->
                        ErrorBanner(message = vaultErrorText(failure))
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
                        onClick = { overlay = SettingsOverlay.Theme },
                    )
                }

                SettingsGroup(stringResource(R.string.settings_section_language)) {
                    SettingsRow(
                        index = 0,
                        count = 1,
                        title = stringResource(R.string.settings_language),
                        description = languageName(language),
                        onClick = { overlay = SettingsOverlay.Language },
                    )
                }

                SettingsGroup(stringResource(R.string.settings_section_notes)) {
                    SettingsRow(
                        index = 0,
                        count = 3,
                        title = stringResource(R.string.settings_toolbar_order),
                        description = stringResource(R.string.settings_toolbar_order_desc),
                        onClick = { overlay = SettingsOverlay.ToolbarOrder },
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

                SettingsGroup(stringResource(R.string.settings_section_search)) {
                    SettingsRow(
                        index = 0,
                        count = 1,
                        title = stringResource(R.string.settings_semantic_title),
                        description = stringResource(R.string.settings_semantic_desc),
                        onClick = onSemanticSearchClick,
                    )
                }

                SettingsGroup(stringResource(R.string.settings_section_images)) {
                    SettingsRow(
                        index = 0,
                        count = 2,
                        title = stringResource(R.string.settings_image_destination),
                        description = imageTargetName(settings.imageUploadTarget),
                        onClick = { overlay = SettingsOverlay.ImageTarget },
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
                        onClick = { overlay = SettingsOverlay.ImageBed },
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
                                TextButton(onClick = onStorageAccessClick, shapes = ButtonDefaults.shapes()) {
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

    when (overlay) {
        SettingsOverlay.Theme -> ThemePresetDialog(
            current = settings.themePreset,
            onSelect = {
                onThemePresetSelect(it)
                overlay = null
            },
            onDismiss = { overlay = null },
        )
        SettingsOverlay.Language -> LanguageDialog(
            current = language,
            onSelect = {
                onLanguageSelect(it)
                overlay = null
            },
            onDismiss = { overlay = null },
        )
        SettingsOverlay.ToolbarOrder -> ToolbarOrderSheet(
            current = settings.toolbarOrder,
            onSave = { onToolbarOrderChange(it); overlay = null },
            onDismiss = { overlay = null },
        )
        SettingsOverlay.ImageTarget -> ImageTargetDialog(
            current = settings.imageUploadTarget,
            imageBedConfigured = settings.imageBed.isConfigured,
            onSelect = {
                onImageUploadTargetChange(it)
                overlay = null
            },
            onDismiss = { overlay = null },
        )
        SettingsOverlay.ImageBed -> ImageBedSheet(
            config = settings.imageBed,
            onSave = {
                onImageBedChange(it)
                overlay = null
            },
            credentialsUnavailable = settings.s3CredentialsUnavailable,
            onDismiss = { overlay = null },
        )
        null -> Unit
    }
}

private sealed interface SettingsOverlay {
    data object Theme : SettingsOverlay
    data object Language : SettingsOverlay
    data object ToolbarOrder : SettingsOverlay
    data object ImageTarget : SettingsOverlay
    data object ImageBed : SettingsOverlay
}
