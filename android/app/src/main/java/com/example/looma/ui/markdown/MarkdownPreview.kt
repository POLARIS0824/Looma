package com.example.looma.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import com.example.looma.domain.markdown.WikiImageFlavour
import com.example.looma.ui.theme.LoomaPreviewTypography
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes

@Composable
internal fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    typography: LoomaPreviewTypography? = null,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    onSelectedContainer: Boolean = false,
) {
    if (markdown.isBlank()) return
    val inspection = LocalInspectionMode.current
    var parsed by remember(inspection) {
        mutableStateOf(
            if (inspection) parsePreview(markdown) else markdownPreviewCache.get(markdown),
        )
    }
    val cached = if (inspection) parsePreview(markdown) else markdownPreviewCache.get(markdown)
    val state = cached ?: parsed
    LaunchedEffect(markdown, inspection) {
        if (inspection) {
            parsed = parsePreview(markdown)
        } else {
            parsed = markdownPreviewCache.get(markdown) ?: markdownPreviewCache.load(markdown)
        }
    }
    if (state == null) return

    val theme = loomaMarkdownTheme(
        base = typography?.body ?: MaterialTheme.typography.bodySmall,
        compact = true,
        preview = typography,
        textColor = textColor,
        onSelectedContainer = onSelectedContainer,
    )
    val components = remember(theme) {
        loomaMarkdownComponents(
            theme = theme,
            compact = true,
            showImages = false,
        )
    }

    Markdown(
        state = state,
        annotator = previewMarkdownAnnotator,
        colors = theme.colors,
        typography = theme.typography,
        padding = theme.padding,
        extendedSpans = theme.extendedSpans,
        components = components,
        modifier = modifier.fillMaxWidth(),
    )
}

private val markdownPreviewCache = MarkdownPreviewCache(capacity = 128)

internal suspend fun warmMarkdownPreview(markdown: String) {
    if (markdown.isNotBlank()) markdownPreviewCache.load(markdown)
}

internal class MarkdownPreviewCache(private val capacity: Int) {
    private val states = LinkedHashMap<String, State.Success>(capacity, 0.75f, true)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    fun get(markdown: String): State.Success? = synchronized(states) { states[markdown] }

    suspend fun load(markdown: String): State = withContext(dispatcher) {
        get(markdown) ?: parsePreview(markdown).also { state ->
            if (state is State.Success) synchronized(states) {
                states[markdown] = state
                if (states.size > capacity) states.remove(states.keys.first())
            }
        }
    }
}

private fun parsePreview(markdown: String): State =
    parseMarkdown(content = markdown, flavour = WikiImageFlavour())

internal val previewMarkdownAnnotator =
    markdownAnnotator(config = loomaAnnotatorConfig) { _, node ->
        node.type == MarkdownElementTypes.IMAGE
}
