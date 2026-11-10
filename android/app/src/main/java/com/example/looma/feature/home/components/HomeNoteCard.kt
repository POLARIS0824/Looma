package com.example.looma.feature.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.home.HomeNoteItem
import com.example.looma.ui.markdown.MarkdownPreview
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun HomeNoteCard(
    note: HomeNoteItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val selectLabel = stringResource(R.string.home_select_note_label)
    val colors = homeCardColors(isSelected)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onLongClickLabel = selectLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { selected = isSelected },
        shape = shape,
        color = colors.container,
        border = if (isSelected) BorderStroke(2.dp, LoomaTokens.colors.accent) else null,
    ) {
        Column {
            note.coverPath?.let { path ->
                NoteCoverImage(
                    path = path,
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = note.summary.title,
                    style = LoomaTokens.type.homeNoteTitle,
                    color = colors.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.markdownSnippet.isBlank()) {
                    Text(
                        text = stringResource(R.string.home_empty_note),
                        style = LoomaTokens.type.homeNotePreview.body,
                        color = colors.body,
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clipToBounds(),
                    ) {
                        MarkdownPreview(
                            markdown = note.markdownSnippet,
                            typography = LoomaTokens.type.homeNotePreview,
                            textColor = colors.body,
                        )
                        Box(
                            Modifier.matchParentSize().background(
                                Brush.verticalGradient(
                                    0.72f to Color.Transparent,
                                    1f to colors.container,
                                ),
                            ),
                        )
                    }
                }
                if (note.summary.tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        note.summary.tags.take(2).forEach { tag -> HomeChip("#$tag", isSelected) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeChip(label: String, onSelectedCard: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (onSelectedCard) LoomaTokens.colors.card else LoomaTokens.colors.chip,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = LoomaTokens.type.cardMeta,
            color = if (onSelectedCard) LoomaTokens.colors.body else LoomaTokens.colors.onChip,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Immutable
internal data class HomeCardColors(
    val container: Color,
    val title: Color,
    val body: Color,
)

@Composable
internal fun homeCardColors(isSelected: Boolean): HomeCardColors {
    val scheme = MaterialTheme.colorScheme
    val colors = LoomaTokens.colors
    return if (isSelected) {
        HomeCardColors(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.onSecondaryContainer)
    } else {
        HomeCardColors(colors.card, colors.title, colors.body)
    }
}