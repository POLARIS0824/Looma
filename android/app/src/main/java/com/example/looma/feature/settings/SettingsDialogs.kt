package com.example.looma.feature.settings

import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.data.settings.AppLanguage
import com.example.looma.data.settings.ThemePreset
import com.example.looma.ui.theme.presetScheme

@Composable
private fun <T> SettingsRadioDialog(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    isEnabled: (T) -> Boolean = { true },
    rowContent: @Composable RowScope.(item: T, enabled: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { option ->
                    val optionSelected = option == selected
                    val enabled = isEnabled(option)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(
                                selected = optionSelected,
                                enabled = enabled,
                                onClick = { onSelect(option) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = optionSelected, onClick = null, enabled = enabled)
                        rowContent(option, enabled)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.dialog_confirm)) }
        },
    )
}

@Composable
internal fun ThemePresetDialog(
    current: ThemePreset,
    onSelect: (ThemePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsRadioDialog(
        title = stringResource(R.string.settings_theme_dialog_title),
        options = ThemePreset.entries,
        selected = current,
        onSelect = onSelect,
        onDismiss = onDismiss,
    ) { preset, _ ->
        Text(
            text = themeName(preset),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        ThemeSwatch(preset = preset, useWallpaper = false)
    }
}

@Composable
internal fun LanguageDialog(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsRadioDialog(
        title = stringResource(R.string.settings_language_dialog_title),
        options = AppLanguage.entries,
        selected = current,
        onSelect = onSelect,
        onDismiss = onDismiss,
    ) { language, _ ->
        Text(
            text = languageName(language),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun ThemeSwatch(preset: ThemePreset, useWallpaper: Boolean) {
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
internal fun themeName(preset: ThemePreset): String = stringResource(
    when (preset) {
        ThemePreset.Default -> R.string.settings_theme_default
        ThemePreset.Indigo -> R.string.settings_theme_indigo
        ThemePreset.Teal -> R.string.settings_theme_teal
        ThemePreset.Rose -> R.string.settings_theme_rose
        ThemePreset.Amber -> R.string.settings_theme_amber
    },
)

@Composable
internal fun languageName(language: AppLanguage): String = when (language) {
    AppLanguage.System -> stringResource(R.string.settings_language_system)
    AppLanguage.English -> "English"
    AppLanguage.Chinese -> "简体中文"
}
