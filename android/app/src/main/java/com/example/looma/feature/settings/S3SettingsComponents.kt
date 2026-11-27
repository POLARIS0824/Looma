package com.example.looma.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.data.s3.S3ErrorKind
import com.example.looma.data.s3.S3Exception
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.components.LoomaIcon

internal sealed interface S3ConnectionTestState {
    data object Idle : S3ConnectionTestState
    data object Testing : S3ConnectionTestState
    data object Succeeded : S3ConnectionTestState
    data class Failed(@StringRes val messageRes: Int, val detail: String? = null) : S3ConnectionTestState
}

@Composable
internal fun S3ConfigTextField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        state = state,
        label = { Text(label) },
        labelPosition = TextFieldLabelPosition.Inside(),
        shape = OutlinedTextFieldDefaults.roundedShape,
        lineLimits = TextFieldLineLimits.SingleLine,
        isError = isError,
        supportingText = supportingText?.let { text -> { Text(text) } },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun S3TestConnectionRow(
    testState: S3ConnectionTestState,
    enabled: Boolean,
    onTestClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = onTestClick,
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(R.string.settings_sync_test))
        }
        S3TestFeedback(testState = testState)
    }
}

@Composable
internal fun S3TestFeedback(
    testState: S3ConnectionTestState,
    modifier: Modifier = Modifier,
) {
    when (testState) {
        S3ConnectionTestState.Idle -> Unit
        S3ConnectionTestState.Testing -> {
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DelayedLoadingIndicator(loading = true, size = 24.dp)
                Text(
                    text = stringResource(R.string.settings_sync_testing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        S3ConnectionTestState.Succeeded -> {
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoomaIcon(
                    iconRes = R.drawable.ic_cloud_done,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.settings_sync_test_ok),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is S3ConnectionTestState.Failed -> {
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoomaIcon(
                    iconRes = R.drawable.ic_warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = s3FailureMessage(testState.messageRes, testState.detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun s3FailureMessage(@StringRes messageRes: Int, detail: String?): String {
    val base = stringResource(messageRes)
    return if (detail.isNullOrBlank()) base else "$base ($detail)"
}

@StringRes
internal fun s3ErrorMessageRes(error: Throwable): Int = when (error) {
    is S3Exception -> when (error.kind) {
        S3ErrorKind.InvalidConfig -> R.string.settings_sync_err_invalid_config
        S3ErrorKind.AuthFailed -> R.string.settings_sync_err_auth
        S3ErrorKind.BucketNotFound -> R.string.settings_sync_err_bucket_not_found
        S3ErrorKind.ClockSkew -> R.string.settings_sync_err_clock_skew
        S3ErrorKind.Network -> R.string.settings_sync_err_network
        else -> R.string.settings_sync_err_generic
    }
    else -> R.string.settings_sync_err_generic
}

internal fun s3ErrorDetailFor(error: Throwable, @StringRes messageRes: Int): String? =
    if (messageRes == R.string.settings_sync_err_generic) {
        error.message?.takeIf { it.isNotBlank() }
    } else {
        null
    }
