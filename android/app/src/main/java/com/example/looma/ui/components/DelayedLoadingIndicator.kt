@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.example.looma.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

@Composable
fun DelayedLoadingIndicator(
    loading: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = LoadingIndicatorDefaults.ContainerHeight,
    color: Color = LoadingIndicatorDefaults.indicatorColor,
) {
    val motion = MaterialTheme.motionScheme
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = rememberDelayedVisibility(loading),
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
        ) {
            LoadingIndicator(modifier = Modifier.size(size), color = color)
        }
    }
}
