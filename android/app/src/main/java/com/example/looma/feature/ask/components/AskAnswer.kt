package com.example.looma.feature.ask.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.data.assistant.GenerationError
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.ask.AskIssue
import com.example.looma.feature.ask.AskStatus
import com.example.looma.feature.ask.ChatMessage
import com.example.looma.feature.ask.ChatRole
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.components.ErrorBanner
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.PageMaxWidth
import com.example.looma.ui.components.pageMargin
import com.example.looma.ui.markdown.LoomaMarkdown
import com.example.looma.ui.markdown.MarkdownDensity

private fun Modifier.pageColumn(margin: Dp): Modifier =
    widthIn(max = PageMaxWidth).fillMaxWidth().padding(horizontal = margin)

@Composable
internal fun AskEmpty(
    vaultName: String,
    hasModel: Boolean,
    issue: AskIssue?,
    onManageModels: () -> Unit,
    contentPadding: PaddingValues,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.pageColumn(pageMargin()).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.ask_empty_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall.copy(lineBreak = LineBreak.Heading),
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.ask_empty_scope, vaultName),
                    style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (!hasModel && !WindowInsets.isImeVisible) {
                    Text(
                        stringResource(R.string.ask_empty_no_model),
                        style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    FilledTonalButton(onClick = onManageModels, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.models_manage))
                    }
                }
                issue?.let { AskIssueBanner(it, onManageModels = onManageModels) }
            }
        }
    }
}

private fun LazyListState.isAtBottom(): Boolean {
    if (!canScrollForward) return true
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    if (lastVisible.index < layoutInfo.totalItemsCount - 1) return false
    val viewportBottom = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    return (lastVisible.offset + lastVisible.size) <= viewportBottom + 8
}

@Composable
internal fun AskConversation(
    messages: List<ChatMessage>,
    status: AskStatus?,
    saving: Boolean,
    busy: Boolean,
    compacted: Boolean,
    issue: AskIssue?,
    onSaveMessage: (ChatMessage) -> Unit,
    onOpenSaved: (NoteId) -> Unit,
    onRetry: () -> Unit,
    onManageModels: () -> Unit,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val bottomPaddingPx = with(density) { (contentPadding.calculateBottomPadding() + 16.dp).roundToPx() }
    val currentBottomPaddingPx by rememberUpdatedState(bottomPaddingPx)
    var stickToBottom by remember { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.collect {
            if (!programmaticScroll) {
                stickToBottom = listState.isAtBottom()
            }
        }
    }

    LaunchedEffect(listState) {
        var previousBottomPaddingPx = currentBottomPaddingPx
        snapshotFlow { currentBottomPaddingPx }.collect { newBottomPaddingPx ->
            val delta = newBottomPaddingPx - previousBottomPaddingPx
            previousBottomPaddingPx = newBottomPaddingPx
            if (delta > 0 && stickToBottom) {
                programmaticScroll = true
                try {
                    listState.scrollBy(delta.toFloat())
                } finally {
                    programmaticScroll = false
                }
            }
        }
    }

    val lastTextLength = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size) {
        stickToBottom = true
    }
    LaunchedEffect(messages.size, lastTextLength, status) {
        if (messages.isNotEmpty() && stickToBottom) {
            programmaticScroll = true
            try {
                listState.animateScrollToItem(messages.lastIndex)
            } finally {
                programmaticScroll = false
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (compacted) {
            item(key = "compaction_notice") {
                Text(
                    stringResource(R.string.ask_context_compacted),
                    modifier = Modifier.pageColumn(pageMargin()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        itemsIndexed(messages, key = { _, msg -> msg.id }) { index, message ->
            val isLast = index == messages.lastIndex
            when (message.role) {
                ChatRole.User -> UserBubble(message.text)
                ChatRole.Assistant -> AssistantMessage(
                    message = message,
                    isLast = isLast,
                    busy = busy,
                    onSave = { onSaveMessage(message) },
                    onOpenSaved = onOpenSaved,
                    onRetry = onRetry,
                )
            }
        }

        if (saving || status != null) {
            item(key = "progress") {
                Row(
                    Modifier.pageColumn(pageMargin()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DelayedLoadingIndicator(loading = true, size = 32.dp)
                    Text(
                        when {
                            saving -> stringResource(R.string.ask_saving)
                            status is AskStatus.ToolCalling -> {
                                if (status.toolName == "search_vault") {
                                    stringResource(R.string.ask_tool_searching_vault)
                                } else {
                                    stringResource(R.string.ask_tool, status.toolName)
                                }
                            }
                            status is AskStatus.Compacting -> stringResource(R.string.ask_compacting)
                            else -> stringResource(R.string.ask_thinking_chat)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (issue != null) {
            item(key = "issue") {
                Box(Modifier.pageColumn(pageMargin())) {
                    AskIssueBanner(
                        issue = issue,
                        onRetry = onRetry.takeIf { issue == AskIssue.Run || issue == AskIssue.Timeout },
                        onManageModels = onManageModels,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.pageColumn(pageMargin()), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.padding(start = 48.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    isLast: Boolean,
    busy: Boolean,
    onSave: () -> Unit,
    onOpenSaved: (NoteId) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.pageColumn(pageMargin()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (message.text.isNotBlank()) {
            LoomaMarkdown(
                markdown = message.text,
                density = MarkdownDensity.Comfortable,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!message.isStreaming) {
            val error = message.error
            if (error != null) {
                val errorText = if (error is GenerationError.Failed && !error.message.isNullOrBlank()) {
                    error.message
                } else {
                    stringResource(error.messageRes())
                }
                if (isLast) {
                    ErrorBanner(
                        message = errorText,
                        actionLabel = stringResource(R.string.ask_retry),
                        onAction = onRetry,
                        actionEnabled = !busy,
                    )
                } else {
                    ErrorBanner(message = errorText)
                }
            } else if (message.text.isNotBlank()) {
                val meta = message.meta
                if (meta != null) {
                    val origin = stringResource(meta.originLabelRes())
                    val label = if (meta.fallbackUsed) {
                        stringResource(R.string.ask_origin_with_fallback, origin, stringResource(R.string.ask_meta_fallback))
                    } else {
                        origin
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val saved = message.savedNoteId
                    IconButton(
                        onClick = { if (saved == null) onSave() else onOpenSaved(saved) },
                        enabled = !busy,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        LoomaIcon(
                            if (saved == null) R.drawable.ic_note_add else R.drawable.ic_note,
                            stringResource(if (saved == null) R.string.ask_save_note else R.string.ask_open_saved),
                        )
                    }
                    if (isLast) {
                        IconButton(onClick = onRetry, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                            LoomaIcon(R.drawable.ic_retry, stringResource(R.string.ask_retry))
                        }
                    }
                }
            }
        }
    }
}

private fun GenerationError.messageRes(): Int = when (this) {
    GenerationError.Offline -> R.string.ask_error_offline
    GenerationError.OnDeviceUnavailable -> R.string.ask_error_on_device_unavailable
    GenerationError.AppCheckFailed -> R.string.ask_error_app_check
    GenerationError.QuotaExceeded -> R.string.ask_error_quota
    is GenerationError.Failed -> R.string.ask_model_failed
}

@Composable
private fun AskIssueBanner(
    issue: AskIssue,
    onRetry: (() -> Unit)? = null,
    onManageModels: (() -> Unit)? = null,
) {
    val message = stringResource(
        when (issue) {
            AskIssue.Run -> R.string.ask_run_failed
            AskIssue.Timeout -> R.string.ask_timeout
            AskIssue.NoModel -> R.string.ask_no_model_selected
            AskIssue.Save -> R.string.ask_save_failed
        },
    )
    when {
        issue == AskIssue.NoModel && onManageModels != null -> {
            ErrorBanner(message = message, actionLabel = stringResource(R.string.models_manage), onAction = onManageModels)
        }
        onRetry != null -> {
            ErrorBanner(message = message, actionLabel = stringResource(R.string.ask_retry), onAction = onRetry)
        }
        else -> {
            ErrorBanner(message = message)
        }
    }
}
