@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.example.looma.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.data.settings.S3ImageBedConfig
import com.example.looma.ui.components.ErrorBanner
import com.example.looma.ui.theme.LoomaTokens
import java.io.IOException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@Composable
internal fun ImageBedConfigSheet(
    config: S3ImageBedConfig,
    credentialsUnavailable: Boolean,
    testState: S3ConnectionTestState,
    onTestConnection: (S3ImageBedConfig) -> Unit,
    onResetTestState: () -> Unit,
    onSave: suspend (S3ImageBedConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val endpoint = rememberTextFieldState(config.endpoint)
    val region = rememberTextFieldState(config.region.ifBlank { "auto" })
    val bucket = rememberTextFieldState(config.bucket)
    val accessKey = rememberTextFieldState(config.accessKey)
    val secretKey = rememberTextFieldState(config.secretKey)
    val pathPrefix = rememberTextFieldState(config.pathPrefix)
    val publicBaseUrl = rememberTextFieldState(config.publicBaseUrl)
    var pathStyle by remember { mutableStateOf(config.pathStyle) }
    var publicRead by remember { mutableStateOf(config.publicRead) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentOnResetTestState by rememberUpdatedState(onResetTestState)

    fun draftConfig() = config.copy(
        endpoint = endpoint.text.toString().trim(),
        region = region.text.toString().trim().ifBlank { "auto" },
        bucket = bucket.text.toString().trim(),
        accessKey = accessKey.text.toString().trim(),
        secretKey = secretKey.text.toString(),
        pathPrefix = pathPrefix.text.toString().trim(),
        publicBaseUrl = publicBaseUrl.text.toString().trim(),
        pathStyle = pathStyle,
        publicRead = publicRead,
    )

    LaunchedEffect(Unit) {
        snapshotFlow { draftConfig() }
            .distinctUntilChanged()
            .drop(1)
            .collect { currentOnResetTestState() }
    }

    val sheetState = rememberExpandedSheetState()
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        containerColor = LoomaTokens.colors.sheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_image_bed_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (credentialsUnavailable) {
                ErrorBanner(message = stringResource(R.string.settings_image_bed_credentials_unavailable))
            }
            Text(
                text = stringResource(R.string.settings_image_bed_credentials_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            S3ConfigTextField(
                state = endpoint,
                label = stringResource(R.string.settings_image_bed_endpoint),
                supportingText = stringResource(R.string.settings_sync_endpoint_hint),
            )
            S3ConfigTextField(
                state = region,
                label = stringResource(R.string.settings_image_bed_region),
            )
            S3ConfigTextField(
                state = bucket,
                label = stringResource(R.string.settings_image_bed_bucket),
            )
            S3ConfigTextField(
                state = accessKey,
                label = stringResource(R.string.settings_image_bed_access_key),
            )
            OutlinedSecureTextField(
                state = secretKey,
                label = { Text(stringResource(R.string.settings_image_bed_secret_key)) },
                labelPosition = TextFieldLabelPosition.Inside(),
                shape = OutlinedTextFieldDefaults.roundedShape,
                modifier = Modifier.fillMaxWidth(),
            )
            S3ConfigTextField(
                state = pathPrefix,
                label = stringResource(R.string.settings_image_bed_path_prefix),
            )
            S3ConfigTextField(
                state = publicBaseUrl,
                label = stringResource(R.string.settings_image_bed_public_url),
            )
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SettingsSwitchRow(
                    index = 0,
                    count = 2,
                    title = stringResource(R.string.settings_image_bed_path_style),
                    description = stringResource(R.string.settings_image_bed_path_style_desc),
                    checked = pathStyle,
                    enabled = !saving,
                    onCheckedChange = { pathStyle = it },
                )
                SettingsSwitchRow(
                    index = 1,
                    count = 2,
                    title = stringResource(R.string.settings_image_bed_public_read),
                    description = stringResource(R.string.settings_image_bed_public_read_desc),
                    checked = publicRead,
                    enabled = !saving,
                    onCheckedChange = { publicRead = it },
                )
            }
            S3TestConnectionRow(
                testState = testState,
                enabled = !saving && testState !is S3ConnectionTestState.Testing,
                onTestClick = { onTestConnection(draftConfig()) },
            )
            if (saveFailed) {
                Text(
                    text = stringResource(R.string.settings_image_bed_save_failed),
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
                    enabled = !saving,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
                Button(
                    enabled = !saving,
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        val updatedConfig = draftConfig()
                        saving = true
                        saveFailed = false
                        scope.launch {
                            try {
                                onSave(updatedConfig)
                            } catch (_: IOException) {
                                saveFailed = true
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.dialog_save))
                }
            }
        }
    }
}
