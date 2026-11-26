@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.looma.feature.note.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.data.assistant.Connection
import com.example.looma.data.assistant.Evidence
import com.example.looma.data.assistant.EvidenceMatchSource
import com.example.looma.data.assistant.VaultEvidence
import com.example.looma.data.assistant.WeaveSemanticStatus
import com.example.looma.data.assistant.WeaveSuggestions
import com.example.looma.data.assistant.addition
import com.example.looma.domain.markdown.MarkdownNavigation
import com.example.looma.domain.markdown.NotePassage
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.Vault
import com.example.looma.feature.note.NoteViewModel
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.components.ErrorBanner
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.markdown.MarkdownPreview
import com.example.looma.ui.theme.LoomaTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WeaveAction(
    vault: Vault,
    id: NoteId,
    repository: VaultEvidence,
    editor: NoteViewModel,
    focus: () -> TextRange?,
    open: (Evidence) -> Unit,
    onOpenSemanticSettings: () -> Unit,
) {
    var showing by remember(id) { mutableStateOf(false) }
    var range by remember(id) { mutableStateOf<TextRange?>(null) }
    IconButton(onClick = { range = focus(); showing = true }, shapes = IconButtonDefaults.shapes()) {
        LoomaIcon(R.drawable.ic_sparkle, stringResource(R.string.weave_title))
    }
    if (!showing) return

    var dialogState by remember { mutableStateOf<WeaveSheetState>(WeaveSheetState.Loading) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var passage by remember { mutableStateOf<NotePassage?>(null) }
    var scoped by remember { mutableStateOf(range?.collapsed == false) }
    var excludeLinked by remember { mutableStateOf(true) }
    var allExpanded by remember { mutableStateOf(true) }
    var expandedOverrides by remember { mutableStateOf<Map<NoteId, Boolean>>(emptyMap()) }
    var sheetMenuOpen by remember { mutableStateOf(false) }
    var copiedNotice by remember { mutableStateOf(false) }
    val applying = (dialogState as? WeaveSheetState.Preview)?.applying == true
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    LaunchedEffect(id, refresh, scoped, excludeLinked) {
        copiedNotice = false
        dialogState = WeaveSheetState.Loading
        dialogState = try {
            val source = editor.editor.snapshot()
            passage = passage?.takeIf { it.source === source } ?: range?.let { r ->
                withContext(Dispatchers.Default) { MarkdownNavigation.scan(source).passage(source, r.min, r.max) }
            }
            WeaveSheetState.Ready(
                repository.related(
                    vault,
                    id,
                    source,
                    passage.takeIf { scoped },
                    excludeLinked = excludeLinked,
                ),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            WeaveSheetState.Failed
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!applying) showing = false },
        sheetState = sheetState,
        containerColor = LoomaTokens.colors.sheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val current = dialogState
                if (current is WeaveSheetState.Preview) {
                    IconButton(
                        onClick = { dialogState = WeaveSheetState.Ready(current.suggestions) },
                        enabled = !current.applying,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        LoomaIcon(R.drawable.ic_arrow_back, stringResource(R.string.weave_back))
                    }
                    Text(
                        text = stringResource(R.string.weave_confirm),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    val ready = current as? WeaveSheetState.Ready
                    Text(
                        text = stringResource(R.string.weave_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { refresh++ },
                        enabled = current !is WeaveSheetState.Loading,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        LoomaIcon(R.drawable.ic_retry, stringResource(R.string.weave_retry))
                    }
                    Box {
                        IconButton(
                            onClick = { sheetMenuOpen = true },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            LoomaIcon(
                                R.drawable.ic_more_vert,
                                stringResource(R.string.weave_more_actions),
                            )
                        }
                        val hasUnhide = ready != null && ready.suggestions.hiddenCount > 0
                        val itemCount = if (hasUnhide) 3 else 2
                        DropdownMenuPopup(
                            expanded = sheetMenuOpen,
                            onDismissRequest = { sheetMenuOpen = false },
                        ) {
                            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                                DropdownMenuItem(
                                    shape = MenuDefaults.itemShape(0, itemCount).shape,
                                    text = {
                                        Text(
                                            stringResource(
                                                if (allExpanded) {
                                                    R.string.weave_collapse_all
                                                } else {
                                                    R.string.weave_expand_all
                                                },
                                            ),
                                        )
                                    },
                                    onClick = {
                                        allExpanded = !allExpanded
                                        expandedOverrides = emptyMap()
                                        sheetMenuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    shape = MenuDefaults.itemShape(1, itemCount).shape,
                                    text = { Text(stringResource(R.string.weave_copy_links)) },
                                    enabled = ready != null && ready.suggestions.items.isNotEmpty(),
                                    onClick = {
                                        val items = ready?.suggestions?.items.orEmpty()
                                        if (items.isNotEmpty()) {
                                            val payload = items.joinToString("\n") { "- [[${it.reference}]]" }
                                            context.getSystemService(ClipboardManager::class.java)
                                                ?.setPrimaryClip(ClipData.newPlainText("links", payload))
                                            copiedNotice = true
                                        }
                                        sheetMenuOpen = false
                                    },
                                )
                                if (hasUnhide) {
                                    DropdownMenuItem(
                                        shape = MenuDefaults.itemShape(2, itemCount).shape,
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.weave_unhide_all,
                                                    ready.suggestions.hiddenCount,
                                                ),
                                            )
                                        },
                                        onClick = {
                                            sheetMenuOpen = false
                                            scope.launch {
                                                try {
                                                    repository.unhideAllConnections(id)
                                                    refresh++
                                                } catch (cancel: CancellationException) {
                                                    throw cancel
                                                } catch (_: Exception) {
                                                    dialogState = ready.copy(actionError = true)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (dialogState !is WeaveSheetState.Preview) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (passage != null) {
                        FilterChip(
                            selected = !scoped,
                            onClick = { scoped = false },
                            label = { Text(stringResource(R.string.weave_scope_note)) },
                            shapes = FilterChipDefaults.shapes(),
                        )
                        FilterChip(
                            selected = scoped,
                            onClick = { scoped = true },
                            label = { Text(stringResource(R.string.weave_scope_passage)) },
                            shapes = FilterChipDefaults.shapes(),
                        )
                    }
                    FilterChip(
                        selected = excludeLinked,
                        onClick = { excludeLinked = !excludeLinked },
                        label = { Text(stringResource(R.string.weave_hide_linked)) },
                        shapes = FilterChipDefaults.shapes(),
                    )
                }
                passage?.let { activePassage ->
                    if (scoped) {
                        Surface(
                            color = LoomaTokens.colors.cardInset,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.weave_focus_label),
                                    style = LoomaTokens.type.cardMeta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                MarkdownPreview(
                                    markdown = trimExcerpt(activePassage.excerpt(220), 220),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 96.dp)
                                        .clipToBounds(),
                                )
                            }
                        }
                    }
                }
            }

            when (val current = dialogState) {
                WeaveSheetState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        DelayedLoadingIndicator(true)
                    }
                }
                WeaveSheetState.Failed -> {
                    ErrorBanner(
                        message = stringResource(R.string.weave_changed),
                        actionLabel = stringResource(R.string.weave_retry),
                        onAction = { refresh++ },
                    )
                }
                is WeaveSheetState.Ready -> {
                    SemanticStatusBanner(
                        status = current.suggestions.semanticStatus,
                        onAction = {
                            if (editor.prepareToOpenLinkedNote()) {
                                showing = false
                                onOpenSemanticSettings()
                            }
                        },
                    )
                    if (copiedNotice) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                LoomaIcon(
                                    iconRes = R.drawable.ic_check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = stringResource(R.string.weave_copied_links),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    if (current.actionError) {
                        ErrorBanner(
                            message = stringResource(R.string.weave_action_failed),
                            actionLabel = stringResource(R.string.weave_retry),
                            onAction = { refresh++ },
                        )
                    }
                    if (current.suggestions.items.isEmpty()) {
                        Text(
                            text = stringResource(R.string.weave_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        for (item in current.suggestions.items) {
                            val itemExpanded = expandedOverrides[item.evidence.id] ?: allExpanded
                            ConnectionCard(
                                item = item,
                                expanded = itemExpanded,
                                onToggleExpand = {
                                    expandedOverrides = expandedOverrides + (item.evidence.id to !itemExpanded)
                                },
                                onTogglePin = {
                                    scope.launch {
                                        try {
                                            repository.togglePin(id, item.evidence.id)
                                            refresh++
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            dialogState = current.copy(actionError = true)
                                        }
                                    }
                                },
                                onHide = {
                                    scope.launch {
                                        try {
                                            repository.hideConnection(id, item.evidence.id)
                                            refresh++
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            dialogState = current.copy(actionError = true)
                                        }
                                    }
                                },
                                onOpenNote = {
                                    scope.launch {
                                        try {
                                            val resolved = repository.resolve(vault, item.evidence)
                                            if (resolved != null && editor.prepareToOpenLinkedNote()) {
                                                showing = false
                                                open(resolved)
                                            } else {
                                                dialogState = current.copy(actionError = true)
                                            }
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            dialogState = current.copy(actionError = true)
                                        }
                                    }
                                },
                                onPreviewInsert = {
                                    dialogState = WeaveSheetState.Preview(current.suggestions, item)
                                },
                            )
                        }
                    }
                }
                is WeaveSheetState.Preview -> {
                    if (current.actionError) {
                        ErrorBanner(
                            message = stringResource(R.string.weave_action_failed),
                            actionLabel = stringResource(R.string.weave_retry),
                            onAction = { refresh++ },
                        )
                    }
                    val proposal = current.proposal
                    Text(
                        text = stringResource(R.string.weave_preview_target),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Card(
                        shape = MaterialTheme.shapes.largeIncreased,
                        colors = CardDefaults.cardColors(containerColor = LoomaTokens.colors.card),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = proposal.title,
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            ConnectionProvenanceRow(proposal)
                            Surface(
                                color = LoomaTokens.colors.cardInset,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                MarkdownPreview(
                                    markdown = trimExcerpt(proposal.evidence.text, 420),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                        .heightIn(max = 180.dp)
                                        .clipToBounds(),
                                )
                            }
                        }
                    }

                    val focusPassage = proposal.passage
                    Text(
                        text = stringResource(
                            if (focusPassage == null) R.string.weave_preview_location_end
                            else R.string.weave_preview_location_passage,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        color = LoomaTokens.colors.cardInset,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val linkToken = "[[${proposal.reference}]]"
                        val rawPreview = if (focusPassage == null) {
                            linkToken
                        } else {
                            focusPassage.insertionPreview(proposal.addition(focusPassage.source))
                        }
                        val linkColor = MaterialTheme.colorScheme.primary
                        val linkBackground = MaterialTheme.colorScheme.primaryContainer
                        val annotated = buildAnnotatedString {
                            val idx = rawPreview.indexOf(linkToken)
                            if (idx >= 0) {
                                append(rawPreview.substring(0, idx))
                                withStyle(SpanStyle(color = linkColor, background = linkBackground)) {
                                    append(linkToken)
                                }
                                append(rawPreview.substring(idx + linkToken.length))
                            } else {
                                append(rawPreview)
                            }
                        }
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(
                            onClick = { dialogState = WeaveSheetState.Ready(current.suggestions) },
                            enabled = !current.applying,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.weave_back))
                        }
                        Button(
                            enabled = !current.applying,
                            shapes = ButtonDefaults.shapes(),
                            onClick = {
                                dialogState = current.copy(applying = true, actionError = false)
                                scope.launch {
                                    try {
                                        if (editor.confirmWeave(vault, current.proposal, repository)) {
                                            showing = false
                                        } else {
                                            dialogState = current.copy(applying = false, actionError = true)
                                        }
                                    } catch (cancel: CancellationException) {
                                        dialogState = current.copy(applying = false)
                                        throw cancel
                                    } catch (_: Exception) {
                                        dialogState = current.copy(applying = false, actionError = true)
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.weave_insert))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SemanticStatusBanner(
    status: WeaveSemanticStatus,
    onAction: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val isNeutral = status == WeaveSemanticStatus.Ready || status == WeaveSemanticStatus.Unavailable
    val containerColor = if (isNeutral) scheme.surfaceContainerHigh else scheme.tertiaryContainer
    val contentColor = if (isNeutral) scheme.onSurfaceVariant else scheme.onTertiaryContainer
    val iconRes = when (status) {
        WeaveSemanticStatus.Ready -> R.drawable.ic_sparkle
        WeaveSemanticStatus.Failed -> R.drawable.ic_warning
        else -> R.drawable.ic_info
    }
    val messageRes = when (status) {
        WeaveSemanticStatus.Ready -> R.string.weave_status_hybrid
        WeaveSemanticStatus.Partial -> R.string.weave_status_partial
        WeaveSemanticStatus.ModelMissing -> R.string.weave_status_model_missing
        WeaveSemanticStatus.ModelDownloading -> R.string.weave_status_model_downloading
        WeaveSemanticStatus.IndexMissing -> R.string.weave_status_index_missing
        WeaveSemanticStatus.Indexing -> R.string.weave_status_indexing
        WeaveSemanticStatus.Unavailable -> R.string.weave_status_unavailable
        WeaveSemanticStatus.Failed -> R.string.weave_status_failed
    }
    val actionRes = when (status) {
        WeaveSemanticStatus.Ready, WeaveSemanticStatus.Unavailable -> null
        WeaveSemanticStatus.ModelMissing -> R.string.weave_download_model
        WeaveSemanticStatus.IndexMissing -> R.string.weave_build_index
        WeaveSemanticStatus.ModelDownloading,
        WeaveSemanticStatus.Indexing,
        WeaveSemanticStatus.Partial,
        WeaveSemanticStatus.Failed,
        -> R.string.weave_open_semantic_settings
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                start = 16.dp,
                end = if (actionRes != null) 8.dp else 16.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoomaIcon(
                iconRes = iconRes,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (actionRes != null) {
                TextButton(
                    onClick = onAction,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                ) {
                    Text(stringResource(actionRes))
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    item: Connection,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onTogglePin: () -> Unit,
    onHide: () -> Unit,
    onOpenNote: () -> Unit,
    onPreviewInsert: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.largeIncreased,
        colors = CardDefaults.cardColors(containerColor = LoomaTokens.colors.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onToggleExpand,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    LoomaIcon(
                        if (expanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down,
                        stringResource(
                            if (expanded) R.string.weave_collapse_preview else R.string.weave_expand_preview,
                        ),
                    )
                }
                Box {
                    var menuOpen by remember(item.evidence.id) { mutableStateOf(false) }
                    IconButton(
                        onClick = { menuOpen = true },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        LoomaIcon(
                            R.drawable.ic_more_vert,
                            stringResource(R.string.weave_more_actions),
                        )
                    }
                    DropdownMenuPopup(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                            DropdownMenuItem(
                                shape = MenuDefaults.itemShape(0, 2).shape,
                                text = {
                                    Text(
                                        stringResource(
                                            if (item.pinned) R.string.weave_unpin else R.string.weave_pin,
                                        ),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onTogglePin()
                                },
                            )
                            DropdownMenuItem(
                                shape = MenuDefaults.itemShape(1, 2).shape,
                                text = { Text(stringResource(R.string.weave_hide)) },
                                onClick = {
                                    menuOpen = false
                                    onHide()
                                },
                            )
                        }
                    }
                }
            }
            val folder = item.evidence.id.path.relativePath.substringBeforeLast('/', "")
            val metadata = listOf(folder, item.evidence.section).filter { it.isNotBlank() }.joinToString(" · ")
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata,
                    style = LoomaTokens.type.cardMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ConnectionProvenanceRow(item)
            if (item.keywords.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.weave_keywords, item.keywords.joinToString(" · ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.tags.isNotEmpty()) {
                Text(
                    text = item.tags.joinToString(" ") { "#$it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (item.keywords.isEmpty() && item.tags.isEmpty() && item.matchedTitle) {
                Text(
                    text = stringResource(R.string.weave_matched_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Surface(
                    color = LoomaTokens.colors.cardInset,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MarkdownPreview(
                        markdown = trimExcerpt(item.evidence.text, 420),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .heightIn(max = 180.dp)
                            .clipToBounds(),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(
                    onClick = onOpenNote,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.weave_open_note))
                }
                if (!item.alreadyLinked) {
                    FilledTonalButton(
                        onClick = onPreviewInsert,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.weave_preview_insert))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionProvenanceRow(item: Connection) {
    val scheme = MaterialTheme.colorScheme
    val (sourceContainer, sourceContent, sourceLabel) = when (item.matchSource) {
        EvidenceMatchSource.Hybrid -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, R.string.weave_source_hybrid)
        EvidenceMatchSource.Semantic -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, R.string.weave_source_semantic)
        EvidenceMatchSource.Lexical -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, R.string.weave_source_lexical)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (item.pinned) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.weave_pinned),
                    style = LoomaTokens.type.cardMeta,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = sourceContainer,
            contentColor = sourceContent,
        ) {
            Text(
                text = stringResource(sourceLabel),
                style = LoomaTokens.type.cardMeta,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        item.evidence.similarity?.let { sim ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurfaceVariant,
            ) {
                Text(
                    text = stringResource(R.string.weave_similarity_percent, (sim.coerceIn(0.0, 1.0) * 100).roundToInt()),
                    style = LoomaTokens.type.cardMeta,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (item.alreadyLinked) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurfaceVariant,
            ) {
                Text(
                    text = stringResource(R.string.weave_already_linked),
                    style = LoomaTokens.type.cardMeta,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun trimExcerpt(text: String, limit: Int): String {
    val trimmed = text.trim()
    if (trimmed.length <= limit) return trimmed
    var end = limit
    if (end > 0 && trimmed[end - 1].isHighSurrogate() && trimmed[end].isLowSurrogate()) {
        end--
    }
    return trimmed.substring(0, end).trimEnd() + "…"
}

private sealed interface WeaveSheetState {
    data object Loading : WeaveSheetState
    data class Ready(val suggestions: WeaveSuggestions, val actionError: Boolean = false) : WeaveSheetState
    data class Preview(
        val suggestions: WeaveSuggestions,
        val proposal: Connection,
        val applying: Boolean = false,
        val actionError: Boolean = false,
    ) : WeaveSheetState
    data object Failed : WeaveSheetState
}
