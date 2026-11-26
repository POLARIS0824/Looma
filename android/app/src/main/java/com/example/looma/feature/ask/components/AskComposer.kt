package com.example.looma.feature.ask.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.data.assistant.ModelChoice
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.PageMaxWidth
import com.example.looma.ui.components.pageMargin
import com.example.looma.ui.theme.LoomaTokens

internal const val AskQuestionLimit = 1000

@Composable
internal fun AskComposer(
    input: TextFieldState,
    choice: ModelChoice?,
    running: Boolean,
    busy: Boolean,
    sheetOpen: Boolean,
    effortOpen: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onModel: () -> Unit,
    onEffort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    var focused by remember { mutableStateOf(false) }
    val expanded = focused || input.text.isNotEmpty() || sheetOpen
    val tooLong = input.text.length > AskQuestionLimit
    val canSend = input.text.isNotBlank() && !tooLong && !busy
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = pageMargin(), vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = PageMaxWidth).fillMaxWidth(),
            color = LoomaTokens.colors.searchField,
            shape = MaterialTheme.shapes.largeIncreased,
        ) {
            Column(Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        state = input,
                        modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                        placeholder = { Text(stringResource(R.string.ask_placeholder)) },
                        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        isError = tooLong,
                        supportingText = if (tooLong) {
                            { Text(stringResource(R.string.ask_length)) }
                        } else null,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            errorIndicatorColor = Color.Transparent,
                        ),
                    )
                    AnimatedVisibility(
                        visible = !expanded,
                        enter = expandHorizontally(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
                        exit = shrinkHorizontally(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                    ) {
                        SendButton(running = running, enabled = canSend, onSend = onSend, onStop = onStop)
                    }
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                    exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.defaultEffectsSpec()),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ModelChip(choice = choice, effortOpen = effortOpen, onModel = onModel, onEffort = onEffort)
                        Spacer(Modifier.weight(1f))
                        SendButton(running = running, enabled = canSend, onSend = onSend, onStop = onStop)
                    }
                }
            }
        }
    }
}

@Composable
private fun SendButton(running: Boolean, enabled: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    FilledIconButton(
        onClick = if (running) onStop else onSend,
        enabled = running || enabled,
        shapes = IconButtonDefaults.shapes(),
    ) {
        SwapIcon(
            iconRes = if (running) R.drawable.ic_stop else R.drawable.ic_arrow_up,
            contentDescription = stringResource(if (running) R.string.ask_stop else R.string.ask_send),
        )
    }
}

@Composable
private fun ModelChip(choice: ModelChoice?, effortOpen: Boolean, onModel: () -> Unit, onEffort: () -> Unit) {
    val height = SplitButtonDefaults.ExtraSmallContainerHeight
    if (choice == null) {
        FilledTonalButton(
            onClick = onModel,
            modifier = Modifier.height(height),
            shapes = ButtonDefaults.shapesFor(height),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
        ) {
            Text(stringResource(R.string.ask_choose_model))
        }
        return
    }
    SplitButtonLayout(
        leadingButton = {
            SplitButtonDefaults.TonalLeadingButton(
                onClick = onModel,
                modifier = Modifier.height(height),
                shapes = SplitButtonDefaults.leadingButtonShapesFor(height),
                contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(height),
            ) {
                SwapText(choice.model.displayLabel())
            }
        },
        trailingButton = {
            SplitButtonDefaults.TonalTrailingButton(
                checked = effortOpen,
                onCheckedChange = { onEffort() },
                modifier = Modifier.height(height),
                shapes = SplitButtonDefaults.trailingButtonShapesFor(height),
                contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(height),
            ) {
                SwapText(stringResource(choice.effort.labelRes()))
                LoomaIcon(
                    iconRes = R.drawable.ic_expand_more,
                    contentDescription = stringResource(R.string.ask_effort_title),
                    modifier = Modifier.size(SplitButtonDefaults.trailingButtonIconSizeFor(height)),
                )
            }
        },
    )
}
