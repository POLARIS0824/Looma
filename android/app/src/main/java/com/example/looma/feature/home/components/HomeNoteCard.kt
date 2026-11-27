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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.markdown.ImageSource
import com.example.looma.domain.search.SearchMatch
import com.example.looma.feature.home.HomeNoteItem
import com.example.looma.feature.home.HomeSearchResult
import com.example.looma.ui.image.LoomaAsyncImage
import com.example.looma.ui.markdown.LoomaMarkdown
import com.example.looma.ui.markdown.MarkdownDensity
import com.example.looma.ui.theme.LoomaPreviewTypography
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun HomeNoteCard(
    note: HomeNoteItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    search: HomeSearchResult? = null,
) {
    val selectLabel = stringResource(R.string.home_select_note_label)
    val colors = homeCardColors(isSelected)
    val highlight = SpanStyle(
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        background = MaterialTheme.colorScheme.primaryContainer,
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { selected = isSelected },
        shape = MaterialTheme.shapes.largeIncreased,
        colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.title),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = if (search != null) stringResource(R.string.home_open_note_label) else null,
                    onLongClickLabel = if (onLongClick != null) selectLabel else null,
                    onLongClick = onLongClick,
                    onClick = onClick,
                ),
        ) {
            note.coverPath?.let { path ->
                LoomaAsyncImage(
                    source = ImageSource.Local(path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val title = search?.titleMatch
                Text(
                    text = title?.highlighted(highlight) ?: AnnotatedString(note.summary.title),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = colors.title,
                    maxLines = if (title == null) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (search != null) {
                    Text(
                        text = search.id.path.relativePath,
                        style = LoomaTokens.type.cardMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    if (search.section.isNotBlank()) {
                        Text(
                            text = search.section,
                            style = LoomaTokens.type.cardMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (search.matches.isNotEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .clipToBounds(),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                search.matches.take(3).forEach { match ->
                                    Text(
                                        text = match.highlighted(highlight),
                                        style = LoomaTokens.type.homeNotePreview.body,
                                        color = colors.body,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Box(
                                Modifier.matchParentSize().background(
                                    Brush.verticalGradient(
                                        0.78f to Color.Transparent,
                                        1f to colors.container,
                                    ),
                                ),
                            )
                        }
                    }
                } else {
                    HomeFadedMarkdownPreview(
                        markdown = note.markdownSnippet,
                        bodyColor = colors.body,
                        containerColor = colors.container,
                        emptyTextStyle = LoomaTokens.type.homeNotePreview.body,
                        previewTypography = LoomaTokens.type.homeNotePreview,
                        onSelectedContainer = isSelected,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                    )
                }
                val tags = if (search == null) {
                    note.summary.tags.take(2).map { AnnotatedString("#$it") }
                } else {
                    search.tagMatches.take(2).map { it.highlighted(highlight) }
                }
                if (tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tags.forEach { tag ->
                            HomeChip(tag, isSelected, maxLines = if (search == null) 1 else 2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeFadedMarkdownPreview(
    markdown: String,
    bodyColor: Color,
    containerColor: Color,
    emptyTextStyle: TextStyle,
    modifier: Modifier = Modifier,
    previewTypography: LoomaPreviewTypography? = null,
    onSelectedContainer: Boolean = false,
) {
    if (markdown.isBlank()) {
        Box(modifier) {
            Text(
                text = stringResource(R.string.home_empty_note),
                style = emptyTextStyle,
                color = bodyColor,
            )
        }
    } else {
        Box(modifier.clipToBounds()) {
            LoomaMarkdown(
                markdown = markdown,
                density = MarkdownDensity.Compact,
                typography = previewTypography,
                textColor = bodyColor,
                onSelectedContainer = onSelectedContainer,
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0.72f to Color.Transparent,
                        1f to containerColor,
                    ),
                ),
            )
        }
    }
}

private fun SearchMatch.highlighted(style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { range ->
        addStyle(style, range.first, range.last + 1)
    }
}

@Composable
private fun HomeChip(label: AnnotatedString, onSelectedCard: Boolean, maxLines: Int) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (onSelectedCard) LoomaTokens.colors.card else LoomaTokens.colors.cardInset,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = LoomaTokens.type.cardMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Immutable
internal data class HomeCardColors(
    val container: Color,
    val title: Color,
    val body: Color,
    val icon: Color,
    val selectedCheck: Color,
)

@Composable
internal fun homeCardColors(isSelected: Boolean): HomeCardColors {
    val scheme = MaterialTheme.colorScheme
    val colors = LoomaTokens.colors
    return if (isSelected) {
        HomeCardColors(
            container = scheme.secondaryContainer,
            title = scheme.onSecondaryContainer,
            body = scheme.onSecondaryContainer,
            icon = scheme.onSecondaryContainer,
            selectedCheck = scheme.onSecondaryContainer,
        )
    } else {
        HomeCardColors(
            container = colors.card,
            title = scheme.onSurface,
            body = scheme.onSurfaceVariant,
            icon = scheme.onSurfaceVariant,
            selectedCheck = scheme.onSurfaceVariant,
        )
    }
}
