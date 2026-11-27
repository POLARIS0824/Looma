@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.example.looma.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.sync.SyncPlan
import com.example.looma.domain.sync.SyncPlanItem
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.components.ErrorBanner
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.format.formatFileSize
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun SyncPreviewSheet(
    previewState: SyncPreviewState,
    actionState: SyncActionState,
    onRetry: () -> Unit,
    onConfirmSync: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (previewState is SyncPreviewState.Closed) return
    val sheetState = rememberExpandedSheetState()
    val submitting = actionState == SyncActionState.Submitting

    ModalBottomSheet(
        onDismissRequest = { if (!submitting) onDismiss() },
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
            Text(
                text = stringResource(R.string.settings_sync_preview_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            when (previewState) {
                SyncPreviewState.Closed -> Unit
                SyncPreviewState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        DelayedLoadingIndicator(loading = true)
                        Text(
                            text = stringResource(R.string.settings_sync_preview_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.dialog_cancel))
                        }
                    }
                }
                is SyncPreviewState.Failed -> {
                    ErrorBanner(
                        message = s3FailureMessage(previewState.messageRes, previewState.detail),
                        actionLabel = stringResource(R.string.home_retry),
                        onAction = onRetry,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.dialog_cancel))
                        }
                    }
                }
                is SyncPreviewState.Ready -> {
                    SyncPreviewReadyContent(
                        plan = previewState.preview.plan,
                        actionState = actionState,
                        onConfirmSync = onConfirmSync,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncPreviewReadyContent(
    plan: SyncPlan,
    actionState: SyncActionState,
    onConfirmSync: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val submitting = actionState == SyncActionState.Submitting

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = LoomaTokens.colors.cardInset,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            LoomaIcon(
                iconRes = R.drawable.ic_info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.settings_sync_preview_no_delete),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = LoomaTokens.colors.card,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyncPreviewMetricRow(
                label = stringResource(R.string.settings_sync_preview_upload),
                value = stringResource(
                    R.string.settings_sync_preview_count_bytes,
                    plan.uploadCount,
                    formatFileSize(context, plan.totalUploadBytes),
                ),
            )
            SyncPreviewMetricRow(
                label = stringResource(R.string.settings_sync_preview_download),
                value = stringResource(
                    R.string.settings_sync_preview_count_bytes,
                    plan.downloadCount,
                    formatFileSize(context, plan.totalDownloadBytes),
                ),
            )
            SyncPreviewMetricRow(
                label = stringResource(R.string.settings_sync_preview_matched),
                value = stringResource(
                    R.string.settings_sync_preview_count,
                    plan.matchedCount + plan.unchangedCount,
                ),
            )
            if (plan.conflictCount > 0) {
                SyncPreviewMetricRow(
                    label = stringResource(R.string.settings_sync_preview_conflicts),
                    value = stringResource(R.string.settings_sync_preview_count, plan.conflictCount),
                    accentColor = MaterialTheme.colorScheme.error,
                    iconRes = R.drawable.ic_warning,
                )
            }
            if (plan.skipped.isNotEmpty()) {
                SyncPreviewMetricRow(
                    label = stringResource(R.string.settings_sync_preview_skipped),
                    value = stringResource(R.string.settings_sync_preview_count, plan.skipped.size),
                )
            }
        }
    }

    val nothingToTransfer = plan.uploadCount == 0 && plan.downloadCount == 0 && plan.conflictCount == 0
    if (nothingToTransfer) {
        Text(
            text = stringResource(R.string.settings_sync_preview_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val sampleItems = previewSampleItems(plan)
        if (sampleItems.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.settings_sync_preview_samples),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                sampleItems.forEach { item ->
                    val symbol = when (item) {
                        is SyncPlanItem.ResolveConflict -> "!"
                        is SyncPlanItem.UploadNew, is SyncPlanItem.UploadUpdate -> "↑"
                        is SyncPlanItem.DownloadNew, is SyncPlanItem.DownloadUpdate -> "↓"
                        else -> ""
                    }
                    val symbolColor = if (item is SyncPlanItem.ResolveConflict) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = symbol,
                            style = LoomaTokens.type.count,
                            color = symbolColor,
                        )
                        Text(
                            text = item.path.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    if (actionState == SyncActionState.Failed) {
        Text(
            text = stringResource(R.string.settings_sync_action_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        TextButton(
            onClick = onDismiss,
            enabled = !submitting,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(R.string.dialog_cancel))
        }
        Button(
            onClick = onConfirmSync,
            enabled = !submitting,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(R.string.settings_sync_preview_confirm))
        }
    }
}

@Composable
private fun SyncPreviewMetricRow(
    label: String,
    value: String,
    accentColor: Color? = null,
    iconRes: Int? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            if (iconRes != null) {
                LoomaIcon(
                    iconRes = iconRes,
                    contentDescription = null,
                    tint = accentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = accentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value,
            style = LoomaTokens.type.count,
            color = accentColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun previewSampleItems(plan: SyncPlan): List<SyncPlanItem> {
    val conflicts = plan.items.filterIsInstance<SyncPlanItem.ResolveConflict>()
    val transfers = plan.items.filter {
        it is SyncPlanItem.UploadNew ||
            it is SyncPlanItem.UploadUpdate ||
            it is SyncPlanItem.DownloadNew ||
            it is SyncPlanItem.DownloadUpdate
    }
    return (conflicts + transfers).take(10)
}
