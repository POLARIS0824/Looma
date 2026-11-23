package com.example.looma.feature.home.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.home.HomeSemanticSearch
import com.example.looma.ui.components.DelayedContent
import com.example.looma.ui.components.noteGridMargin

@Composable
internal fun HomeSemanticSearchControls(
    state: HomeSemanticSearch,
    onToggle: (Boolean) -> Unit,
    onManage: () -> Unit,
    onRetry: () -> Unit,
) {
    val enabled = state != HomeSemanticSearch.Off
    ListItem(
        checked = enabled,
        onCheckedChange = onToggle,
        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(
            text = stringResource(R.string.home_semantic_search),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    val message = when (state) {
        HomeSemanticSearch.Off, HomeSemanticSearch.Ready -> return
        HomeSemanticSearch.Unsupported -> stringResource(R.string.home_semantic_unsupported)
        HomeSemanticSearch.Checking -> stringResource(R.string.home_semantic_checking)
        HomeSemanticSearch.NeedsModel -> stringResource(R.string.home_semantic_model_required)
        HomeSemanticSearch.NeedsIndex -> stringResource(R.string.home_semantic_index_required)
        HomeSemanticSearch.SearchFailed -> stringResource(R.string.home_semantic_search_failed)
    }
    DelayedContent {
        Column(Modifier.padding(horizontal = noteGridMargin(), vertical = 8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state) {
                HomeSemanticSearch.NeedsModel, HomeSemanticSearch.NeedsIndex -> TextButton(
                    onClick = onManage,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.home_semantic_manage))
                }

                HomeSemanticSearch.SearchFailed -> TextButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.home_semantic_retry))
                }

                else -> Unit
            }
        }
    }
}
