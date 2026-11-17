package com.example.looma.ui.markdown

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions

internal class InlineCodeBackground(
    private val background: Color,
    private val corner: CornerSize,
) : ExtendedSpanPainter() {
    override fun decorate(
        span: SpanStyle,
        start: Int,
        end: Int,
        text: AnnotatedString,
        builder: AnnotatedString.Builder,
    ): SpanStyle {
        if (span.background != background) return span
        val content = text.text.substring(start, end)
        val first = content.indexOfFirst { !it.isWhitespace() }
        if (first >= 0) {
            val last = content.indexOfLast { !it.isWhitespace() }
            builder.addStringAnnotation(Tag, "", start + first, start + last + 1)
        }
        return span.copy(background = Color.Unspecified)
    }

    override fun decorate(
        linkAnnotation: LinkAnnotation,
        start: Int,
        end: Int,
        text: AnnotatedString,
        builder: AnnotatedString.Builder,
    ): LinkAnnotation = linkAnnotation

    override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
        val text = layoutResult.layoutInput.text
        val boxes = text.getStringAnnotations(Tag, 0, text.length).flatMap { span ->
            layoutResult.getBoundingBoxes(span.start, span.end)
        }
        return SpanDrawInstructions {
            for (box in boxes) {
                if (box.width <= 0f || box.height <= 0f) continue
                val inset = minOf(2.dp.toPx(), box.height / 4)
                val padding = 2.dp.toPx()
                val size = Size(box.width + padding * 2, box.height - inset * 2)
                drawRoundRect(
                    color = background,
                    topLeft = Offset(box.left - padding, box.top + inset),
                    size = size,
                    cornerRadius = CornerRadius(corner.toPx(size, this)),
                )
            }
        }
    }

    private companion object {
        const val Tag = "looma_inline_code_background"
    }
}
