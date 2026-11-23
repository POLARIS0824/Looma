package com.example.looma.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.settings.S3SyncConfig
import com.example.looma.data.sync.SyncActiveWork
import com.example.looma.data.sync.SyncStatus
import com.example.looma.domain.sync.RemotePrefix
import com.example.looma.domain.sync.SyncPhase
import com.example.looma.domain.sync.SyncRunOutcome
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.components.ErrorBanner
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.PageMaxWidth
import com.example.looma.ui.components.pageMargin
import com.example.looma.ui.format.formatDateTime
import com.example.looma.ui.theme.LoomaTokens
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LoomaTokens.colors.page,
        topBar = {
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LoomaTokens.colors.page),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pageMargin(), vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = PageMaxWidth).fillMaxWidth().padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(SettingsGroupGap),
            ) {
                SyncHeroHeader()
                SyncStatusSection(
                    vaultName = vaultName,
                    status = status,
                    testState = testState,
                    actionState = actionState,
                    onPreviewFirstSync = onPreviewFirstSync,
                    onStartSync = onStartSync,
                    onCancelSync = onCancelSync,
                    onTestConnection = onTestConnection,
                    onOpenConfig = onOpenConfig,
                )
                SyncStorageSection(
                    status = status,
                    onOpenConfig = onOpenConfig,
                )
                SyncGuideSection(status = status)
            }
        }
    }
}

@Composable
private fun SyncHeroHeader() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            LoomaIcon(
                iconRes = R.drawable.ic_cloud_sync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(16.dp).size(32.dp),
            )
        }
        Text(
            text = stringResource(R.string.settings_sync_intro_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.settings_sync_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SyncStatusSection(
    vaultName: String,
    status: SyncStatus?,
    testState: SyncTestState,
    actionState: SyncActionState,
    onPreviewFirstSync: () -> Unit,
    onStartSync: () -> Unit,
    onCancelSync: () -> Unit,
    onTestConnection: (S3SyncConfig) -> Unit,
    onOpenConfig: () -> Unit,
) {
    val activeWork = status?.activeWork
    val isConfigured = status != null && !status.credentialsUnavailable && status.config.isConfigured
    val submitting = actionState == SyncActionState.Submitting

    SettingsGroup(stringResource(R.string.settings_sync_status_section)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = LoomaTokens.colors.card,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (vaultName.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.settings_sync_vault, vaultName),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (isConfigured) {
                    val normalizedPrefix = RemotePrefix.normalizeOrNull(status.config.remotePrefix)?.value.orEmpty()
                    val prefixLabel = if (normalizedPrefix.isEmpty()) {
                        stringResource(R.string.settings_sync_prefix_root)
                    } else {
                        stringResource(R.string.settings_sync_prefix_value, normalizedPrefix)
                    }
                    Text(
                        text = stringResource(R.string.settings_sync_configured, status.config.bucket, prefixLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = LoomaTokens.colors.cardInset,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            activeWork != null -> SyncActiveWorkStatus(activeWork)
                            status == null -> DelayedLoadingIndicator(loading = true, size = 24.dp)
                            status.credentialsUnavailable -> ErrorBanner(
                                message = stringResource(R.string.settings_sync_credentials_unavailable),
                            )
                            !status.config.isConfigured -> Text(
                                text = stringResource(R.string.settings_sync_not_configured),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> SyncIdleStatus(status)
                        }
                    }
                }

                if (activeWork != null) {
                    TextButton(
                        onClick = onCancelSync,
                        enabled = !submitting,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                } else if (isConfigured) {
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
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onPrimaryClick,
                            enabled = !submitting && testState !is SyncTestState.Testing,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(primaryLabel)
                        }
                        FilledTonalButton(
                            onClick = { onTestConnection(status.config) },
                            enabled = !submitting && testState !is SyncTestState.Testing,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.settings_sync_test))
                        }
                    }
                    if (testState !is SyncTestState.Idle) {
                        SyncTestFeedback(testState = testState)
                    }
                } else if (status != null) {
                    Button(
                        onClick = onOpenConfig,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.settings_sync_configure))
                    }
                }

                if (actionState == SyncActionState.Failed) {
                    Text(
                        text = stringResource(R.string.settings_sync_action_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncActiveWorkStatus(work: SyncActiveWork) {
    val label = when (work) {
        SyncActiveWork.Queued -> stringResource(R.string.settings_sync_queued)
        is SyncActiveWork.Running -> when (work.phase) {
            SyncPhase.SCANNING_LOCAL -> stringResource(R.string.settings_sync_phase_local)
            SyncPhase.LISTING_REMOTE -> stringResource(R.string.settings_sync_phase_remote)
            SyncPhase.SYNCING -> if (work.total > 0) {
                stringResource(R.string.settings_sync_phase_syncing, work.completed, work.total)
            } else {
                stringResource(R.string.sync_work_title)
            }
            SyncPhase.UPDATING_INDEX -> stringResource(R.string.settings_sync_phase_indexing)
        }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (work is SyncActiveWork.Running && work.phase == SyncPhase.SYNCING && work.total > 0) {
        LinearWavyProgressIndicator(
            progress = { (work.completed.toFloat() / work.total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun SyncIdleStatus(status: SyncStatus) {
    val last = status.lastState
    val lastSyncAt = last?.lastSyncAtMillis
    if (last == null || lastSyncAt == null) {
        Text(
            text = stringResource(
                if (status.hasCompletedInitialSync) {
                    R.string.settings_sync_idle_ready
                } else {
                    R.string.settings_sync_idle_first
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        text = stringResource(
            R.string.settings_sync_last_synced,
            formatDateTime(Instant.ofEpochMilli(lastSyncAt)),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )

    when (last.lastOutcome) {
        SyncRunOutcome.CANCELLED.name -> {
            Text(
                text = stringResource(R.string.settings_sync_summary_cancelled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SyncRunOutcome.PARTIAL.name -> {
            Text(
                text = stringResource(R.string.settings_sync_summary_partial, last.lastFailed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (last.lastUploaded + last.lastDownloaded + last.lastConflicts > 0) {
                Text(
                    text = stringResource(
                        R.string.settings_sync_summary_counts,
                        last.lastUploaded,
                        last.lastDownloaded,
                        last.lastConflicts,
                    ),
                    style = LoomaTokens.type.count,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SyncRunOutcome.FAILED.name -> {
            ErrorBanner(
                message = last.lastErrorMessage?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.settings_sync_err_generic),
            )
        }
        else -> {
            if (last.lastUploaded == 0 && last.lastDownloaded == 0 && last.lastConflicts == 0) {
                Text(
                    text = stringResource(R.string.settings_sync_summary_up_to_date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.settings_sync_summary_counts,
                        last.lastUploaded,
                        last.lastDownloaded,
                        last.lastConflicts,
                    ),
                    style = LoomaTokens.type.count,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (last.lastConflicts > 0) {
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

    if (last.lastSkipped > 0) {
        Text(
            text = stringResource(R.string.settings_sync_skipped_notice, last.lastSkipped),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (!status.hasCompletedInitialSync) {
        Text(
            text = stringResource(R.string.settings_sync_idle_first),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    SettingsGroup(stringResource(R.string.settings_sync_storage_section)) {
        SettingsRow(
            index = 0,
            count = 1,
            title = stringResource(R.string.settings_sync_sheet_title),
            description = description,
            onClick = onOpenConfig,
        )
    }
}

@Composable
private fun SyncGuideSection(status: SyncStatus?) {
    var expandedOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val defaultExpanded = status?.let { it.credentialsUnavailable || !it.config.isConfigured } ?: false
    val expanded = expandedOverride ?: defaultExpanded

    SettingsGroup(stringResource(R.string.settings_sync_guide_section)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = LoomaTokens.colors.card,
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
