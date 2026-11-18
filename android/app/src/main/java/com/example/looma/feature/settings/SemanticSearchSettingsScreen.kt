@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.looma.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.search.SemanticWorkState
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun SemanticSearchSettingsRoute(
    viewModel: SemanticSearchSettingsViewModel,
    vaultName: String,
    onNavigateUp: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LoomaTokens.colors.page,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_semantic_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsGroup(stringResource(R.string.settings_semantic_section)) {
                    if (viewModel.supported) {
                        SemanticSearchOperations(viewModel, vaultName)
                        Text(
                            text = stringResource(R.string.settings_semantic_on_device),
                            style = LoomaTokens.type.supporting,
                            color = LoomaTokens.colors.muted,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    } else {
                        SettingsRow(
                            index = 0,
                            count = 1,
                            title = stringResource(R.string.settings_semantic_title),
                            description = stringResource(R.string.settings_semantic_unsupported),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SemanticSearchOperations(viewModel: SemanticSearchSettingsViewModel, vaultName: String) {
    val modelStatus by viewModel.model.status.collectAsStateWithLifecycle()
    val indexStatus by viewModel.index.status.collectAsStateWithLifecycle()
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

    SemanticOperationRow(
        index = 0,
        count = 2,
        title = stringResource(R.string.settings_semantic_model),
        description = when (val state = modelStatus) {
            null -> stringResource(R.string.settings_semantic_checking)
            SemanticWorkState.Unavailable -> stringResource(R.string.settings_semantic_unsupported)
            SemanticWorkState.Idle -> stringResource(R.string.settings_semantic_model_size)
            SemanticWorkState.Queued -> stringResource(R.string.settings_semantic_download_queued)
            is SemanticWorkState.Running -> stringResource(R.string.settings_semantic_downloading, state.completed)
            SemanticWorkState.Ready -> stringResource(R.string.settings_semantic_model_ready)
            SemanticWorkState.Cancelled -> stringResource(R.string.settings_semantic_download_cancelled)
            SemanticWorkState.Failed -> stringResource(R.string.settings_semantic_download_failed)
        },
        operation = viewModel.model,
        status = modelStatus,
        startLabel = stringResource(R.string.settings_semantic_download),
        canStart = modelStatus != SemanticWorkState.Ready,
        requestNotifications = requestNotifications,
    )
    SemanticOperationRow(
        index = 1,
        count = 2,
        title = stringResource(R.string.settings_semantic_index),
        description = when (val state = indexStatus) {
            null -> stringResource(R.string.settings_semantic_index_checking)
            SemanticWorkState.Unavailable -> stringResource(R.string.settings_semantic_index_unavailable)
            SemanticWorkState.Idle -> stringResource(
                if (modelStatus == SemanticWorkState.Ready) {
                    R.string.settings_semantic_index_idle
                } else {
                    R.string.settings_semantic_index_requires_model
                },
            )
            SemanticWorkState.Queued -> stringResource(R.string.settings_semantic_index_queued)
            is SemanticWorkState.Running -> if (state.total > 0) {
                stringResource(R.string.settings_semantic_indexing, state.completed, state.total)
            } else {
                stringResource(R.string.settings_semantic_index_preparing)
            }
            SemanticWorkState.Ready -> stringResource(R.string.settings_semantic_index_ready)
            SemanticWorkState.Cancelled -> stringResource(R.string.settings_semantic_index_cancelled)
            SemanticWorkState.Failed -> stringResource(R.string.settings_semantic_index_failed)
        },
        hint = if (vaultName.isBlank()) {
            null
        } else {
            stringResource(R.string.settings_semantic_vault, vaultName)
        },
        operation = viewModel.index,
        status = indexStatus,
        startLabel = stringResource(
            if (indexStatus == SemanticWorkState.Ready) {
                R.string.settings_semantic_update_index
            } else {
                R.string.settings_semantic_build_index
            },
        ),
        canStart = modelStatus == SemanticWorkState.Ready,
        requestNotifications = requestNotifications,
    )
}

@Composable
private fun SemanticOperationRow(
    index: Int,
    count: Int,
    title: String,
    description: String,
    hint: String? = null,
    operation: SemanticSettingsOperation,
    status: SemanticWorkState?,
    startLabel: String,
    canStart: Boolean,
    requestNotifications: () -> Unit,
) {
    val action by operation.action.collectAsStateWithLifecycle()
    val active = status == SemanticWorkState.Queued || status is SemanticWorkState.Running
    val showStart = canStart && status != null && status != SemanticWorkState.Unavailable
    SettingsRow(
        index = index,
        count = count,
        title = title,
        description = description,
        hint = hint,
        trailing = when {
            active -> {
                {
                    TextButton(
                        onClick = operation::cancel,
                        enabled = action != SemanticSettingsAction.Submitting,
                    ) { Text(stringResource(R.string.dialog_cancel)) }
                }
            }
            showStart -> {
                {
                    TextButton(
                        onClick = {
                            operation.enqueue()
                            requestNotifications()
                        },
                        enabled = action != SemanticSettingsAction.Submitting,
                    ) {
                        val retry = status == SemanticWorkState.Cancelled || status == SemanticWorkState.Failed
                        Text(if (retry) stringResource(R.string.settings_semantic_retry) else startLabel)
                    }
                }
            }
            else -> null
        },
    )
    if (action == SemanticSettingsAction.Failed) {
        Text(
            text = stringResource(R.string.settings_semantic_action_failed),
            style = LoomaTokens.type.supporting,
            color = LoomaTokens.colors.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
