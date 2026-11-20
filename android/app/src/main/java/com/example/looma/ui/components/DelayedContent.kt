package com.example.looma.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun DelayedContent(
    modifier: Modifier = Modifier,
    delayMillis: Long = DelayedFeedbackMillis,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = rememberDelayedVisibility(active = true, delayMillis = delayMillis),
        modifier = modifier,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
    ) {
        content()
    }
}
