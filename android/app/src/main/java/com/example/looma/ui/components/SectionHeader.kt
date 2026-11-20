package com.example.looma.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object SectionHeaderDefaults {
    val ContentGap: Dp = 16.dp
    val GroupGap: Dp = 24.dp
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    first: Boolean = true,
    parentSpacing: Dp = 0.dp,
) {
    val top = if (first) 0.dp else SectionHeaderDefaults.GroupGap
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = (top - parentSpacing).coerceAtLeast(0.dp),
                bottom = (SectionHeaderDefaults.ContentGap - parentSpacing).coerceAtLeast(0.dp),
            )
            .semantics { heading() },
    )
}
