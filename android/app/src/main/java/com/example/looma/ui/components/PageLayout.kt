package com.example.looma.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val PageMaxWidth = 640.dp

@Composable
fun isCompactWindow(): Boolean {
    val width = LocalWindowInfo.current.containerSize.width
    return with(LocalDensity.current) { width.toDp() } < CompactWidthLimit
}

@Composable
fun pageMargin(): Dp = if (isCompactWindow()) 16.dp else 24.dp

@Composable
fun noteGridMargin(): Dp = if (isCompactWindow()) 8.dp else 16.dp

private val CompactWidthLimit = 600.dp
