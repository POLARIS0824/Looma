package com.example.looma.feature.note.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.wiki.Backlink
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.SectionHeader

@Composable
fun BacklinksSection(
    backlinks: List<Backlink>,
    onOpen: (NoteId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (backlinks.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.note_backlinks_title, backlinks.size),
            first = false,
        )
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            backlinks.forEachIndexed { index, backlink ->
                SegmentedListItem(
                    onClick = { onOpen(backlink.id) },
                    shapes = ListItemDefaults.segmentedShapes(index, backlinks.size),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    leadingContent = {
                        LoomaIcon(
                            iconRes = R.drawable.ic_note,
                            contentDescription = null,
                        )
                    },
                ) {
                    Text(
                        text = backlink.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
