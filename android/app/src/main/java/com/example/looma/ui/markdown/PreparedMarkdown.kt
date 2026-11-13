package com.example.looma.ui.markdown

import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.markdown.MarkdownReadingIndex
import com.example.looma.domain.markdown.MathSpan
import com.example.looma.domain.markdown.ReadingBlock
import com.example.looma.domain.markdown.WikiImageFlavour
import com.example.looma.domain.markdown.collectMathSpans
import com.example.looma.domain.markdown.frontmatterEnd
import com.example.looma.domain.markdown.preserveDisplayMathParagraphs
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.EmptyStreamingMarkdownFile
import org.intellij.markdown.parser.MarkdownParser

internal sealed interface PreparedMarkdown {
    val source: String

    class Ready(
        override val source: String,
        val index: MarkdownReadingIndex,
        val state: State.Success,
        val mathSpans: List<MathSpan>,
        val blocks: List<ReadingBlock>,
    ) : PreparedMarkdown

    class Opening(
        override val source: String,
        val preview: Ready,
    ) : PreparedMarkdown

    class Failed(override val source: String) : PreparedMarkdown
}

internal suspend fun prepareMarkdown(source: String): PreparedMarkdown =
    prepareInBackground { prepareMarkdownOnCurrentThread(source, it) }

internal suspend fun prepareMarkdownOpening(source: String): PreparedMarkdown.Opening? =
    prepareInBackground { prepareMarkdownOpeningOnCurrentThread(source, it) }

private suspend fun <T> prepareInBackground(prepare: (CancellationToken) -> T): T =
    withContext(Dispatchers.Default) {
        val cancellation = object : CancellationToken {
            override fun checkCancelled() = ensureActive()
        }
        prepare(cancellation)
    }

internal fun prepareMarkdownOpeningOnCurrentThread(
    source: String,
    cancellation: CancellationToken,
): PreparedMarkdown.Opening? {
    val bodyStart = frontmatterEnd(source)
    if (bodyStart >= OPENING_CHARS || source.length - bodyStart <= OPENING_CHARS) return null
    val prefix = source.substring(bodyStart, bodyStart + OPENING_CHARS)
    val beforeMath = prefix.indexOf("$$").takeIf { it >= 0 } ?: prefix.length
    val streaming = EmptyStreamingMarkdownFile(WikiImageFlavour(), cancellation)
    val stableEnd = try {
        streaming.append(prefix.subSequence(0, beforeMath))
        streaming.stableChildren.lastOrNull()?.endOffset ?: return null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    } catch (_: StackOverflowError) {
        return null
    }
    val preview = prepareMarkdownOnCurrentThread(source.substring(0, bodyStart + stableEnd), cancellation)
    return (preview as? PreparedMarkdown.Ready)?.let { PreparedMarkdown.Opening(source, it) }
}

internal fun prepareMarkdownOnCurrentThread(
    source: String,
    cancellation: CancellationToken,
): PreparedMarkdown = try {
    val bodyStart = frontmatterEnd(source)
    val body = source.substring(bodyStart)
    val flavour = WikiImageFlavour()
    val parser = MarkdownParser(flavour, cancellationToken = cancellation)
    fun parse(content: String): State.Success {
        val state = parseMarkdown(content = content, flavour = flavour, parser = parser)
        cancellation.checkCancelled()
        check(state is State.Success)
        return state
    }

    val raw = parse(body)
    val content = preserveDisplayMathParagraphs(body, raw.node)
    val state = if (content === body) raw else parse(content)
    val index = MarkdownIndex.forReading(body, bodyStart, raw.node)
    val spans = collectMathSpans(state.content, state.node)
    val blocks = state.node.children.map {
        ReadingBlock(
            start = it.startOffset,
            end = it.endOffset,
            hasContent = it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE,
        )
    }
    cancellation.checkCancelled()
    PreparedMarkdown.Ready(source, index, state, spans, blocks)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    PreparedMarkdown.Failed(source)
} catch (_: StackOverflowError) {
    PreparedMarkdown.Failed(source)
}

private const val OPENING_CHARS = 4096
