package com.example.looma.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalWideNavigationRail
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailState
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens
import kotlinx.coroutines.launch

enum class LoomaDestination(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    Notes(R.string.drawer_notes, R.drawable.ic_note),
    Ask(R.string.ask_title, R.drawable.ic_sparkle),
    Trash(R.string.drawer_trash, R.drawable.ic_delete),
    Settings(R.string.drawer_settings, R.drawable.ic_settings),
}

@Composable
fun LoomaNavigationRail(
    state: WideNavigationRailState,
    compact: Boolean,
    current: LoomaDestination?,
    vaultName: String,
    onSelect: (LoomaDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val expanded = state.targetValue == WideNavigationRailValue.Expanded
    Row(modifier) {
        when {
            current == null -> Unit
            compact -> ModalWideNavigationRail(
                state = state,
                hideOnCollapse = true,
                header = { RailTitle(vaultName) },
            ) {
                RailItems(current = current, railExpanded = true, onSelect = onSelect)
            }

            else -> WideNavigationRail(
                state = state,
                header = { RailToggle(state = state, expanded = expanded, vaultName = vaultName) },
            ) {
                RailItems(current = current, railExpanded = expanded, onSelect = onSelect)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

@Composable
private fun RailItems(
    current: LoomaDestination,
    railExpanded: Boolean,
    onSelect: (LoomaDestination) -> Unit,
) {
    LoomaDestination.entries.forEach { destination ->
        WideNavigationRailItem(
            selected = destination == current,
            onClick = { onSelect(destination) },
            icon = { LoomaIcon(iconRes = destination.icon, contentDescription = null) },
            label = { Text(stringResource(destination.label)) },
            railExpanded = railExpanded,
        )
    }
}

@Composable
private fun RailTitle(vaultName: String) {
    val appName = stringResource(R.string.app_name)
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = appName,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (vaultName.isNotBlank() && vaultName != appName) {
            Text(
                text = vaultName,
                style = LoomaTokens.type.cardMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RailToggle(state: WideNavigationRailState, expanded: Boolean, vaultName: String) {
    val scope = rememberCoroutineScope()
    val description = stringResource(
        if (expanded) R.string.nav_collapse_rail else R.string.nav_expand_rail,
    )
    val stateText = stringResource(
        if (expanded) R.string.nav_rail_expanded else R.string.nav_rail_collapsed,
    )
    Column {
        IconButton(
            onClick = { scope.launch { state.toggle() } },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier
                .padding(start = 24.dp)
                .semantics { stateDescription = stateText },
        ) {
            LoomaIcon(iconRes = R.drawable.ic_menu, contentDescription = description)
        }
        if (expanded) RailTitle(vaultName)
    }
}
