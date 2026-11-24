package com.example.looma.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.search.SemanticWorkState
import com.example.looma.embedding.EmbeddingBackend
import com.example.looma.ui.components.FeatureAction
import com.example.looma.ui.components.FeatureActionStyle
import com.example.looma.ui.components.FeaturePage
import com.example.looma.ui.components.FeatureProgress
import com.example.looma.ui.components.FeatureStatus
import com.example.looma.ui.components.FeatureStatusCard
import com.example.looma.ui.components.FeatureStatusTone
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.SectionHeader
import com.example.looma.ui.theme.LoomaTheme

@Immutable
internal data class SemanticOperationUiState(
    val status: SemanticWorkState?,
    val action: SemanticSettingsAction,
)

@Immutable
internal data class SemanticSearchSettingsUiState(
    val supported: Boolean,
    val vaultName: String,
    val backend: EmbeddingBackend,
    val model: SemanticOperationUiState,
    val index: SemanticOperationUiState,
)

private val SemanticWorkState?.active: Boolean
    get() = this == SemanticWorkState.Queued || this is SemanticWorkState.Running

private val SemanticWorkState?.retryable: Boolean
    get() = this == SemanticWorkState.Cancelled || this == SemanticWorkState.Failed

@Composable
internal fun SemanticSearchSettingsRoute(
    viewModel: SemanticSearchSettingsViewModel,
    vaultName: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backend by viewModel.backend.collectAsStateWithLifecycle()
    val modelStatus by viewModel.model.status.collectAsStateWithLifecycle()
    val modelAction by viewModel.model.action.collectAsStateWithLifecycle()
    val indexStatus by viewModel.index.status.collectAsStateWithLifecycle()
    val indexAction by viewModel.index.action.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val requestNotifications = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedForNotifications &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedForNotifications = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    SemanticSearchSettingsScreen(
        state = SemanticSearchSettingsUiState(
            supported = viewModel.supported,
            vaultName = vaultName,
            backend = backend,
            model = SemanticOperationUiState(status = modelStatus, action = modelAction),
            index = SemanticOperationUiState(status = indexStatus, action = indexAction),
        ),
        onSelectBackend = viewModel::setBackend,
        onStartModel = {
            viewModel.model.enqueue()
            requestNotifications()
        },
        onCancelModel = viewModel.model::cancel,
        onDeleteModel = viewModel.model::delete,
        onStartIndex = {
            viewModel.index.enqueue()
            requestNotifications()
        },
        onCancelIndex = viewModel.index::cancel,
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )
}

@Composable
internal fun SemanticSearchSettingsScreen(
    state: SemanticSearchSettingsUiState,
    onSelectBackend: (EmbeddingBackend) -> Unit,
    onStartModel: () -> Unit,
    onCancelModel: () -> Unit,
    onDeleteModel: () -> Unit,
    onStartIndex: () -> Unit,
    onCancelIndex: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    if (showDeleteConfirm) {
        ConfirmDeleteModelDialog(
            onConfirm = {
                showDeleteConfirm = false
                onDeleteModel()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    val available = state.supported && state.model.status != SemanticWorkState.Unavailable
    val failed = state.model.action == SemanticSettingsAction.Failed ||
        state.index.action == SemanticSettingsAction.Failed

    FeaturePage(
        iconRes = R.drawable.ic_sparkle,
        title = stringResource(R.string.settings_semantic_intro_title),
        subtitle = stringResource(R.string.settings_semantic_intro),
        modifier = modifier,
        topBar = { scrollBehavior ->
            TopAppBar(
                title = { Text(stringResource(R.string.settings_semantic_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp, shapes = IconButtonDefaults.shapes()) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_arrow_back,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        actions = {
            if (available) {
                SemanticActions(
                    state = state,
                    onStartModel = onStartModel,
                    onCancelModel = onCancelModel,
                    onRequestDeleteModel = { showDeleteConfirm = true },
                    onStartIndex = onStartIndex,
                    onCancelIndex = onCancelIndex,
                )
            }
        },
    ) {
        FeatureStatusCard(
            status = semanticStatus(state),
            loading = state.supported && state.model.status == null,
        )
        if (failed) {
            Text(
                text = stringResource(R.string.settings_semantic_action_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp),
            )
        }
        if (available) {
            Spacer(Modifier.height(32.dp))
            BackendGroup(
                backend = state.backend,
                enabled = !state.model.status.active && state.model.action != SemanticSettingsAction.Submitting,
                onSelectBackend = onSelectBackend,
            )
            Spacer(Modifier.height(24.dp))
            Column(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_semantic_model_details),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_semantic_background_work),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun semanticStatus(state: SemanticSearchSettingsUiState): FeatureStatus? {
    val unavailable = FeatureStatus(
        label = stringResource(R.string.settings_semantic_status_unavailable),
        title = stringResource(R.string.settings_semantic_model),
        detail = stringResource(R.string.settings_semantic_unsupported),
        tone = FeatureStatusTone.Error,
    )
    if (!state.supported) return unavailable
    val model = state.model.status ?: return null
    return when (model) {
        SemanticWorkState.Unavailable -> unavailable
        SemanticWorkState.Ready -> indexStatus(state.vaultName, state.index.status)
        else -> modelStatus(model)
    }
}

@Composable
private fun modelStatus(model: SemanticWorkState): FeatureStatus {
    val step = stringResource(R.string.settings_semantic_step, 1, 2)
    val title = stringResource(R.string.settings_semantic_model)
    return when (model) {
        SemanticWorkState.Failed -> FeatureStatus(
            label = stringResource(R.string.settings_semantic_status_failed),
            title = title,
            detail = stringResource(R.string.settings_semantic_download_failed),
            tone = FeatureStatusTone.Error,
        )
        SemanticWorkState.Queued -> FeatureStatus(
            label = step,
            title = title,
            detail = stringResource(R.string.settings_semantic_download_queued),
            progress = FeatureProgress.Indeterminate,
        )
        is SemanticWorkState.Running -> FeatureStatus(
            label = step,
            title = title,
            detail = stringResource(R.string.settings_semantic_downloading, model.completed),
            progress = progressOf(model),
        )
        SemanticWorkState.Cancelled -> FeatureStatus(
            label = step,
            title = title,
            detail = stringResource(R.string.settings_semantic_download_cancelled),
        )
        else -> FeatureStatus(
            label = step,
            title = title,
            detail = stringResource(R.string.settings_semantic_model_size),
        )
    }
}

@Composable
private fun indexStatus(vaultName: String, index: SemanticWorkState?): FeatureStatus {
    val step = stringResource(R.string.settings_semantic_step, 2, 2)
    val title = vaultName.ifBlank { stringResource(R.string.settings_semantic_index) }
    return when (index) {
        null -> FeatureStatus(step, title, stringResource(R.string.settings_semantic_index_checking))
        SemanticWorkState.Unavailable -> FeatureStatus(
            label = stringResource(R.string.settings_semantic_status_unavailable),
            title = title,
            detail = stringResource(R.string.settings_semantic_index_unavailable),
            tone = FeatureStatusTone.Error,
        )
        SemanticWorkState.Idle -> FeatureStatus(
            label = step,
            title = title,
            detail = stringResource(R.string.settings_semantic_index_not_built),
            note = stringResource(R.string.settings_semantic_index_idle),
        )
        SemanticWorkState.Queued -> FeatureStatus(
            label = step,
            title = title,
            detail = stringResource(R.string.settings_semantic_index_queued),
            progress = FeatureProgress.Indeterminate,
        )
        is SemanticWorkState.Running -> FeatureStatus(
            label = step,
            title = title,
            detail = if (index.total > 0) {
                stringResource(R.string.settings_semantic_indexing, index.completed, index.total)
            } else {
                stringResource(R.string.settings_semantic_index_preparing)
            },
            progress = progressOf(index),
        )
        SemanticWorkState.Cancelled -> FeatureStatus(
            label = step,
            title = title,
            detail = stringResource(R.string.settings_semantic_index_cancelled),
        )
        SemanticWorkState.Failed -> FeatureStatus(
            label = stringResource(R.string.settings_semantic_status_failed),
            title = title,
            detail = stringResource(R.string.settings_semantic_index_failed),
            tone = FeatureStatusTone.Error,
        )
        SemanticWorkState.Partial -> FeatureStatus(
            label = stringResource(R.string.settings_semantic_status_ready),
            title = title,
            detail = stringResource(R.string.settings_semantic_model_ready),
            note = stringResource(R.string.semantic_index_partial),
        )
        SemanticWorkState.Ready -> FeatureStatus(
            label = stringResource(R.string.settings_semantic_status_ready),
            title = title,
            detail = stringResource(R.string.settings_semantic_model_ready),
            note = stringResource(R.string.settings_semantic_index_ready),
        )
    }
}

private fun progressOf(running: SemanticWorkState.Running): FeatureProgress =
    if (running.total > 0) {
        FeatureProgress.Determinate(running.completed.toFloat() / running.total)
    } else {
        FeatureProgress.Indeterminate
    }

@Composable
private fun FlowRowScope.SemanticActions(
    state: SemanticSearchSettingsUiState,
    onStartModel: () -> Unit,
    onCancelModel: () -> Unit,
    onRequestDeleteModel: () -> Unit,
    onStartIndex: () -> Unit,
    onCancelIndex: () -> Unit,
) {
    val model = state.model.status
    val index = state.index.status
    val modelSubmitting = state.model.action == SemanticSettingsAction.Submitting
    val indexSubmitting = state.index.action == SemanticSettingsAction.Submitting
    when {
        model == null -> Unit
        model.active -> FeatureAction(
            text = stringResource(R.string.dialog_cancel),
            onClick = onCancelModel,
            style = FeatureActionStyle.Tonal,
            enabled = !modelSubmitting,
        )
        model != SemanticWorkState.Ready -> FeatureAction(
            text = stringResource(if (model.retryable) R.string.settings_semantic_retry else R.string.settings_semantic_download),
            onClick = onStartModel,
            enabled = !modelSubmitting,
        )
        index.active -> FeatureAction(
            text = stringResource(R.string.dialog_cancel),
            onClick = onCancelIndex,
            style = FeatureActionStyle.Tonal,
            enabled = !indexSubmitting,
        )
        else -> {
            FeatureAction(
                text = stringResource(R.string.settings_semantic_delete_model),
                onClick = onRequestDeleteModel,
                style = FeatureActionStyle.Tonal,
                enabled = !modelSubmitting && !indexSubmitting,
            )
            if (index != null && index != SemanticWorkState.Unavailable) {
                FeatureAction(
                    text = stringResource(
                        when {
                            index.retryable -> R.string.settings_semantic_retry
                            index == SemanticWorkState.Partial || index == SemanticWorkState.Ready -> R.string.settings_semantic_update_index
                            else -> R.string.settings_semantic_build_index
                        },
                    ),
                    onClick = onStartIndex,
                    enabled = !indexSubmitting && !modelSubmitting,
                )
            }
        }
    }
}

private data class BackendOption(
    val backend: EmbeddingBackend,
    @DrawableRes val iconRes: Int,
    @StringRes val label: Int,
    @StringRes val hint: Int,
)

private val BackendOptions = listOf(
    BackendOption(EmbeddingBackend.CPU, R.drawable.ic_memory, R.string.settings_semantic_backend_cpu, R.string.settings_semantic_backend_cpu_hint),
    BackendOption(EmbeddingBackend.NPU, R.drawable.ic_bolt, R.string.settings_semantic_backend_npu, R.string.settings_semantic_backend_npu_hint),
)

@Composable
private fun BackendGroup(
    backend: EmbeddingBackend,
    enabled: Boolean,
    onSelectBackend: (EmbeddingBackend) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.settings_semantic_backend_label))
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            BackendOptions.forEachIndexed { index, option ->
                val selected = backend == option.backend
                SegmentedListItem(
                    selected = selected,
                    onClick = { onSelectBackend(option.backend) },
                    shapes = ListItemDefaults.segmentedShapes(index, BackendOptions.size),
                    enabled = enabled,
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
                    leadingContent = { LoomaIcon(iconRes = option.iconRes, contentDescription = null) },
                    supportingContent = { Text(stringResource(option.hint)) },
                    trailingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
                ) {
                    Text(stringResource(option.label))
                }
            }
        }
    }
}

@Composable
private fun ConfirmDeleteModelDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_semantic_delete_confirm_title)) },
        text = { Text(stringResource(R.string.settings_semantic_delete_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, shapes = ButtonDefaults.shapes()) {
                Text(
                    text = stringResource(R.string.settings_semantic_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun PreviewScreen(
    backend: EmbeddingBackend,
    model: SemanticWorkState,
    index: SemanticWorkState,
) {
    LoomaTheme(dynamicColor = false) {
        SemanticSearchSettingsScreen(
            state = SemanticSearchSettingsUiState(
                supported = true,
                vaultName = "My Notes",
                backend = backend,
                model = SemanticOperationUiState(model, SemanticSettingsAction.Idle),
                index = SemanticOperationUiState(index, SemanticSettingsAction.Idle),
            ),
            onSelectBackend = {},
            onStartModel = {},
            onCancelModel = {},
            onDeleteModel = {},
            onStartIndex = {},
            onCancelIndex = {},
            onNavigateUp = {},
        )
    }
}

@Preview(name = "未下载", showBackground = true)
@Composable
private fun IdlePreview() = PreviewScreen(EmbeddingBackend.CPU, SemanticWorkState.Idle, SemanticWorkState.Idle)

@Preview(name = "下载中", showBackground = true)
@Composable
private fun DownloadingPreview() =
    PreviewScreen(EmbeddingBackend.NPU, SemanticWorkState.Running(completed = 42, total = 100), SemanticWorkState.Idle)

@Preview(name = "待索引", showBackground = true)
@Composable
private fun ModelReadyPreview() = PreviewScreen(EmbeddingBackend.CPU, SemanticWorkState.Ready, SemanticWorkState.Idle)

@Preview(name = "已就绪 NPU", showBackground = true)
@Composable
private fun ReadyNpuPreview() = PreviewScreen(EmbeddingBackend.NPU, SemanticWorkState.Ready, SemanticWorkState.Ready)
