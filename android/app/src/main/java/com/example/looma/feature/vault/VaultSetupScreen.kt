@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.example.looma.feature.vault

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTheme
import com.example.looma.ui.theme.LoomaTokens

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

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                VaultSetupHeader()
                Spacer(Modifier.height(32.dp))

                AnimatedContent(
                    targetState = content,
                    transitionSpec = {
                        (fadeIn(motion.defaultEffectsSpec()) +
                            scaleIn(initialScale = 0.92f, animationSpec = motion.defaultSpatialSpec())) togetherWith
                            fadeOut(motion.fastEffectsSpec())
                    },
                    label = "vaultSetupContent",
                ) { target ->
                    when (target) {
                        VaultContent.Loading -> VaultSetupLoading()
                        VaultContent.Empty -> Unit
                        is VaultContent.Pending -> VaultFolderCard(
                            folderName = target.name,
                            pending = true,
                            previousFolderName = target.previous,
                        )
                        is VaultContent.Current -> VaultFolderCard(
                            folderName = target.name,
                            pending = false,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = error != null,
                    enter = fadeIn(motion.defaultEffectsSpec()),
                    exit = fadeOut(motion.fastEffectsSpec()),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        Text(
                            text = vaultErrorText(error),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            VaultSetupActions(
                content = content,
                busy = isBusy,
                onChooseFolderClick = onChooseFolderClick,
                onConfirmClick = onConfirmClick,
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
private fun VaultSetupLoading() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        DelayedLoadingIndicator(loading = true)
    }
}

@Composable
private fun VaultSetupHeader() {
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(88.dp).background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialShapes.Cookie9Sided.toShape(),
            ),
            contentAlignment = Alignment.Center,
        ) {
            LoomaIcon(
                iconRes = R.drawable.ic_folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.vault_setup_title),
            style = LoomaTokens.type.heroTitle,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.vault_setup_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VaultFolderCard(
    folderName: String,
    pending: Boolean,
    previousFolderName: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                color = colors.primary,
                contentColor = colors.onPrimary,
                shape = CircleShape,
            ) {
                Text(
                    text = stringResource(
                        if (pending) R.string.vault_setup_pending_label else R.string.vault_setup_current_label,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Text(
                text = folderName.ifBlank { stringResource(R.string.vault_setup_unnamed_folder) },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(color = colors.surface, contentColor = colors.onSurface, shape = CircleShape) {
                Text(
                    text = stringResource(
                        if (pending) R.string.vault_setup_pending_hint else R.string.vault_setup_connected,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (pending && previousFolderName != null) {
                HorizontalDivider(color = colors.outlineVariant)
                Text(
                    text = stringResource(R.string.vault_setup_previous, previousFolderName),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun VaultSetupActions(
    content: VaultContent,
    busy: Boolean,
    onChooseFolderClick: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    val height = ButtonDefaults.MediumContainerHeight
    if (content == VaultContent.Loading) {
        Spacer(Modifier.height(height))
        return
    }

    if (content == VaultContent.Empty) {
        Button(
            onClick = onChooseFolderClick,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = height),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
        ) {
            Text(
                text = stringResource(R.string.vault_setup_choose),
                style = ButtonDefaults.textStyleFor(height),
            )
        }
        return
    }

    val primaryLabel = when {
        busy -> stringResource(R.string.vault_setup_connecting)
        content is VaultContent.Pending -> stringResource(R.string.vault_setup_confirm)
        else -> stringResource(R.string.vault_setup_confirm)
    }
    val secondaryLabel = stringResource(
        if (content is VaultContent.Pending) R.string.vault_setup_reselect else R.string.vault_setup_change,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = onChooseFolderClick,
            enabled = !busy,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f).heightIn(min = height),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
        ) {
            Text(text = secondaryLabel, style = ButtonDefaults.textStyleFor(height))
        }
        Button(
            onClick = onConfirmClick,
            enabled = !busy,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f).heightIn(min = height),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
        ) {
            Text(text = primaryLabel, style = ButtonDefaults.textStyleFor(height))
        }
    }
}

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
