package com.example.looma.ui.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.ui.theme.LoomaTokens
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.model.markdownInlineContent

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
    val theme = loomaMarkdownTheme(
        base = LoomaTokens.type.noteBody,
    )
    val dividerColor = LoomaTokens.colors.divider
    val accent = LoomaTokens.colors.accent
    val muted = LoomaTokens.colors.muted
    val checkedText = MaterialTheme.colorScheme.onSurfaceVariant
    val math = rememberMathRenderState(
        spans = preview.mathSpans,
        fontSize = LoomaTokens.type.noteBody.fontSize,
        color = LoomaTokens.colors.title,
    )
    val annotator = remember(math) { mathAnnotator(math) }
    val bodyStart = preview.index.bodyStart
    val components = remember(theme, dividerColor, bodyStart, wiki, accent, muted, math, onCheckboxToggle, checkedText) {
        loomaMarkdownComponents(
            theme = theme,
            compact = false,
            dividerColor = dividerColor,
            bodyStart = bodyStart,
            onCheckboxToggle = onCheckboxToggle,
            wiki = wiki,
            onWikiLinkClick = { target -> currentLinkClick.value(target) },
            resolvedLink = SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
            missingLink = SpanStyle(color = muted, textDecoration = TextDecoration.Underline),
            math = math,
            checkedText = checkedText,
        )
    }

    val transformer = remember { CoilImageTransformer() }
    Markdown(
        state = preview.state,
        imageTransformer = transformer,
        annotator = annotator,
        colors = theme.colors,
        typography = theme.typography,
        padding = theme.padding,
        components = components,
        modifier = modifier.fillMaxWidth(),
        success = { state, activeComponents, bodyModifier ->
            LazyColumn(
                modifier = bodyModifier,
                state = scrollState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                if (header != null) item(key = "header", contentType = "header") { header() }
                items(state.node.children, key = { it.startOffset }, contentType = { it.type }) { node ->
                    val range = node.startOffset until node.endOffset
                    if (math.hasInlineMath(range)) {
                        val inline = rememberMathInlineContent(math, math.fontSize, range)
                        CompositionLocalProvider(
                            LocalMarkdownInlineContent provides markdownInlineContent(inline),
                        ) {
                            MarkdownElement(node, activeComponents, state.content)
                        }
                    } else {
                        MarkdownElement(node, activeComponents, state.content)
                    }
                }
                if (footer != null) item(key = "footer", contentType = "footer") { footer() }
            }
        },
    )
}
