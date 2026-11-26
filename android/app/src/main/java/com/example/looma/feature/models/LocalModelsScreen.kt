package com.example.looma.feature.models

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import com.example.looma.data.assistant.LocalImport
import com.example.looma.data.assistant.LocalModelEntry
import com.example.looma.data.assistant.LocalModelState
import com.example.looma.ui.components.FeatureAction
import com.example.looma.ui.components.FeatureActionStyle
import com.example.looma.ui.components.FeaturePage
import com.example.looma.ui.components.FeatureProgress
import com.example.looma.ui.components.FeatureStatus
import com.example.looma.ui.components.FeatureStatusCard
import com.example.looma.ui.components.FeatureStatusTone
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.SectionHeader
import com.example.looma.ui.format.formatFileSize
import com.example.looma.ui.theme.LoomaTheme
import kotlin.math.roundToInt

@Composable
internal fun LocalModelsRoute(viewModel: ModelSettingsViewModel, onNavigateUp: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::importLocal)
    }
    LocalModelsScreen(
        state = state,
        onSelectDefault = viewModel::setDefault,
        onDownload = viewModel::download,
        onCancelDownload = viewModel::cancelDownload,
        onImport = { picker.launch(arrayOf("*/*")) },
        onNavigateUp = onNavigateUp,
    )
}

@Composable
internal fun LocalModelsScreen(
    state: ModelSettingsUiState,
    onSelectDefault: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onImport: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val importing = state.localImport == LocalImport.Running
    FeaturePage(
        iconRes = R.drawable.ic_memory,
        title = stringResource(R.string.models_local_hero),
        subtitle = stringResource(R.string.models_local_subtitle),
        modifier = modifier,
        topBar = { scrollBehavior ->
            ModelTopBar(stringResource(R.string.models_local_title), onNavigateUp, scrollBehavior)
        },
        actions = {
            FeatureAction(
                text = stringResource(R.string.models_local_import),
                onClick = onImport,
                style = FeatureActionStyle.Tonal,
                enabled = !importing,
                iconRes = R.drawable.ic_upload_file,
            )
        },
    ) {
        FeatureStatusCard(status = localStatus(state))
        Spacer(Modifier.height(32.dp))
        SectionHeader(title = stringResource(R.string.models_local_available))
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            state.localModels.forEachIndexed { index, entry ->
                LocalModelItem(
                    entry = entry,
                    index = index,
                    count = state.localModels.size,
                    selected = entry.id == state.defaultModelId,
                    onSelectDefault = { onSelectDefault(entry.id) },
                    onDownload = { onDownload(entry.id) },
                    onCancelDownload = { onCancelDownload(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun localStatus(state: ModelSettingsUiState): FeatureStatus {
    val context = LocalContext.current
    val downloading = state.localModels.firstOrNull { it.state is LocalModelState.Downloading }
    val default = state.localModels.firstOrNull { it.id == state.defaultModelId && it.state == LocalModelState.Ready }
    return when {
        state.localImport == LocalImport.Running -> FeatureStatus(
            label = stringResource(R.string.models_local_status_importing),
            title = stringResource(R.string.models_imported_name),
            detail = stringResource(R.string.models_local_import_detail),
            progress = FeatureProgress.Indeterminate,
        )
        state.localImport == LocalImport.Failed -> FeatureStatus(
            label = stringResource(R.string.models_local_status_failed),
            title = stringResource(R.string.models_imported_name),
            detail = stringResource(R.string.models_local_import_failed),
            tone = FeatureStatusTone.Error,
        )
        downloading != null -> {
            val fraction = (downloading.state as LocalModelState.Downloading).fraction
            FeatureStatus(
                label = stringResource(R.string.models_local_status_downloading),
                title = downloading.displayName(),
                detail = stringResource(R.string.models_local_downloaded_percent, (fraction * 100).roundToInt()),
                progress = FeatureProgress.Determinate(fraction),
            )
        }
        default != null -> FeatureStatus(
            label = stringResource(R.string.models_local_status_default),
            title = default.displayName(),
            detail = stringResource(R.string.models_on_device_size, formatFileSize(context, default.sizeBytes)),
        )
        else -> FeatureStatus(
            label = stringResource(R.string.models_local_status_none),
            title = stringResource(R.string.models_local_status_none_title),
            detail = stringResource(R.string.models_local_status_none_detail),
        )
    }
}

@Composable
private fun LocalModelItem(
    entry: LocalModelEntry,
    index: Int,
    count: Int,
    selected: Boolean,
    onSelectDefault: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val context = LocalContext.current
    val ready = entry.state == LocalModelState.Ready
    val name = entry.displayName()
    val size = formatFileSize(context, entry.sizeBytes)
    SegmentedListItem(
        selected = ready && selected,
        onClick = if (ready) onSelectDefault else onDownload,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        enabled = ready || entry.state == LocalModelState.NotDownloaded,
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().semantics { if (ready) role = Role.RadioButton },
        leadingContent = { LoomaIcon(iconRes = R.drawable.ic_memory, contentDescription = null) },
        supportingContent = {
            Text(entry.description?.let { stringResource(R.string.models_pair, size, it) } ?: size)
        },
        trailingContent = {
            when (val state = entry.state) {
                LocalModelState.Ready -> RadioButton(selected = selected, onClick = null)
                LocalModelState.NotDownloaded -> IconButton(onClick = onDownload, shapes = IconButtonDefaults.shapes()) {
                    LoomaIcon(
                        iconRes = R.drawable.ic_download,
                        contentDescription = stringResource(R.string.models_local_download, name),
                    )
                }
                is LocalModelState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularWavyProgressIndicator(
                        progress = { state.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.size(24.dp),
                    )
                    IconButton(onClick = onCancelDownload, shapes = IconButtonDefaults.shapes()) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_close,
                            contentDescription = stringResource(R.string.models_local_cancel, name),
                        )
                    }
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (entry.recommended) {
                Spacer(Modifier.size(8.dp))
                ModelBadge(
                    text = stringResource(R.string.models_local_recommended),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun PreviewScreen(state: ModelSettingsUiState) {
    LoomaTheme(dynamicColor = false) {
        LocalModelsScreen(
            state = state,
            onSelectDefault = {},
            onDownload = {},
            onCancelDownload = {},
            onImport = {},
            onNavigateUp = {},
        )
    }
}

@Preview(name = "本地模型 下载中", showBackground = true)
@Composable
private fun LocalModelsDownloadingPreview() =
    PreviewScreen(ModelSettingsUiState(localModels = ModelPreviewData.local, defaultModelId = "gemma-3n-e2b"))

@Preview(name = "本地模型 默认", showBackground = true)
@Composable
private fun LocalModelsReadyPreview() = PreviewScreen(
    ModelSettingsUiState(
        localModels = ModelPreviewData.local.map {
            if (it.state is LocalModelState.Downloading) it.copy(state = LocalModelState.NotDownloaded) else it
        },
        defaultModelId = "gemma-3n-e2b",
    ),
)

@Preview(name = "本地模型 空", showBackground = true)
@Composable
private fun LocalModelsEmptyPreview() = PreviewScreen(
    ModelSettingsUiState(localModels = ModelPreviewData.local.map { it.copy(state = LocalModelState.NotDownloaded) }),
)
