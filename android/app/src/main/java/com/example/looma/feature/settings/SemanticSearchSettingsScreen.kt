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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.looma.data.search.SemanticWorkState
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun SemanticSearchSettingsRoute(
    viewModel: SemanticSearchSettingsViewModel,
    vaultName: String,
    onNavigateUp: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LoomaTokens.colors.page,
        topBar = {
            TopAppBar(
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
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = LoomaTokens.colors.accentContainer,
                    ) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_sparkle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(16.dp).size(32.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_semantic_intro_title),
                        style = LoomaTokens.type.pageTitle,
                        color = LoomaTokens.colors.title,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.settings_semantic_intro),
                        style = LoomaTokens.type.supporting,
                        color = LoomaTokens.colors.muted,
                        textAlign = TextAlign.Center,
                    )
                }
                if (viewModel.supported) {
                    SemanticSearchOperations(viewModel, vaultName)
                    Text(
                        text = stringResource(R.string.settings_semantic_background_work),
                        style = LoomaTokens.type.supporting,
                        color = LoomaTokens.colors.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                } else {
                    SettingsRow(
                        index = 0,
                        count = 1,
                        title = stringResource(R.string.settings_semantic_unsupported),
                    )
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
    var modelDetailsExpanded by rememberSaveable { mutableStateOf(false) }

    SettingsGroup(stringResource(R.string.settings_semantic_model_section)) {
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
                    text = stringResource(R.string.settings_semantic_model),
                    style = LoomaTokens.type.cardTitle,
                    color = LoomaTokens.colors.title,
                )
                TextButton(onClick = { modelDetailsExpanded = !modelDetailsExpanded }) {
                    Text(
                        stringResource(
                            if (modelDetailsExpanded) R.string.settings_semantic_hide_details
                            else R.string.settings_semantic_show_details,
                        ),
                    )
                }
                if (modelDetailsExpanded) {
                    Text(
                        text = stringResource(R.string.settings_semantic_model_details),
                        style = LoomaTokens.type.supporting,
                        color = LoomaTokens.colors.body,
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = LoomaTokens.colors.cardInset,
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        SemanticOperationStatus(
                            description = when (val state = modelStatus) {
                                null -> stringResource(R.string.settings_semantic_checking)
                                SemanticWorkState.Unavailable -> stringResource(R.string.settings_semantic_unsupported)
                                SemanticWorkState.Idle -> stringResource(R.string.settings_semantic_model_size)
                                SemanticWorkState.Queued -> stringResource(R.string.settings_semantic_download_queued)
                                is SemanticWorkState.Running -> stringResource(
                                    R.string.settings_semantic_downloading,
                                    state.completed,
                                )
                                SemanticWorkState.Ready -> stringResource(R.string.settings_semantic_model_ready)
                                SemanticWorkState.Cancelled -> stringResource(R.string.settings_semantic_download_cancelled)
                                SemanticWorkState.Failed -> stringResource(R.string.settings_semantic_download_failed)
                            },
                            operation = viewModel.model,
                            status = modelStatus,
                            startLabel = stringResource(R.string.settings_semantic_download),
                            canStart = modelStatus != SemanticWorkState.Ready,
                            filledAction = true,
                            requestNotifications = requestNotifications,
                        )
                    }
                }
            }
        }
    }
    SettingsGroup(stringResource(R.string.settings_semantic_index)) {
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
                        text = stringResource(R.string.settings_semantic_vault, vaultName),
                        style = LoomaTokens.type.cardTitle,
                        color = LoomaTokens.colors.title,
                    )
                }
                SemanticOperationStatus(
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
                    filledAction = false,
                    requestNotifications = requestNotifications,
                )
            }
        }
    }
}

@Composable
private fun SemanticOperationStatus(
    description: String,
    operation: SemanticSettingsOperation,
    status: SemanticWorkState?,
    startLabel: String,
    canStart: Boolean,
    filledAction: Boolean,
    requestNotifications: () -> Unit,
) {
    val action by operation.action.collectAsStateWithLifecycle()
    val active = status == SemanticWorkState.Queued || status is SemanticWorkState.Running
    val showStart = canStart && status != null && status != SemanticWorkState.Unavailable
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = description,
            style = LoomaTokens.type.supporting,
            color = LoomaTokens.colors.body,
        )
        when {
            status is SemanticWorkState.Running && status.total > 0 -> LinearProgressIndicator(
                progress = { (status.completed.toFloat() / status.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            status is SemanticWorkState.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> Unit
        }
        if (active) {
            TextButton(
                onClick = operation::cancel,
                enabled = action != SemanticSettingsAction.Submitting,
            ) { Text(stringResource(R.string.dialog_cancel)) }
        } else if (showStart) {
            val retry = status == SemanticWorkState.Cancelled || status == SemanticWorkState.Failed
            val label = if (retry) stringResource(R.string.settings_semantic_retry) else startLabel
            val onStart = {
                operation.enqueue()
                requestNotifications()
            }
            if (filledAction) {
                Button(onClick = onStart, enabled = action != SemanticSettingsAction.Submitting) {
                    Text(label)
                }
            } else {
                FilledTonalButton(onClick = onStart, enabled = action != SemanticSettingsAction.Submitting) {
                    Text(label)
                }
            }
        }
        if (action == SemanticSettingsAction.Failed) {
            Text(
                text = stringResource(R.string.settings_semantic_action_failed),
                style = LoomaTokens.type.supporting,
                color = LoomaTokens.colors.error,
            )
        }
    }
}
