package com.example.looma.feature.home.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.items as gridItems
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.home.HomeSearchResult
import com.example.looma.ui.components.DelayedContent
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.noteGridMargin
import com.example.looma.ui.components.SectionHeader

@Composable
internal fun HomeSearchResults(
    query: String,
    results: List<HomeSearchResult>,
    isGridMode: Boolean,
    onNoteClick: (HomeSearchResult) -> Unit,
    recentSearches: List<String> = emptyList(),
    onRecentSearchClick: (String) -> Unit = {},
    isSearching: Boolean = false,
    semanticSearch: Boolean = false,
) {
    val margin = noteGridMargin()
    when {
        query.isBlank() && recentSearches.isNotEmpty() -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "recent-searches-header") {
                SectionHeader(
                    title = stringResource(R.string.home_recent_searches),
                    parentSpacing = RecentSearchItemInset,
                    modifier = Modifier.padding(horizontal = margin).padding(top = 8.dp),
                )
            }
            items(recentSearches) { search ->
                ListItem(
                    leadingContent = { LoomaIcon(R.drawable.ic_search, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRecentSearchClick(search) },
                ) {
                    Text(
                        text = search,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        query.isBlank() -> HomeSearchHint(
            stringResource(if (semanticSearch) R.string.home_semantic_hint else R.string.home_search_hint),
        )
        isSearching -> Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
            DelayedLoadingIndicator(loading = true)
        }
        results.isEmpty() -> DelayedContent {
            HomeSearchHint(stringResource(R.string.home_search_no_match))
        }
        else -> HomeNoteGrid(
            isGridMode = isGridMode,
            contentPadding = homeGridPadding(bottom = 24.dp),

        ) {
            gridItems(results, key = { "search:${it.listKey}" }) { result ->
                HomeNoteCard(
                    note = result.note,
                    isSelected = false,
                    onClick = { onNoteClick(result) },
                    search = result,
                )
            }
        }
    }
}

private val RecentSearchItemInset = 8.dp

@Composable
private fun HomeSearchHint(text: String) {
    Box(Modifier.fillMaxSize().padding(start = noteGridMargin(), end = noteGridMargin(), top = 24.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
