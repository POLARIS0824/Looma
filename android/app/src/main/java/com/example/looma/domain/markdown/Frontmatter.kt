package com.example.looma.domain.markdown

internal fun frontmatterEnd(source: String): Int {
    if (!source.startsWith(FRONTMATTER_FENCE)) return 0
    if (source.length > FRONTMATTER_FENCE.length && source[FRONTMATTER_FENCE.length] !in "\r\n") return 0
    var bodyStart = 0
    forEachLineRange(source, 0) { start, end, next ->
        when {
            start == 0 -> end == FRONTMATTER_FENCE.length
            isFrontmatterClosing(source.subSequence(start, end)) -> {
                bodyStart = next
                false
            }
            else -> true
        }
    }
    return bodyStart
}

internal fun isFrontmatterOpening(line: String): Boolean = line == FRONTMATTER_FENCE

internal fun isFrontmatterClosing(line: CharSequence): Boolean = line.trim().contentEquals(FRONTMATTER_FENCE)

private const val FRONTMATTER_FENCE = "---"

internal inline fun forEachLineRange(
    text: String,
    startOffset: Int,
    visit: (start: Int, end: Int, next: Int) -> Boolean,
) {
    var start = startOffset
    while (start < text.length) {
        var end = start
        while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
        val next = when {
            end == text.length -> end
            text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n' -> end + 2
            else -> end + 1
        }
        if (!visit(start, end, next)) return
        start = next
    }
}
