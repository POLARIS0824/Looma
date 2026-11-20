package com.example.looma.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

internal const val DelayedFeedbackMillis = 300L

@Composable
fun rememberDelayedVisibility(
    active: Boolean,
    delayMillis: Long = DelayedFeedbackMillis,
): Boolean {
    var elapsed by remember(active) { mutableStateOf(false) }
    LaunchedEffect(active, delayMillis) {
        if (active) {
            delay(delayMillis)
            elapsed = true
        }
    }
    return active && elapsed
}
