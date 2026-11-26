package com.example.looma.feature.ask.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.data.assistant.history.AskConversationSummary
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.format.formatDateTime
import com.example.looma.ui.theme.LoomaTokens
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AskHistorySheet(
    conversations: List<AskConversationSummary>,
    currentConversationId: String?,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LoomaTokens.colors.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp),
            ) {
                FilledTonalIconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterStart),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    LoomaIcon(R.drawable.ic_close, stringResource(android.R.string.cancel))
                }
                Text(
                    text = stringResource(R.string.ask_history),
                    modifier = Modifier.align(Alignment.Center).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (conversations.isEmpty()) {
                Text(
                    text = stringResource(R.string.ask_history_empty),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    itemsIndexed(conversations, key = { _, item -> item.id }) { index, item ->
                        val selected = item.id == currentConversationId
                        SegmentedListItem(
                            selected = selected,
                            onClick = { onSelect(item.id) },
                            shapes = ListItemDefaults.segmentedShapes(index, conversations.size),
                            enabled = !busy,
                            colors = ListItemDefaults.segmentedColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            supportingContent = {
                                Text(
                                    text = formatDateTime(Instant.ofEpochMilli(item.updatedAt)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = { onDelete(item.id) },
                                    enabled = !busy,
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    LoomaIcon(
                                        iconRes = R.drawable.ic_delete,
                                        contentDescription = stringResource(R.string.ask_history_delete),
                                    )
                                }
                            },
                        ) {
                            Text(
                                text = item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
