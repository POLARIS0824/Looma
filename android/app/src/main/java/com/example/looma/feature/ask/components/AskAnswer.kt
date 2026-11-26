package com.example.looma.feature.ask.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.data.assistant.AnswerStatus
import com.example.looma.data.assistant.Evidence
import com.example.looma.data.assistant.GenerationError
import com.example.looma.data.assistant.VaultAnswer
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.ask.AskIssue
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.components.ErrorBanner
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.PageMaxWidth
import com.example.looma.ui.components.pageMargin
import com.example.looma.ui.markdown.MarkdownPreview
import com.example.looma.ui.theme.LoomaPreviewTypography
import com.example.looma.ui.theme.LoomaTokens

private fun Modifier.pageColumn(margin: Dp): Modifier = widthIn(max = PageMaxWidth).fillMaxWidth().padding(horizontal = margin)

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
                issue?.let { AskIssueBanner(it) }
            }
        }
    }
}

@Composable
internal fun AskConversation(
    question: String,
    answer: VaultAnswer?,
    step: String?,
    saving: Boolean,
    saved: NoteId?,
    busy: Boolean,
    issue: AskIssue?,
    onOpen: (Evidence) -> Unit,
    onSave: () -> Unit,
    onOpenSaved: (NoteId) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "question") {
            Row(Modifier.pageColumn(pageMargin()), horizontalArrangement = Arrangement.End) {
                Surface(
                    modifier = Modifier.padding(start = 48.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        question,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        if (answer != null) answerItems(answer, saved, busy, onOpen, onSave, onOpenSaved, onRetry)
        if (saving || (step != null && answer == null)) {
            item(key = "progress") {
                Row(
                    Modifier.pageColumn(pageMargin()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DelayedLoadingIndicator(loading = true, size = 40.dp)
                    Text(
                        when {
                            saving -> stringResource(R.string.ask_saving)
                            step.isNullOrEmpty() -> stringResource(R.string.ask_thinking)
                            else -> stringResource(R.string.ask_tool, step)
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
                    AskIssueBanner(issue, onRetry.takeIf { issue == AskIssue.Run || issue == AskIssue.Timeout })
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.answerItems(
    answer: VaultAnswer,
    saved: NoteId?,
    busy: Boolean,
    onOpen: (Evidence) -> Unit,
    onSave: () -> Unit,
    onOpenSaved: (NoteId) -> Unit,
    onRetry: () -> Unit,
) {
    val status = when (answer.status) {
        AnswerStatus.NoEvidence -> R.string.ask_none
        AnswerStatus.NeedsModel -> R.string.ask_needs_model
        AnswerStatus.Insufficient -> R.string.ask_insufficient
        AnswerStatus.ModelFailed -> when (answer.error) {
            GenerationError.Offline -> R.string.ask_error_offline
            GenerationError.OnDeviceUnavailable -> R.string.ask_error_on_device_unavailable
            GenerationError.AppCheckFailed -> R.string.ask_error_app_check
            GenerationError.QuotaExceeded -> R.string.ask_error_quota
            is GenerationError.Failed, null -> R.string.ask_model_failed
        }
        AnswerStatus.Answered -> null
    }
    if (answer.claims.isNotEmpty()) {
        item(key = "answer") {
            val markdown = remember(answer) { answer.chatMarkdown() }
            MarkdownPreview(
                markdown = markdown,
                modifier = Modifier.pageColumn(pageMargin()),
                typography = LoomaPreviewTypography(
                    body = LoomaTokens.type.noteBody,
                    headings = LoomaTokens.type.noteHeadings,
                    code = LoomaTokens.type.noteCode,
                ),
            )
        }
    }
    val notes = listOfNotNull(
        status,
        answer.meta?.originLabelRes(),
        R.string.ask_meta_fallback.takeIf { answer.meta?.fallbackUsed == true },
        R.string.ask_lexical.takeIf { !answer.semantic },
        R.string.ask_partial.takeIf { answer.partial },
    )
    if (notes.isNotEmpty()) {
        item(key = "status") {
            Column(Modifier.pageColumn(pageMargin()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                notes.forEach {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (answer.sources.isNotEmpty()) {
        item(key = "sources") {
            val margin = pageMargin()
            LazyRow(
                modifier = Modifier.widthIn(max = PageMaxWidth).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = margin),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(answer.sources) { index, source ->
                    Card(
                        onClick = { onOpen(source) },
                        enabled = !busy,
                        modifier = Modifier.width(240.dp),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = LoomaTokens.colors.card),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${index + 1}  ${source.id.path.relativePath}",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                source.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
    item(key = "actions") {
        Row(Modifier.pageColumn(pageMargin()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (answer.claims.isNotEmpty()) {
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
            }
            IconButton(onClick = onRetry, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                LoomaIcon(R.drawable.ic_retry, stringResource(R.string.ask_retry))
            }
        }
    }
}

@Composable
private fun AskIssueBanner(issue: AskIssue, onRetry: (() -> Unit)? = null) {
    val message = stringResource(
        when (issue) {
            AskIssue.Run -> R.string.ask_run_failed
            AskIssue.Timeout -> R.string.ask_timeout
            AskIssue.Changed -> R.string.ask_changed
            AskIssue.Save -> R.string.ask_save_failed
        },
    )
    if (onRetry == null) ErrorBanner(message = message)
    else ErrorBanner(message = message, actionLabel = stringResource(R.string.ask_retry), onAction = onRetry)
}

private fun VaultAnswer.chatMarkdown(): String =
    claims.joinToString("\n\n") { claim -> claim.text + claim.citations.joinToString("") { " [$it]" } }
