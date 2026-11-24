package com.example.looma.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.settings.S3SyncConfig
import com.example.looma.data.sync.SyncActiveWork
import com.example.looma.data.sync.SyncStatus
import com.example.looma.data.sync.SyncVaultStateEntity
import com.example.looma.domain.sync.RemotePrefix
import com.example.looma.domain.sync.SyncPhase
import com.example.looma.domain.sync.SyncRunOutcome
import com.example.looma.ui.components.FeatureAction
import com.example.looma.ui.components.FeatureActionStyle
import com.example.looma.ui.components.FeaturePage
import com.example.looma.ui.components.FeatureProgress
import com.example.looma.ui.components.FeatureStatus
import com.example.looma.ui.components.FeatureStatusCard
import com.example.looma.ui.components.FeatureStatusTone
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.SectionHeader
import com.example.looma.ui.format.formatDateTime
import com.example.looma.ui.theme.LoomaTheme
import java.time.Instant

@Composable
internal fun SyncSettingsRoute(
    viewModel: SyncSettingsViewModel,
    vaultName: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showConfigSheet by rememberSaveable { mutableStateOf(false) }

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

    val startSyncWithPermission = {
        viewModel.startSync()
        requestNotifications()
    }

    SyncSettingsScreen(
        vaultName = uiState.vaultName.ifBlank { vaultName },
        status = uiState.status,
        testState = if (showConfigSheet) SyncTestState.Idle else uiState.testState,
        actionState = uiState.actionState,
        onPreviewFirstSync = viewModel::loadFirstSyncPreview,
        onStartSync = startSyncWithPermission,
        onCancelSync = viewModel::cancelSync,
        onTestConnection = viewModel::testConnection,
        onOpenConfig = {
            viewModel.resetTestState()
            showConfigSheet = true
        },
        onNavigateUp = onNavigateUp,
        modifier = modifier,
    )

    if (showConfigSheet) {
        SyncConfigSheet(
            config = uiState.status?.config ?: S3SyncConfig(),
            credentialsUnavailable = uiState.status?.credentialsUnavailable == true,
            testState = uiState.testState,
            onTestConnection = viewModel::testConnection,
            onResetTestState = viewModel::resetTestState,
            onSave = { config ->
                viewModel.saveConfig(config)
                showConfigSheet = false
            },
            onDismiss = {
                viewModel.resetTestState()
                showConfigSheet = false
            },
        )
    }

    if (uiState.previewState !is SyncPreviewState.Closed) {
        SyncPreviewSheet(
            previewState = uiState.previewState,
            actionState = uiState.actionState,
            onRetry = viewModel::loadFirstSyncPreview,
            onConfirmSync = startSyncWithPermission,
            onDismiss = viewModel::dismissPreview,
        )
    }
}

@Composable
internal fun SyncSettingsScreen(
    vaultName: String,
    status: SyncStatus?,
    testState: SyncTestState,
    actionState: SyncActionState,
    onPreviewFirstSync: () -> Unit,
    onStartSync: () -> Unit,
    onCancelSync: () -> Unit,
    onTestConnection: (S3SyncConfig) -> Unit,
    onOpenConfig: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConfigured = status != null && !status.credentialsUnavailable && status.config.isConfigured

    FeaturePage(
        iconRes = R.drawable.ic_cloud_sync,
        title = stringResource(R.string.settings_sync_intro_title),
        subtitle = stringResource(R.string.settings_sync_intro),
        modifier = modifier,
        topBar = { scrollBehavior ->
            TopAppBar(
                title = { Text(stringResource(R.string.settings_sync_title)) },
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
            SyncActions(
                status = status,
                testState = testState,
                actionState = actionState,
                onPreviewFirstSync = onPreviewFirstSync,
                onStartSync = onStartSync,
                onCancelSync = onCancelSync,
                onTestConnection = onTestConnection,
                onOpenConfig = onOpenConfig,
            )
        },
    ) {
        FeatureStatusCard(
            status = syncStatus(vaultName = vaultName, status = status),
            loading = status == null,
        )
        if (testState !is SyncTestState.Idle) {
            Box(Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp)) {
                SyncTestFeedback(testState = testState)
            }
        }
        if (actionState == SyncActionState.Failed) {
            Text(
                text = stringResource(R.string.settings_sync_action_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp),
            )
        }
        val lastState = status?.lastState
        if (status != null && status.activeWork == null && isConfigured && lastState?.lastSyncAtMillis != null) {
            SyncIdleNotices(status = status, last = lastState)
        }
        Spacer(Modifier.height(32.dp))
        SyncStorageSection(status = status, onOpenConfig = onOpenConfig)
        Spacer(Modifier.height(24.dp))
        SyncGuideSection(status = status)
    }
}

@Composable
private fun FlowRowScope.SyncActions(
    status: SyncStatus?,
    testState: SyncTestState,
    actionState: SyncActionState,
    onPreviewFirstSync: () -> Unit,
    onStartSync: () -> Unit,
    onCancelSync: () -> Unit,
    onTestConnection: (S3SyncConfig) -> Unit,
    onOpenConfig: () -> Unit,
) {
    if (status == null) return
    val submitting = actionState == SyncActionState.Submitting
    val isConfigured = !status.credentialsUnavailable && status.config.isConfigured
    when {
        status.activeWork != null -> FeatureAction(
            text = stringResource(R.string.dialog_cancel),
            onClick = onCancelSync,
            style = FeatureActionStyle.Tonal,
            enabled = !submitting,
        )
        !isConfigured -> FeatureAction(
            text = stringResource(R.string.settings_sync_configure),
            onClick = onOpenConfig,
        )
        else -> {
            val lastOutcome = status.lastState?.lastOutcome
            val isRetry = lastOutcome == SyncRunOutcome.FAILED.name ||
                lastOutcome == SyncRunOutcome.PARTIAL.name
            val primaryLabel = when {
                !status.hasCompletedInitialSync -> stringResource(R.string.settings_sync_preview_first)
                isRetry -> stringResource(R.string.settings_sync_retry)
                else -> stringResource(R.string.settings_sync_now)
            }
            val onPrimaryClick = if (!status.hasCompletedInitialSync) {
                onPreviewFirstSync
            } else {
                onStartSync
            }
            FeatureAction(
                text = stringResource(R.string.settings_sync_test),
                onClick = { onTestConnection(status.config) },
                style = FeatureActionStyle.Tonal,
                enabled = !submitting && testState !is SyncTestState.Testing,
            )
            FeatureAction(
                text = primaryLabel,
                onClick = onPrimaryClick,
                enabled = !submitting && testState !is SyncTestState.Testing,
            )
        }
    }
}

@Composable
private fun syncStatus(vaultName: String, status: SyncStatus?): FeatureStatus? {
    if (status == null) return null
    val title = vaultName.ifBlank { stringResource(R.string.settings_sync_title) }
    val activeWork = status.activeWork
    if (activeWork != null) {
        val detail = when (activeWork) {
            SyncActiveWork.Queued -> stringResource(R.string.settings_sync_queued)
            is SyncActiveWork.Running -> when (activeWork.phase) {
                SyncPhase.SCANNING_LOCAL -> stringResource(R.string.settings_sync_phase_local)
                SyncPhase.LISTING_REMOTE -> stringResource(R.string.settings_sync_phase_remote)
                SyncPhase.SYNCING -> if (activeWork.total > 0) {
                    stringResource(R.string.settings_sync_phase_syncing, activeWork.completed, activeWork.total)
                } else {
                    stringResource(R.string.sync_work_title)
                }
                SyncPhase.UPDATING_INDEX -> stringResource(R.string.settings_sync_phase_indexing)
            }
        }
        val progress = if (activeWork is SyncActiveWork.Running &&
            activeWork.phase == SyncPhase.SYNCING &&
            activeWork.total > 0
        ) {
            FeatureProgress.Determinate(activeWork.completed.toFloat() / activeWork.total)
        } else {
            FeatureProgress.Indeterminate
        }
        val note = if (!status.credentialsUnavailable && status.config.isConfigured) {
            targetSummary(status.config)
        } else {
            null
        }
        return FeatureStatus(
            label = stringResource(R.string.settings_sync_status_syncing),
            title = title,
            detail = detail,
            progress = progress,
            note = note,
        )
    }
    if (status.credentialsUnavailable) {
        return FeatureStatus(
            label = stringResource(R.string.settings_sync_status_unavailable),
            title = title,
            detail = stringResource(R.string.settings_sync_sheet_title),
            tone = FeatureStatusTone.Error,
            note = stringResource(R.string.settings_sync_credentials_unavailable),
        )
    }
    if (!status.config.isConfigured) {
        return FeatureStatus(
            label = stringResource(R.string.settings_sync_not_configured),
            title = title,
            detail = stringResource(R.string.settings_sync_sheet_title),
        )
    }
    val last = status.lastState
    val lastSyncAt = last?.lastSyncAtMillis
    if (last == null || lastSyncAt == null) {
        return FeatureStatus(
            label = stringResource(R.string.settings_sync_status_ready),
            title = title,
            detail = targetSummary(status.config),
            note = stringResource(
                if (status.hasCompletedInitialSync) {
                    R.string.settings_sync_idle_ready
                } else {
                    R.string.settings_sync_idle_first
                },
            ),
        )
    }
    val lastSyncedText = stringResource(
        R.string.settings_sync_last_synced,
        formatDateTime(Instant.ofEpochMilli(lastSyncAt)),
    )
    return when (last.lastOutcome) {
        SyncRunOutcome.FAILED.name -> FeatureStatus(
            label = stringResource(R.string.settings_sync_status_failed),
            title = title,
            detail = lastSyncedText,
            tone = FeatureStatusTone.Error,
            note = last.lastErrorMessage?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.settings_sync_err_generic),
        )
        SyncRunOutcome.PARTIAL.name -> FeatureStatus(
            label = stringResource(R.string.settings_sync_status_partial),
            title = title,
            detail = stringResource(R.string.settings_sync_summary_partial, last.lastFailed),
            tone = FeatureStatusTone.Error,
            note = if (last.lastUploaded + last.lastDownloaded + last.lastConflicts > 0) {
                stringResource(
                    R.string.settings_sync_summary_counts,
                    last.lastUploaded,
                    last.lastDownloaded,
                    last.lastConflicts,
                )
            } else {
                lastSyncedText
            },
        )
        SyncRunOutcome.CANCELLED.name -> FeatureStatus(
            label = stringResource(R.string.settings_sync_status_cancelled),
            title = title,
            detail = stringResource(R.string.settings_sync_summary_cancelled),
            note = lastSyncedText,
        )
        else -> FeatureStatus(
            label = stringResource(R.string.settings_sync_status_ready),
            title = title,
            detail = lastSyncedText,
            note = if (last.lastUploaded == 0 && last.lastDownloaded == 0 && last.lastConflicts == 0) {
                stringResource(R.string.settings_sync_summary_up_to_date)
            } else {
                stringResource(
                    R.string.settings_sync_summary_counts,
                    last.lastUploaded,
                    last.lastDownloaded,
                    last.lastConflicts,
                )
            },
        )
    }
}

@Composable
private fun targetSummary(config: S3SyncConfig): String {
    val normalizedPrefix = RemotePrefix.normalizeOrNull(config.remotePrefix)?.value.orEmpty()
    val prefixLabel = if (normalizedPrefix.isEmpty()) {
        stringResource(R.string.settings_sync_prefix_root)
    } else {
        stringResource(R.string.settings_sync_prefix_value, normalizedPrefix)
    }
    return stringResource(R.string.settings_sync_configured, config.bucket, prefixLabel)
}

@Composable
private fun SyncIdleNotices(status: SyncStatus, last: SyncVaultStateEntity) {
    val hasConflicts = last.lastConflicts > 0
    val hasSkipped = last.lastSkipped > 0
    val needsInitial = !status.hasCompletedInitialSync
    if (!hasConflicts && !hasSkipped && !needsInitial) return

    Column(
        modifier = Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasConflicts) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoomaIcon(
                    iconRes = R.drawable.ic_warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.settings_sync_conflict_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hasSkipped) {
            Text(
                text = stringResource(R.string.settings_sync_skipped_notice, last.lastSkipped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (needsInitial) {
            Text(
                text = stringResource(R.string.settings_sync_idle_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncStorageSection(
    status: SyncStatus?,
    onOpenConfig: () -> Unit,
) {
    val description = when {
        status == null -> null
        status.credentialsUnavailable -> stringResource(R.string.settings_sync_credentials_unavailable)
        status.config.isConfigured -> stringResource(
            R.string.settings_sync_configured,
            status.config.bucket,
            status.config.endpoint,
        )
        else -> stringResource(R.string.settings_sync_not_configured)
    }
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.settings_sync_storage_section))
        SegmentedListItem(
            onClick = onOpenConfig,
            shapes = ListItemDefaults.segmentedShapes(0, 1),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
            leadingContent = { LoomaIcon(iconRes = R.drawable.ic_cloud_sync, contentDescription = null) },
            supportingContent = description?.let { { Text(it) } },
        ) {
            Text(stringResource(R.string.settings_sync_sheet_title))
        }
    }
}

@Composable
private fun SyncGuideSection(status: SyncStatus?) {
    var expandedOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val defaultExpanded = status?.let { it.credentialsUnavailable || !it.config.isConfigured } ?: false
    val expanded = expandedOverride ?: defaultExpanded

    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.settings_sync_guide_section))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_sync_guide_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(
                    onClick = { expandedOverride = !expanded },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        stringResource(
                            if (expanded) R.string.settings_sync_guide_toggle_hide
                            else R.string.settings_sync_guide_toggle_show,
                        ),
                    )
                }
                if (expanded) {
                    Text(
                        text = stringResource(R.string.settings_sync_guide_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncPreview(status: SyncStatus?) {
    LoomaTheme(dynamicColor = false) {
        SyncSettingsScreen(
            vaultName = "My Notes",
            status = status,
            testState = SyncTestState.Idle,
            actionState = SyncActionState.Idle,
            onPreviewFirstSync = {},
            onStartSync = {},
            onCancelSync = {},
            onTestConnection = {},
            onOpenConfig = {},
            onNavigateUp = {},
        )
    }
}

@Preview(name = "未配置", showBackground = true)
@Composable
private fun UnconfiguredPreview() = SyncPreview(
    SyncStatus(
        vault = null,
        config = S3SyncConfig(),
        credentialsUnavailable = false,
        targetKey = null,
        hasCompletedInitialSync = false,
        activeWork = null,
        lastState = null,
    ),
)

@Preview(name = "同步中", showBackground = true)
@Composable
private fun SyncingPreview() = SyncPreview(
    SyncStatus(
        vault = null,
        config = S3SyncConfig(
            endpoint = "https://example.r2.cloudflarestorage.com",
            region = "auto",
            bucket = "notes",
            accessKey = "key",
            secretKey = "secret",
        ),
        credentialsUnavailable = false,
        targetKey = "key",
        hasCompletedInitialSync = true,
        activeWork = SyncActiveWork.Running(phase = SyncPhase.SYNCING, completed = 5, total = 12),
        lastState = null,
    ),
)

@Preview(name = "已就绪", showBackground = true)
@Composable
private fun ReadyPreview() = SyncPreview(
    SyncStatus(
        vault = null,
        config = S3SyncConfig(
            endpoint = "https://example.r2.cloudflarestorage.com",
            region = "auto",
            bucket = "notes",
            accessKey = "key",
            secretKey = "secret",
        ),
        credentialsUnavailable = false,
        targetKey = "key",
        hasCompletedInitialSync = true,
        activeWork = null,
        lastState = SyncVaultStateEntity(
            vaultId = "v1",
            targetKey = "key",
            hasCompletedInitialSync = true,
            lastSyncAtMillis = 1_700_000_000_000L,
            lastOutcome = SyncRunOutcome.SUCCEEDED.name,
            lastUploaded = 2,
            lastDownloaded = 1,
            lastDeletedLocal = 0,
            lastDeletedRemote = 0,
            lastConflicts = 0,
            lastFailed = 0,
            lastSkipped = 0,
            lastErrorMessage = null,
        ),
    ),
)
