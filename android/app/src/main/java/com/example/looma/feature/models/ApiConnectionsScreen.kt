package com.example.looma.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.assistant.ApiConnection
import com.example.looma.ui.components.FeatureAction
import com.example.looma.ui.components.FeaturePage
import com.example.looma.ui.components.SectionHeader
import com.example.looma.ui.theme.LoomaTheme

@Composable
internal fun ApiConnectionsRoute(
    viewModel: ModelSettingsViewModel,
    onEdit: (String?) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ApiConnectionsScreen(connections = state.connections, onEdit = onEdit, onNavigateUp = onNavigateUp)
}

@Composable
internal fun ApiConnectionsScreen(
    connections: List<ApiConnection>,
    onEdit: (String?) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FeaturePage(
        iconRes = R.drawable.ic_key,
        title = stringResource(R.string.models_api_hero),
        subtitle = stringResource(R.string.models_api_subtitle),
        modifier = modifier,
        topBar = { scrollBehavior ->
            ModelTopBar(stringResource(R.string.models_api_title), onNavigateUp, scrollBehavior)
        },
        actions = {
            FeatureAction(
                text = stringResource(R.string.models_api_add),
                onClick = { onEdit(null) },
                iconRes = R.drawable.ic_add,
            )
        },
    ) {
        SectionHeader(title = stringResource(R.string.models_api_connections_title))
        if (connections.isEmpty()) {
            Text(
                text = stringResource(R.string.models_api_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                connections.forEachIndexed { index, connection ->
                    ConnectionItem(
                        connection = connection,
                        index = index,
                        count = connections.size,
                        onClick = { onEdit(connection.id) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.models_api_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ConnectionItem(connection: ApiConnection, index: Int, count: Int, onClick: () -> Unit) {
    val models = connection.models.count { it.enabled }
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
        leadingContent = { ProviderAvatar(connection.provider) },
        supportingContent = {
            Text(
                stringResource(
                    R.string.models_pair,
                    stringResource(connection.provider.labelRes()),
                    pluralStringResource(R.plurals.models_api_models, models, models),
                ),
            )
        },
        trailingContent = { ConnectionStatusBadge(connection.status) },
    ) {
        Text(connection.name)
    }
}

@Preview(name = "API 连接", showBackground = true)
@Composable
private fun ApiConnectionsPreview() {
    LoomaTheme(dynamicColor = false) {
        ApiConnectionsScreen(connections = ModelPreviewData.connections, onEdit = {}, onNavigateUp = {})
    }
}

@Preview(name = "API 连接 空", showBackground = true)
@Composable
private fun ApiConnectionsEmptyPreview() {
    LoomaTheme(dynamicColor = false) {
        ApiConnectionsScreen(connections = emptyList(), onEdit = {}, onNavigateUp = {})
    }
}
