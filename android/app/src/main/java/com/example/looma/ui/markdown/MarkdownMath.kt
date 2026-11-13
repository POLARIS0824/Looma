package com.example.looma.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.TextUnit
import com.example.looma.domain.markdown.MathSpan
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import io.ratex.DisplayList
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.compose.RaTeX
import io.ratex.measure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

@Immutable
internal class MathRenderState(
    private val spans: List<MathSpan>,
    private val displays: Map<Int, DisplayList>,
    val fontSize: TextUnit,
) {
    fun span(start: Int): MathSpan? =
        spans.getOrNull(spans.binarySearchBy(start) { it.range.first })

    fun display(start: Int): DisplayList? = displays[start]

    fun hasInlineMath(range: IntRange): Boolean =
        inlineMath(range).any()

    fun inlineMath(range: IntRange): Sequence<MathSpan> {
        val index = spans.binarySearchBy(range.first) { it.range.first }
        val start = if (index >= 0) index else -index - 1
        return (start until spans.size).asSequence()
            .map(spans::get)
            .takeWhile { it.range.first <= range.last }
            .filterNot { it.block }
    }

    fun tag(start: Int): String = mathTag(start)

    fun blockDisplay(node: ASTNode): DisplayList? {
        val math = node.children.firstOrNull { it.type == GFMElementTypes.BLOCK_MATH } ?: return null
        if (span(math.startOffset)?.block != true) return null
        return displays[math.startOffset]
    }
}

@Composable
internal fun rememberMathRenderState(
    spans: List<MathSpan>,
    fontSize: TextUnit,
    color: Color,
): MathRenderState {
    val displayState = remember(spans, color) { mutableStateOf(emptyMap<Int, DisplayList>()) }
    LaunchedEffect(spans, color) {
        if (spans.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            runCatching { RaTeXFontLoader.ensureLoaded() }
                .onFailure { if (it is CancellationException) throw it }
            val parsed = mutableMapOf<Int, DisplayList>()
            var publishedCount = 0
            suspend fun publish() {
                val displays = parsed.toMap()
                withContext(Dispatchers.Main) { displayState.value = displays }
                publishedCount = displays.size
            }

            for (span in spans) {
                ensureActive()
                val display = mathDisplays.get(span.latex, span.display, color)
                    ?: runCatching { RaTeXEngine.parseBlocking(span.latex, span.display, color) }
                        .getOrNull()
                        ?.also { mathDisplays.put(span.latex, span.display, color, it) }
                    ?: continue
                parsed[span.range.first] = display
                if (parsed.size >= publishedCount * 2) {
                    publish()
                }
            }
            if (parsed.size > publishedCount) publish()
        }
    }
    val displays = displayState.value

    return remember(spans, displays, fontSize) {
        MathRenderState(
            spans = spans,
            displays = displays,
            fontSize = fontSize,
        )
    }
}

@Composable
internal fun rememberMathInlineContent(
    state: MathRenderState,
    fontSize: TextUnit,
    range: IntRange,
    maxWidth: TextUnit? = null,
): Map<String, InlineTextContent> {
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.toPx() }
    return remember(state, fontSize, fontSizePx, density, range, maxWidth) {
        buildMap {
            state.inlineMath(range).forEach { span ->
                val start = span.range.first
                val display = state.display(start) ?: return@forEach
                val measured = display.measure(fontSizePx)
                val formulaWidth = with(density) { measured.widthPx.toSp() }
                val placeholderWidth = when {
                    maxWidth == null || formulaWidth <= maxWidth -> formulaWidth
                    else -> maxWidth
                }
                put(
                    mathTag(start),
                    InlineTextContent(
                        Placeholder(
                            width = placeholderWidth,
                            height = with(density) { measured.totalHeightPx.toSp() },
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                        ),
                    ) { _ ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            RaTeX(displayList = display, fontSize = fontSize)
                        }
                    },
                )
            }
        }
    }
}

internal fun mathAnnotator(state: MathRenderState): MarkdownAnnotator =
    markdownAnnotator(config = loomaAnnotatorConfig) { content, child ->
        if (child.type != GFMElementTypes.INLINE_MATH && child.type != GFMElementTypes.BLOCK_MATH) {
            return@markdownAnnotator false
        }
        val span = state.span(child.startOffset) ?: return@markdownAnnotator false
        if (span.block || state.display(span.range.first) == null) return@markdownAnnotator false
        appendInlineContent(state.tag(span.range.first), content.substring(span.range))
        true
    }

@Composable
internal fun MathBlock(
    display: DisplayList,
    fontSize: TextUnit,
    padding: PaddingValues,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(padding)
            .horizontalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        RaTeX(displayList = display, fontSize = fontSize)
    }
}

private fun mathTag(start: Int): String ="looma-math-$start"

private object mathDisplays {
    private const val MAX_ENTRIES = 64
    private val entries = object : LinkedHashMap<String, DisplayList>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DisplayList>) =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(latex: String, display: Boolean, color: Color): DisplayList? = entries[key(latex, display, color)]

    @Synchronized
    fun put(latex: String, display: Boolean, color: Color, displayList: DisplayList) {
        entries[key(latex, display, color)] = displayList
    }

    private fun key(latex: String, display: Boolean, color: Color): String =
"${display}:${color.value}:$latex"
}
