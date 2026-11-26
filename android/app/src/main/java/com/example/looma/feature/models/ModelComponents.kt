package com.example.looma.feature.models

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.data.assistant.AnswerModels
import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.ApiModel
import com.example.looma.data.assistant.ApiProvider
import com.example.looma.data.assistant.ConnectionStatus
import com.example.looma.data.assistant.LocalModelEntry
import com.example.looma.data.assistant.LocalModelState
import com.example.looma.ui.components.LoomaIcon

@StringRes
internal fun ApiProvider.labelRes(): Int = when (this) {
    ApiProvider.OpenAiCompatible -> R.string.models_provider_openai
    ApiProvider.Anthropic -> R.string.models_provider_anthropic
    ApiProvider.Gemini -> R.string.models_provider_gemini
    ApiProvider.Custom -> R.string.models_provider_custom
}

@StringRes
internal fun ConnectionStatus.labelRes(): Int = when (this) {
    ConnectionStatus.Unchecked -> R.string.models_status_unchecked
    ConnectionStatus.Checking -> R.string.models_status_checking
    ConnectionStatus.Ok -> R.string.models_status_ok
    ConnectionStatus.Failed -> R.string.models_status_failed
}

@Composable
internal fun LocalModelEntry.displayName(): String =
    if (id == AnswerModels.LocalModel.id) stringResource(R.string.models_imported_name) else name

@Composable
internal fun ModelTopBar(title: String, onNavigateUp: () -> Unit, scrollBehavior: TopAppBarScrollBehavior) {
    TopAppBar(
        title = { Text(title) },
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
}

@Composable
internal fun ConnectionStatusBadge(status: ConnectionStatus) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        ConnectionStatus.Ok -> colors.primaryContainer to colors.onPrimaryContainer
        ConnectionStatus.Failed -> colors.errorContainer to colors.onErrorContainer
        ConnectionStatus.Unchecked, ConnectionStatus.Checking -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    ModelBadge(text = stringResource(status.labelRes()), container = container, content = content)
}

@Composable
internal fun ModelBadge(
    text: String,
    container: Color,
    content: Color,
) {
    Surface(color = container, contentColor = content, shape = CircleShape) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun ProviderAvatar(provider: ApiProvider) {
    val label = stringResource(provider.labelRes())
    Surface(
        modifier = Modifier.size(40.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label.take(1), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun ModelTextField(
    state: TextFieldState,
    label: String,
    placeholder: String? = null,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        state = state,
        label = { Text(label) },
        labelPosition = TextFieldLabelPosition.Inside(),
        shape = OutlinedTextFieldDefaults.roundedShape,
        lineLimits = TextFieldLineLimits.SingleLine,
        enabled = enabled,
        placeholder = placeholder?.takeIf { it.isNotEmpty() }?.let { text -> { Text(text) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

internal object ModelPreviewData {
    val local = listOf(
        LocalModelEntry("gemma-3n-e2b", "Gemma 3n E2B", 1_300_000_000L, null, recommended = true, LocalModelState.Ready),
        LocalModelEntry("gemma-3n-e4b", "Gemma 3n E4B", 2_900_000_000L, null, recommended = false, LocalModelState.Downloading(0.42f)),
        LocalModelEntry("qwen3-1.7b", "Qwen3 1.7B", 1_700_000_000L, null, recommended = false, LocalModelState.NotDownloaded),
    )
    val connections = listOf(
        ApiConnection(
            "work", "Work OpenAI", ApiProvider.OpenAiCompatible, "https://api.openai.com/v1", "sk-proj-0000",
            ConnectionStatus.Ok, listOf(ApiModel("gpt-4.1-mini", true), ApiModel("gpt-4.1", true), ApiModel("o4-mini", false)),
        ),
        ApiConnection(
            "claude", "Personal Claude", ApiProvider.Anthropic, "https://api.anthropic.com", "sk-ant-0000",
            ConnectionStatus.Ok, listOf(ApiModel("claude-sonnet-4-5", true)),
        ),
        ApiConnection(
            "ollama", "Home Ollama", ApiProvider.Custom, "http://192.168.1.2:11434/v1", "",
            ConnectionStatus.Failed, listOf(ApiModel("qwen3:8b", true)),
        ),
    )
}
