@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.example.looma.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.looma.ui.theme.LoomaTokens

enum class FeatureActionStyle { Filled, Tonal }

@Composable
fun FeaturePage(
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String,
    actions: (@Composable FlowRowScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    topBar: (@Composable (TopAppBarScrollBehavior) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { topBar?.invoke(scrollBehavior) },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = PageMaxWidth)
                .fillMaxWidth()
                .padding(
                    start = pageMargin(),
                    end = pageMargin(),
                    bottom = 24.dp,
                ),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = if (topBar == null) 24.dp else 8.dp),
            ) {
                FeatureHeader(iconRes = iconRes, title = title, subtitle = subtitle)
                Spacer(Modifier.height(32.dp))
                content()
            }
            if (actions != null) {
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions,
                )
            }
        }
    }
}

@Composable
fun FlowRowScope.FeatureAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: FeatureActionStyle = FeatureActionStyle.Filled,
    enabled: Boolean = true,
    @DrawableRes iconRes: Int? = null,
) {
    val height = ButtonDefaults.MediumContainerHeight
    val buttonModifier = modifier.weight(1f).heightIn(min = height)
    val label: @Composable () -> Unit = {
        if (iconRes != null) {
            LoomaIcon(
                iconRes = iconRes,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        }
        Text(text = text, style = ButtonDefaults.textStyleFor(height))
    }
    when (style) {
        FeatureActionStyle.Filled -> Button(
            onClick = onClick,
            enabled = enabled,
            shapes = ButtonDefaults.shapesFor(height),
            modifier = buttonModifier,
            contentPadding = ButtonDefaults.contentPaddingFor(height),
        ) { label() }
        FeatureActionStyle.Tonal -> FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            shapes = ButtonDefaults.shapesFor(height),
            modifier = buttonModifier,
            contentPadding = ButtonDefaults.contentPaddingFor(height),
        ) { label() }
    }
}

@Composable
private fun FeatureHeader(@DrawableRes iconRes: Int, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(88.dp).background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialShapes.Cookie9Sided.toShape(),
            ),
            contentAlignment = Alignment.Center,
        ) {
            LoomaIcon(
                iconRes = iconRes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            style = LoomaTokens.type.heroTitle,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
