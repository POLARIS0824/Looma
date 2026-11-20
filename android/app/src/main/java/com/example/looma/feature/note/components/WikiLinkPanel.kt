package com.example.looma.feature.note.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.wiki.WikiNote
import com.example.looma.domain.wiki.wikiNameOf
import com.example.looma.domain.wiki.wikiPathOf
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun WikiLinkPanel(
    candidates: List<WikiNote>,
    onPick: (WikiNote) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }
    val queryText = query.text.toString()
    val matches = remember(queryText, candidates) { filterCandidates(candidates, queryText) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoomaIcon(
                    iconRes = R.drawable.ic_search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                BasicTextField(
                    state = query,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            when (event.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                        matches.firstOrNull()?.let(onPick)
                                    }
                                    true
                                }
                                Key.Escape -> {
                                    if (event.type == KeyEventType.KeyDown) onDismiss()
                                    true
                                }
                                else -> false
                            }
                        },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { matches.firstOrNull()?.let(onPick) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorator = { inner ->
                        Box {
                            if (query.text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.editor_wikilink_search),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                    LoomaIcon(
                        iconRes = R.drawable.ic_close,
                        contentDescription = stringResource(R.string.editor_cancel),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (matches.isEmpty()) {
                Text(
                    text = stringResource(R.string.editor_wikilink_empty),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    style = LoomaTokens.type.cardMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 216.dp).padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    itemsIndexed(matches, key = { _, it -> it.id.path.relativePath }) { index, note ->
                        val folder = note.id.path.relativePath.substringBeforeLast('/', "")
                        SegmentedListItem(
                            onClick = { onPick(note) },
                            shapes = ListItemDefaults.segmentedShapes(index, matches.size),
                            colors = ListItemDefaults.segmentedColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            ),
                            supportingContent = if (folder.isNotEmpty()) {
                                {
                                    Text(
                                        text = folder,
                                        style = LoomaTokens.type.cardMeta,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else {
                                null
                            },
                        ) {
                            Text(
                                text = note.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
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

internal fun filterCandidates(candidates: List<WikiNote>, query: String): List<WikiNote> {
    val needle = query.trim()
    if (needle.isEmpty()) return candidates.take(MAX_MATCHES)
    return candidates
        .asSequence()
        .filter { it.title.contains(needle, ignoreCase = true) }
        .map { note ->
            val prefixRank = if (note.title.startsWith(needle, ignoreCase = true)) 0 else 1
            Triple(note, prefixRank, note.title.lowercase(java.util.Locale.ROOT))
        }
        .sortedWith(
            compareBy<Triple<WikiNote, Int, String>> { it.second }
                .thenBy { it.third },
        )
        .take(MAX_MATCHES)
        .map { it.first }
        .toList()
}

private const val MAX_MATCHES = 6

internal fun wikiReferenceFor(note: WikiNote, all: List<WikiNote>): String {
    val base = wikiNameOf(note.id.path)
    val duplicated = all.count { wikiNameOf(it.id.path).equals(base, ignoreCase = true) } > 1
    val target = if (duplicated) wikiPathOf(note.id.path) else base
    val alias = note.title.trim()
    return if (alias.isNotEmpty() && !alias.equals(target, ignoreCase = true)) {
        "$target|$alias"
    } else {
        target
    }
}
