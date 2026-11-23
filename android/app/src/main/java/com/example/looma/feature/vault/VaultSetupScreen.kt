package com.example.looma.feature.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.ui.components.ErrorBanner
import com.example.looma.ui.components.FeatureAction
import com.example.looma.ui.components.FeatureActionStyle
import com.example.looma.ui.components.FeaturePage
import com.example.looma.ui.components.FeatureStatus
import com.example.looma.ui.components.FeatureStatusCard
import com.example.looma.ui.theme.LoomaTheme

@Composable
fun VaultSetupScreen(
    onChooseFolderClick: () -> Unit,
    onConfirmClick: () -> Unit = {},
    selectedFolderName: String? = null,
    currentFolderName: String? = null,
    isInitializing: Boolean = false,
    isBusy: Boolean = false,
    error: VaultSetupError? = null,
    modifier: Modifier = Modifier,
) {
    val content = when {
        isInitializing -> VaultContent.Loading
        selectedFolderName != null -> VaultContent.Pending(selectedFolderName, currentFolderName)
        currentFolderName != null -> VaultContent.Current(currentFolderName)
        else -> VaultContent.Empty
    }
    val motion = MaterialTheme.motionScheme

    FeaturePage(
        iconRes = R.drawable.ic_folder,
        title = stringResource(R.string.vault_setup_title),
        subtitle = stringResource(R.string.vault_setup_subtitle),
        modifier = modifier,
        actions = {
            when (content) {
                VaultContent.Loading -> Unit
                VaultContent.Empty -> FeatureAction(
                    text = stringResource(R.string.vault_setup_choose),
                    onClick = onChooseFolderClick,
                    enabled = !isBusy,
                )
                is VaultContent.Pending, is VaultContent.Current -> {
                    FeatureAction(
                        text = stringResource(
                            if (content is VaultContent.Pending) R.string.vault_setup_reselect else R.string.vault_setup_change,
                        ),
                        onClick = onChooseFolderClick,
                        style = FeatureActionStyle.Tonal,
                        enabled = !isBusy,
                    )
                    FeatureAction(
                        text = stringResource(if (isBusy) R.string.vault_setup_connecting else R.string.vault_setup_confirm),
                        onClick = onConfirmClick,
                        enabled = !isBusy,
                    )
                }
            }
        },
    ) {
        FeatureStatusCard(
            status = vaultStatus(content),
            loading = content == VaultContent.Loading,
        )
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
        ) {
            ErrorBanner(
                message = vaultErrorText(error),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
fun vaultErrorText(error: VaultSetupError?): String = stringResource(
    when (error) {
        VaultSetupError.INVALID_FOLDER -> R.string.vault_setup_error_uri
        VaultSetupError.STORAGE_SETTINGS -> R.string.vault_setup_error_settings
        VaultSetupError.PERMISSION_ALL_FILES -> R.string.vault_permission_all_files
        VaultSetupError.PERMISSION_STORAGE -> R.string.vault_permission_storage
        VaultSetupError.UNAVAILABLE -> R.string.settings_vault_unavailable
        VaultSetupError.GENERIC, null -> R.string.vault_setup_error_generic
    },
)

private sealed interface VaultContent {
    data object Loading : VaultContent
    data object Empty : VaultContent
    data class Pending(val name: String, val previous: String?) : VaultContent
    data class Current(val name: String) : VaultContent
}

@Composable
private fun vaultStatus(content: VaultContent): FeatureStatus? = when (content) {
    VaultContent.Loading, VaultContent.Empty -> null
    is VaultContent.Pending -> FeatureStatus(
        label = stringResource(R.string.vault_setup_pending_label),
        title = folderTitle(content.name),
        detail = stringResource(R.string.vault_setup_pending_hint),
        note = content.previous?.let { stringResource(R.string.vault_setup_previous, it) },
    )
    is VaultContent.Current -> FeatureStatus(
        label = stringResource(R.string.vault_setup_current_label),
        title = folderTitle(content.name),
        detail = stringResource(R.string.vault_setup_connected),
    )
}

@Composable
private fun folderTitle(name: String): String = name.ifBlank { stringResource(R.string.vault_setup_unnamed_folder) }

@Preview(name = "未连接", showBackground = true)
@Composable
private fun EmptyPreview() {
    LoomaTheme(dynamicColor = false) { VaultSetupScreen(onChooseFolderClick = {}) }
}

@Preview(name = "待连接", showBackground = true)
@Composable
private fun PendingPreview() {
    LoomaTheme(dynamicColor = false) {
        VaultSetupScreen(
            onChooseFolderClick = {},
            selectedFolderName = "My Markdown Vault",
        )
    }
}

@Preview(name = "已连接", showBackground = true)
@Composable
private fun CurrentPreview() {
    LoomaTheme(dynamicColor = false) {
        VaultSetupScreen(
            onChooseFolderClick = {},
            currentFolderName = "My Markdown Vault",
        )
    }
}
