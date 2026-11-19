@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.home.HomeSemanticSearch
import com.example.looma.ui.components.DelayedContent
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun HomeSemanticSearchControls(
    state: HomeSemanticSearch,
    onToggle: (Boolean) -> Unit,
    onManage: () -> Unit,
    onRetry: () -> Unit,
) {
    val enabled = state != HomeSemanticSearch.Off
    ListItem(
        modifier = Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle),
        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(
            text = stringResource(R.string.home_semantic_search),
            style = LoomaTokens.type.body,
            color = LoomaTokens.colors.title,
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
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(message, style = LoomaTokens.type.supporting, color = LoomaTokens.colors.muted)
            when (state) {
                HomeSemanticSearch.NeedsModel, HomeSemanticSearch.NeedsIndex -> TextButton(onClick = onManage) {
                    Text(stringResource(R.string.home_semantic_manage))
                }

                HomeSemanticSearch.SearchFailed -> TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.home_semantic_retry))
                }

                else -> Unit
            }
        }
    }
}
