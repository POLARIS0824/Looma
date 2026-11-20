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
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import com.example.looma.ui.components.PageMaxWidth
import com.example.looma.ui.components.pageMargin
import com.example.looma.ui.theme.LoomaTokens
import com.example.looma.ui.theme.TABULAR_FIGURES

@Immutable
internal data class SemanticOperationUiState(
    val status: SemanticWorkState?,
    val action: SemanticSettingsAction,
)

@Immutable
internal data class SemanticSearchSettingsUiState(
    val supported: Boolean,
    val vaultName: String,
    val model: SemanticOperationUiState,
    val index: SemanticOperationUiState,
)

@Composable
internal fun SemanticSearchSettingsRoute(
    viewModel: SemanticSearchSettingsViewModel,
    vaultName: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            model = SemanticOperationUiState(status = modelStatus, action = modelAction),
            index = SemanticOperationUiState(status = indexStatus, action = indexAction),
        ),
        onStartModel = {
            viewModel.model.enqueue()
            requestNotifications()
        },
        onCancelModel = viewModel.model::cancel,
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
    onStartModel: () -> Unit,
    onCancelModel: () -> Unit,
    onStartIndex: () -> Unit,
    onCancelIndex: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LoomaTokens.colors.page,
        topBar = {
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
                            iconRes = R.drawable.ic_sparkle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(16.dp).size(32.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_semantic_intro_title),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.settings_semantic_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (state.supported) {
                    SemanticSearchOperations(
                        vaultName = state.vaultName,
                        model = state.model,
                        index = state.index,
                        onStartModel = onStartModel,
                        onCancelModel = onCancelModel,
                        onStartIndex = onStartIndex,
                        onCancelIndex = onCancelIndex,
                    )
                    Text(
                        text = stringResource(R.string.settings_semantic_background_work),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun SemanticSearchOperations(
    vaultName: String,
    model: SemanticOperationUiState,
    index: SemanticOperationUiState,
    onStartModel: () -> Unit,
    onCancelModel: () -> Unit,
    onStartIndex: () -> Unit,
    onCancelIndex: () -> Unit,
) {
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
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(
                    onClick = { modelDetailsExpanded = !modelDetailsExpanded },
                    shapes = ButtonDefaults.shapes(),
                ) {
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = LoomaTokens.colors.cardInset,
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        SemanticOperationStatus(
                            description = when (val state = model.status) {
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
                            operation = model,
                            startLabel = stringResource(R.string.settings_semantic_download),
                            canStart = model.status != SemanticWorkState.Ready,
                            filledAction = true,
                            onStart = onStartModel,
                            onCancel = onCancelModel,
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
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                SemanticOperationStatus(
                    description = when (val state = index.status) {
                        null -> stringResource(R.string.settings_semantic_index_checking)
                        SemanticWorkState.Unavailable -> stringResource(R.string.settings_semantic_index_unavailable)
                        SemanticWorkState.Idle -> stringResource(
                            if (model.status == SemanticWorkState.Ready) {
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
                    operation = index,
                    startLabel = stringResource(
                        if (index.status == SemanticWorkState.Ready) {
                            R.string.settings_semantic_update_index
                        } else {
                            R.string.settings_semantic_build_index
                        },
                    ),
                    canStart = model.status == SemanticWorkState.Ready,
                    filledAction = false,
                    onStart = onStartIndex,
                    onCancel = onCancelIndex,
                )
            }
        }
    }
}

@Composable
private fun SemanticOperationStatus(
    description: String,
    operation: SemanticOperationUiState,
    startLabel: String,
    canStart: Boolean,
    filledAction: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val status = operation.status
    val action = operation.action
    val active = status == SemanticWorkState.Queued || status is SemanticWorkState.Running
    val showStart = canStart && status != null && status != SemanticWorkState.Unavailable
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = description,
            style = if (description.any(Char::isDigit)) {
                MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR_FIGURES)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            status is SemanticWorkState.Running && status.total > 0 -> LinearWavyProgressIndicator(
                progress = { (status.completed.toFloat() / status.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            status is SemanticWorkState.Running -> LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            else -> Unit
        }
        if (active) {
            TextButton(
                onClick = onCancel,
                enabled = action != SemanticSettingsAction.Submitting,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.dialog_cancel)) }
        } else if (showStart) {
            val retry = status == SemanticWorkState.Cancelled || status == SemanticWorkState.Failed
            val label = if (retry) stringResource(R.string.settings_semantic_retry) else startLabel
            if (filledAction) {
                Button(
                    onClick = onStart,
                    enabled = action != SemanticSettingsAction.Submitting,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(label)
                }
            } else {
                FilledTonalButton(
                    onClick = onStart,
                    enabled = action != SemanticSettingsAction.Submitting,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(label)
                }
            }
        }
        if (action == SemanticSettingsAction.Failed) {
            Text(
                text = stringResource(R.string.settings_semantic_action_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
