package com.example.looma.feature.home.components

import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.home.HomeNoteItem
import com.example.looma.feature.home.HomeRecentItem

@Composable
internal fun HomeRecentRow(
    items: List<HomeRecentItem>,
    selection: Set<String>,
    listState: LazyListState,
    onNoteClick: (HomeNoteItem) -> Unit,
    onNoteLongClick: (HomeNoteItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = items, key = { it.note.listKey }) { item ->
            HomeRecentCard(
                item = item,
                isSelected = item.note.listKey in selection,
                onClick = { onNoteClick(item.note) },
                onLongClick = { onNoteLongClick(item.note) },
            )
        }
    }
}

@Composable
private fun HomeRecentCard(
    item: HomeRecentItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectLabel = stringResource(R.string.home_select_note_label)
    val colors = homeCardColors(isSelected)
    Card(
        modifier = modifier
            .width(RecentCardWidth)
            .semantics { selected = isSelected },
        shape = MaterialTheme.shapes.largeIncreased,
        colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.title),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = RecentCardMinHeight)
                .combinedClickable(
                    onLongClickLabel = selectLabel,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.note.summary.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HomeFadedMarkdownPreview(
                markdown = item.markdown,
                bodyColor = colors.body,
                containerColor = colors.container,
                emptyTextStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().height(RecentPreviewHeight),
            )
        }
    }
}

private val RecentCardWidth = 168.dp
private val RecentCardMinHeight = 152.dp
private val RecentPreviewHeight = 96.dp
