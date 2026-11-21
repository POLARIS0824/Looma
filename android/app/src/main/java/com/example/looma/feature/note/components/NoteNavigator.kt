package com.example.looma.feature.note.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import com.example.looma.R
import com.example.looma.domain.markdown.MarkdownNavigation
import com.example.looma.domain.markdown.PreparedMarkdown
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.components.LoomaIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun NoteNavigator(
    snapshot: () -> String,
    prepared: PreparedMarkdown.Ready?,
    jump: (String, TextRange) -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val field = rememberTextFieldState()
    var lastOffset by rememberSaveable { mutableIntStateOf(0) }
    IconButton(onClick = { visible = true }, shapes = IconButtonDefaults.shapes()) {
        LoomaIcon(R.drawable.ic_search, stringResource(R.string.note_navigate))
    }
    if (!visible) return
    val source = snapshot()
    val query = field.text.toString()
    var start by remember(query, source) { mutableIntStateOf(if (query.isEmpty()) 0 else lastOffset) }
    var backwards by remember(query) { mutableStateOf(false) }
    var match by remember(query, source, start, backwards) { mutableStateOf<Int?>(null) }
    var outline by remember(source) { mutableStateOf<MarkdownNavigation?>(null) }
    LaunchedEffect(source, query, start, backwards) {
        if (query.isEmpty()) {
            if (outline == null) outline = withContext(Dispatchers.Default) { MarkdownNavigation.scan(source, prepared) }
        } else {
            match = withContext(Dispatchers.Default) {
                if (backwards) source.lastIndexOf(query, start, true).takeIf { it >= 0 } ?: source.lastIndexOf(query, ignoreCase = true)
                else source.indexOf(query, start, true).takeIf { it >= 0 } ?: source.indexOf(query, ignoreCase = true)
            }
        }
    }
    fun go(range: TextRange) {
        lastOffset = range.min
        jump(source, range)
        visible = false
    }
    val at = match
    AlertDialog(onDismissRequest = { visible = false }, title = { Text(stringResource(R.string.note_navigate)) },
        text = {
            Column {
                OutlinedTextField(state = field, label = { Text(stringResource(R.string.note_find)) },
                    lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine)
                if (query.isEmpty()) {
                    val headings = outline?.headings
                    when {
                        headings == null -> DelayedLoadingIndicator(true)
                        headings.isEmpty() -> Text(stringResource(R.string.note_no_headings))
                        else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(headings, key = { it.start }) { heading ->
                                TextButton(onClick = { go(TextRange(heading.start)) }) {
                                    Text("H" + heading.level + "  " + heading.title)
                                }
                            }
                        }
                    }
                } else if (at == null) {
                    DelayedLoadingIndicator(true)
                } else if (at < 0) {
                    Text(stringResource(R.string.note_no_matches))
                } else {
                    Text(source.substring((at - 45).coerceAtLeast(0),
                        (at + query.length + 75).coerceAtMost(source.length)))
                    Row {
                        TextButton(onClick = { backwards = true; start = at - 1 }) { Text(stringResource(R.string.note_previous)) }
                        TextButton(onClick = { backwards = false; start = at + query.length }) { Text(stringResource(R.string.note_next)) }
                    }
                }
            }
        },
        confirmButton = {
            if (query.isNotEmpty() && at != null && at >= 0) {
                TextButton(onClick = { go(TextRange(at, at + query.length)) }) { Text(stringResource(R.string.note_locate)) }
            }
        },
        dismissButton = { TextButton(onClick = { visible = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}
