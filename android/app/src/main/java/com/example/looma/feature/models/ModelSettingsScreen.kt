package com.example.looma.feature.models

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.assistant.ConnectionStatus
import com.example.looma.data.assistant.LocalModelState
import com.example.looma.data.assistant.apiModelId
import com.example.looma.ui.components.FeaturePage
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.SectionHeader
import com.example.looma.ui.format.formatFileSize
import com.example.looma.ui.theme.LoomaTheme
import com.example.looma.ui.theme.TABULAR_FIGURES

@Immutable
private data class DefaultModelOption(
    val id: String,
    val name: String,
    @DrawableRes val iconRes: Int,
    val supporting: String,
    val enabled: Boolean,
)

@Composable
internal fun ModelSettingsRoute(
    viewModel: ModelSettingsViewModel,
    onOpenLocal: () -> Unit,
    onOpenApi: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ModelSettingsScreen(
        state = state,
        onOpenLocal = onOpenLocal,
        onOpenApi = onOpenApi,
        onSelectDefault = viewModel::setDefault,
        onNavigateUp = onNavigateUp,
    )
}

@Composable
internal fun ModelSettingsScreen(
    state: ModelSettingsUiState,
    onOpenLocal: () -> Unit,
    onOpenApi: () -> Unit,
    onSelectDefault: (String) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloaded = state.localModels.count { it.state == LocalModelState.Ready }
    val modelCount = state.connections.sumOf { connection -> connection.models.count { it.enabled } }
    val colors = MaterialTheme.colorScheme
    FeaturePage(
        iconRes = R.drawable.ic_neurology,
        title = stringResource(R.string.models_hero_title),
        subtitle = stringResource(R.string.models_hero_subtitle),
        modifier = modifier,
        topBar = { scrollBehavior ->
            ModelTopBar(stringResource(R.string.models_title), onNavigateUp, scrollBehavior)
        },
        actions = null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelSourceCard(
                iconRes = R.drawable.ic_memory,
                title = stringResource(R.string.models_local_title),
                description = stringResource(R.string.models_local_desc),
                summary = if (downloaded == 0) {
                    stringResource(R.string.models_local_none)
                } else {
                    pluralStringResource(R.plurals.models_local_downloaded, downloaded, downloaded)
                },
                container = colors.primaryContainer,
                content = colors.onPrimaryContainer,
                pill = colors.primary,
                onPill = colors.onPrimary,
                onClick = onOpenLocal,
            )
            ModelSourceCard(
                iconRes = R.drawable.ic_key,
                title = stringResource(R.string.models_api_title),
                description = stringResource(R.string.models_api_desc),
                summary = if (state.connections.isEmpty()) {
                    stringResource(R.string.models_api_none)
                } else {
                    stringResource(
                        R.string.models_pair,
                        pluralStringResource(R.plurals.models_api_connections, state.connections.size, state.connections.size),
                        pluralStringResource(R.plurals.models_api_models, modelCount, modelCount),
                    )
                },
                container = colors.tertiaryContainer,
                content = colors.onTertiaryContainer,
                pill = colors.tertiary,
                onPill = colors.onTertiary,
                onClick = onOpenApi,
            )
        }
        Spacer(Modifier.height(32.dp))
        DefaultModelGroup(options = defaultModelOptions(state), selected = state.defaultModelId, onSelect = onSelectDefault)
    }
}

@Composable
private fun defaultModelOptions(state: ModelSettingsUiState): List<DefaultModelOption> {
    val context = LocalContext.current
    val local = state.localModels.filter { it.state == LocalModelState.Ready }.map { entry ->
        DefaultModelOption(
            id = entry.id,
            name = entry.displayName(),
            iconRes = R.drawable.ic_memory,
            supporting = stringResource(R.string.models_on_device_size, formatFileSize(context, entry.sizeBytes)),
            enabled = true,
        )
    }
    val api = state.connections.flatMap { connection ->
        val ok = connection.status == ConnectionStatus.Ok
        val supporting = if (ok) {
            connection.name
        } else {
            stringResource(R.string.models_pair, connection.name, stringResource(connection.status.labelRes()))
        }
        connection.models.filter { it.enabled }.map { model ->
            DefaultModelOption(
                id = apiModelId(connection.id, model.id),
                name = model.id,
                iconRes = R.drawable.ic_cloud,
                supporting = supporting,
                enabled = ok,
            )
        }
    }
    return local + api
}

@Composable
private fun ModelSourceCard(
    @DrawableRes iconRes: Int,
    title: String,
    description: String,
    summary: String,
    container: Color,
    content: Color,
    pill: Color,
    onPill: Color,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoomaIcon(iconRes = iconRes, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Surface(color = pill, contentColor = onPill, shape = CircleShape) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR_FIGURES),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DefaultModelGroup(
    options: List<DefaultModelOption>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.models_default_title))
        if (options.isEmpty()) {
            Text(
                text = stringResource(R.string.models_default_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            return@Column
        }
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = option.id == selected
                SegmentedListItem(
                    selected = isSelected,
                    onClick = { onSelect(option.id) },
                    shapes = ListItemDefaults.segmentedShapes(index, options.size),
                    enabled = option.enabled,
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
                    leadingContent = { LoomaIcon(iconRes = option.iconRes, contentDescription = null) },
                    supportingContent = { Text(option.supporting) },
                    trailingContent = { RadioButton(selected = isSelected, onClick = null, enabled = option.enabled) },
                ) {
                    Text(option.name)
                }
            }
        }
    }
}

@Preview(name = "模型总览", showBackground = true)
@Composable
private fun ModelSettingsPreview() {
    LoomaTheme(dynamicColor = false) {
        ModelSettingsScreen(
            state = ModelSettingsUiState(
                localModels = ModelPreviewData.local,
                connections = ModelPreviewData.connections,
                defaultModelId = "gemma-3n-e2b",
            ),
            onOpenLocal = {},
            onOpenApi = {},
            onSelectDefault = {},
            onNavigateUp = {},
        )
    }
}

@Preview(name = "模型总览 空", showBackground = true)
@Composable
private fun ModelSettingsEmptyPreview() {
    LoomaTheme(dynamicColor = false) {
        ModelSettingsScreen(
            state = ModelSettingsUiState(),
            onOpenLocal = {},
            onOpenApi = {},
            onSelectDefault = {},
            onNavigateUp = {},
        )
    }
}
