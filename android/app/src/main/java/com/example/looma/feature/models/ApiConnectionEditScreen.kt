package com.example.looma.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.assistant.ApiModel
import com.example.looma.data.assistant.ApiProvider
import com.example.looma.data.assistant.ConnectionStatus
import com.example.looma.ui.components.FeatureAction
import com.example.looma.ui.components.FeatureActionStyle
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.PageMaxWidth
import com.example.looma.ui.components.SectionHeader
import com.example.looma.ui.components.SectionHeaderDefaults
import com.example.looma.ui.components.pageMargin
import com.example.looma.ui.theme.LoomaTheme

@Composable
internal fun ApiConnectionEditRoute(
    viewModel: ApiConnectionEditViewModel,
    onDuplicated: (String) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val copyName = stringResource(R.string.models_edit_copy_name, viewModel.name.text.toString().trim())
    ApiConnectionEditScreen(
        state = state,
        name = viewModel.name,
        baseUrl = viewModel.baseUrl,
        apiKey = viewModel.apiKey,
        canSave = viewModel.canSave(),
        onSelectProvider = viewModel::selectProvider,
        onToggleModel = viewModel::setModelEnabled,
        onFetchModels = viewModel::fetchModels,
        onTest = viewModel::test,
        onSave = {
            viewModel.save()
            onNavigateUp()
        },
        onDuplicate = { onDuplicated(viewModel.duplicate(copyName)) },
        onDelete = {
            viewModel.delete()
            onNavigateUp()
        },
        onNavigateUp = onNavigateUp,
    )
}

@Composable
internal fun ApiConnectionEditScreen(
    state: ApiConnectionEditUiState,
    name: TextFieldState,
    baseUrl: TextFieldState,
    apiKey: TextFieldState,
    canSave: Boolean,
    onSelectProvider: (ApiProvider) -> Unit,
    onToggleModel: (String, Boolean) -> Unit,
    onFetchModels: () -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmDelete) {
        DeleteConnectionDialog(
            name = name.text.toString(),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    val checking = state.status == ConnectionStatus.Checking
    val scrollState = rememberScrollState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(scrollState)
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.saved) R.string.models_edit_title else R.string.models_api_add))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp, shapes = IconButtonDefaults.shapes()) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_arrow_back,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                actions = {
                    if (state.saved) {
                        OverflowMenu(onDuplicate = onDuplicate, onDelete = { confirmDelete = true })
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = PageMaxWidth)
                .fillMaxWidth()
                .padding(start = pageMargin(), end = pageMargin(), bottom = 24.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(top = 8.dp),
            ) {
                ModelTextField(state = name, label = stringResource(R.string.models_edit_name))
                SectionHeader(title = stringResource(R.string.models_edit_provider), first = false)
                ProviderChips(selected = state.provider, onSelect = onSelectProvider)
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelTextField(
                        state = baseUrl,
                        label = stringResource(R.string.models_edit_base_url),
                        placeholder = state.provider.defaultBaseUrl,
                    )
                    OutlinedSecureTextField(
                        state = apiKey,
                        label = { Text(stringResource(R.string.models_edit_api_key)) },
                        labelPosition = TextFieldLabelPosition.Inside(),
                        shape = OutlinedTextFieldDefaults.roundedShape,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ModelsHeader(enabled = !checking && canSave, onFetch = onFetchModels)
                ModelList(models = state.models, onToggle = onToggleModel)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(statusTextRes(state.status)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.status == ConnectionStatus.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.saved) {
                    FeatureAction(
                        text = stringResource(R.string.models_edit_test),
                        onClick = onTest,
                        style = FeatureActionStyle.Tonal,
                        enabled = !checking && canSave,
                    )
                } else {
                    FeatureAction(
                        text = stringResource(R.string.dialog_cancel),
                        onClick = onNavigateUp,
                        style = FeatureActionStyle.Tonal,
                    )
                }
                FeatureAction(
                    text = stringResource(R.string.dialog_save),
                    onClick = onSave,
                    enabled = canSave,
                )
            }
        }
    }
}

private fun statusTextRes(status: ConnectionStatus): Int = when (status) {
    ConnectionStatus.Unchecked -> R.string.models_edit_status_unchecked
    ConnectionStatus.Checking -> R.string.models_edit_status_checking
    ConnectionStatus.Ok -> R.string.models_edit_status_ok
    ConnectionStatus.Failed -> R.string.models_edit_status_failed
}

@Composable
private fun OverflowMenu(onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, shapes = IconButtonDefaults.shapes()) {
        LoomaIcon(
            iconRes = R.drawable.ic_more_vert,
            contentDescription = stringResource(R.string.models_edit_more),
        )
    }
    DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.models_edit_duplicate)) },
                shape = MenuDefaults.itemShape(0, 2).shape,
                onClick = {
                    expanded = false
                    onDuplicate()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.models_edit_delete)) },
                shape = MenuDefaults.itemShape(1, 2).shape,
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun ProviderChips(selected: ApiProvider, onSelect: (ApiProvider) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ApiProvider.entries.forEach { provider ->
            val isSelected = provider == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(provider) },
                label = { Text(stringResource(provider.labelRes())) },
                leadingIcon = if (isSelected) {
                    {
                        LoomaIcon(
                            iconRes = R.drawable.ic_check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ModelsHeader(enabled: Boolean, onFetch: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = SectionHeaderDefaults.GroupGap, bottom = SectionHeaderDefaults.ContentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader(
            title = stringResource(R.string.models_edit_models),
            modifier = Modifier.weight(1f),
            parentSpacing = SectionHeaderDefaults.ContentGap,
        )
        val height = ButtonDefaults.ExtraSmallContainerHeight
        FilledTonalButton(
            onClick = onFetch,
            enabled = enabled,
            modifier = Modifier.heightIn(min = height),
            shapes = ButtonDefaults.shapesFor(height),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
        ) {
            LoomaIcon(
                iconRes = R.drawable.ic_retry,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
            )
            Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(height)))
            Text(stringResource(R.string.models_edit_fetch), style = ButtonDefaults.textStyleFor(height))
        }
    }
}

@Composable
private fun ModelList(models: List<ApiModel>, onToggle: (String, Boolean) -> Unit) {
    if (models.isEmpty()) {
        Text(
            text = stringResource(R.string.models_edit_models_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        models.forEachIndexed { index, model ->
            SegmentedListItem(
                onClick = { onToggle(model.id, !model.enabled) },
                shapes = ListItemDefaults.segmentedShapes(index, models.size),
                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        role = Role.Switch
                        toggleableState = ToggleableState(model.enabled)
                    },
                trailingContent = { Switch(checked = model.enabled, onCheckedChange = null) },
            ) {
                Text(model.id)
            }
        }
    }
}

@Composable
private fun DeleteConnectionDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.models_edit_delete_title)) },
        text = { Text(stringResource(R.string.models_edit_delete_message, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm, shapes = ButtonDefaults.shapes()) {
                Text(
                    text = stringResource(R.string.models_edit_delete),
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
private fun PreviewScreen(saved: Boolean) {
    val connection = ModelPreviewData.connections.first()
    LoomaTheme(dynamicColor = false) {
        ApiConnectionEditScreen(
            state = ApiConnectionEditUiState(
                saved = saved,
                provider = connection.provider,
                models = if (saved) connection.models else emptyList(),
                status = if (saved) connection.status else ConnectionStatus.Unchecked,
            ),
            name = remember { TextFieldState(if (saved) connection.name else "") },
            baseUrl = remember { TextFieldState(if (saved) connection.baseUrl else "") },
            apiKey = remember { TextFieldState(if (saved) connection.apiKey else "") },
            canSave = saved,
            onSelectProvider = {},
            onToggleModel = { _, _ -> },
            onFetchModels = {},
            onTest = {},
            onSave = {},
            onDuplicate = {},
            onDelete = {},
            onNavigateUp = {},
        )
    }
}

@Preview(name = "编辑连接", showBackground = true)
@Composable
private fun ApiConnectionEditPreview() = PreviewScreen(saved = true)

@Preview(name = "添加连接", showBackground = true)
@Composable
private fun ApiConnectionAddPreview() = PreviewScreen(saved = false)
