@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.looma.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun rememberExpandedSheetState() = rememberBottomSheetState(
    initialValue = SheetValue.Hidden,
    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
)

@Composable
private fun settingsListColors(): ListItemColors =
    ListItemDefaults.segmentedColors(containerColor = LoomaTokens.colors.card)

@Composable
internal fun SettingsSwitchRow(
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
internal fun SettingsRow(
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
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
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
