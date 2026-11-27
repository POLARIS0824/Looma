package com.example.looma.ui.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.looma.domain.markdown.PreparedMarkdown
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.ui.components.pageMargin
import com.example.looma.ui.theme.LoomaTokens
import com.mikepenz.markdown.compose.Markdown

@Composable
internal fun MarkdownBody(
    preview: PreparedMarkdown.Ready,
    onCheckboxToggle: ((position: Int, checked: Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    wiki: WikiContext? = null,
    onWikiLinkClick: (target: String) -> Unit = {},
    scrollState: LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val currentLinkClick = rememberUpdatedState(onWikiLinkClick)
    val currentCheckboxToggle = rememberUpdatedState(onCheckboxToggle)
    val hasCheckboxToggle = onCheckboxToggle != null
    val layout = MarkdownLayoutPresets.forDensity(MarkdownDensity.Comfortable)
    val theme = loomaMarkdownTheme(
        base = LoomaTokens.type.noteBody,
        density = MarkdownDensity.Comfortable,
    )
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val checkedText = MaterialTheme.colorScheme.onSurfaceVariant
    val math = rememberMathRenderState(
        spans = preview.mathSpans,
        fontSize = LoomaTokens.type.noteBody.fontSize,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val annotator = remember(math) { mathAnnotator(math) }
    val bodyStart = preview.index.bodyStart
    val components = remember(
        layout,
        theme.colors.codeBackground,
        theme.colors.inlineCodeBackground,
        theme.colors.dividerColor,
        theme.colors.tableBackground,
        theme.codeShape,
        theme.codeBorder,
        theme.tableShape,
        theme.tableHeader,
        theme.blockQuoteContainer,
        theme.blockQuoteShape,
        theme.blockQuoteBar,
        theme.listMarkerColor,
        bodyStart,
        wiki,
        accent,
        muted,
        math,
        hasCheckboxToggle,
        checkedText,
    ) {
        loomaMarkdownComponents(
            layout = layout,
            theme = theme,
            bodyStart = bodyStart,
            onCheckboxToggle = if (hasCheckboxToggle) { pos, checked ->
                currentCheckboxToggle.value?.invoke(pos, checked)
            } else {
                null
            },
            wiki = wiki,
            onWikiLinkClick = { target -> currentLinkClick.value(target) },
            resolvedLink = SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
            missingLink = SpanStyle(color = muted, textDecoration = TextDecoration.Underline),
            math = math,
            checkedText = checkedText,
        )
    }

    CompositionLocalProvider(LocalMarkdownLayoutSpec provides layout) {
        Markdown(
            state = preview.state,
            imageTransformer = InlineMarkdownImageTransformer,
            annotator = annotator,
            colors = theme.colors,
            typography = theme.typography,
            padding = zeroMarkdownPadding(),
            extendedSpans = theme.extendedSpans,
            components = components,
            modifier = modifier.fillMaxWidth(),
            success = { state, activeComponents, bodyModifier ->
                val plannedBlocks = remember(state.node, layout) {
                    planDocumentBlocks(state.node.children, layout, showImages = true)
                }
                LazyColumn(
                    modifier = bodyModifier,
                    state = scrollState,
                    contentPadding = PaddingValues(horizontal = pageMargin(), vertical = 8.dp),
                ) {
                    if (header != null) item(key = "header", contentType = "header") { header() }
                    itemsIndexed(
                        items = plannedBlocks,
                        key = { _, block -> block.node.startOffset },
                        contentType = { _, block -> block.node.type },
                    ) { _, planned ->
                        RenderPlannedMarkdownBlock(
                            planned = planned,
                            components = activeComponents,
                            content = state.content,
                            math = math,
                        )
                    }
                    if (footer != null) item(key = "footer", contentType = "footer") { footer() }
                }
            },
        )
    }
}
