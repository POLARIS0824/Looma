package com.example.looma.domain.markdown

internal data class FrontmatterSpan(
    val bomLength: Int,
    val contentRange: IntRange?,
    val bodyStart: Int,
)

internal data class InlineTagSpan(
    val range: IntRange,
    val tag: String,
)

internal fun frontmatterSpan(source: String): FrontmatterSpan {
    val bomLength = if (source.startsWith('\uFEFF')) 1 else 0
    if (bomLength >= source.length) return FrontmatterSpan(bomLength, null, bomLength)
    var contentStart = -1
    var contentEnd = -1
    var bodyStart = bomLength
    var found = false
    forEachLineRange(source, bomLength) { start, end, next ->
        val line = source.subSequence(start, end)
        when {
            start == bomLength -> {
                if (!isFrontmatterOpening(line)) return@forEachLineRange false
                contentStart = next
                true
            }
            isFrontmatterClosing(line) -> {
                contentEnd = start
                bodyStart = next
                found = true
                false
            }
            else -> true
        }
    }
    return if (found) {
        FrontmatterSpan(bomLength, contentStart until contentEnd, bodyStart)
    } else {
        FrontmatterSpan(bomLength, null, bomLength)
    }
}

internal fun frontmatterEnd(source: String): Int = frontmatterSpan(source).bodyStart

internal fun isFrontmatterOpening(line: CharSequence): Boolean =
    line.startsWith(FRONTMATTER_FENCE) && isHorizontalSpaces(line, FRONTMATTER_FENCE.length)

internal fun isFrontmatterClosing(line: CharSequence): Boolean =
    (line.startsWith(FRONTMATTER_FENCE) || line.startsWith(YAML_DOCUMENT_END)) &&
        isHorizontalSpaces(line, FRONTMATTER_FENCE.length)

internal fun isValidObsidianTag(raw: String): Boolean {
    if (raw.isEmpty() || raw.startsWith('/') || raw.endsWith('/') || "//" in raw) return false
    var hasNonDigit = false
    for (ch in raw) {
        val type = Character.getType(ch)
        val valid = Character.isLetter(ch) ||
            Character.isDigit(ch) ||
            ch == '_' ||
            ch == '-' ||
            ch == '/' ||
            type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
        if (!valid) return false
        if (!Character.isDigit(ch)) hasNonDigit = true
    }
    return hasNonDigit
}

internal fun parseFrontmatterTags(frontmatter: CharSequence): Set<String> {
    if (frontmatter.isEmpty()) return emptySet()
    val lines = frontmatter.toString().split('\n').map { it.removeSuffix("\r") }
    val tags = linkedSetOf<String>()
    var index = 0
    while (index < lines.size) {
        val rawLine = lines[index]
        if (rawLine.isNotEmpty() && !rawLine[0].isWhitespace()) {
            val keyMatch = YAML_TAGS_KEY.matchAt(rawLine, 0)
            if (keyMatch != null) {
                val afterColon = rawLine.substring(keyMatch.range.last + 1)
                val cleanInline = stripYamlComment(afterColon).trim()
                when {
                    cleanInline.startsWith("[") -> {
                        val buffer = StringBuilder(cleanInline)
                        while (!hasClosingBracket(buffer) && index + 1 < lines.size) {
                            index++
                            buffer.append(' ').append(stripYamlComment(lines[index]).trim())
                        }
                        parseFlowArray(buffer.toString(), tags)
                    }
                    cleanInline.isNotEmpty() -> {
                        parseInlineScalarTags(cleanInline, tags)
                    }
                    else -> {
                        while (index + 1 < lines.size) {
                            val nextLine = lines[index + 1]
                            val cleanNext = stripYamlComment(nextLine).trimEnd()
                            if (cleanNext.isBlank()) {
                                index++
                                continue
                            }
                            if (!nextLine[0].isWhitespace() && !cleanNext.startsWith("-")) break
                            val trimmed = cleanNext.trimStart()
                            if (trimmed == "-" || trimmed.startsWith("- ") || trimmed.startsWith("-\t")) {
                                val item = if (trimmed.length > 1) trimmed.substring(1).trim() else ""
                                addParsedTag(item, tags)
                            }
                            index++
                        }
                    }
                }
            }
        }
        index++
    }
    return tags
}

internal fun findInlineTags(body: String, excludedRanges: List<IntRange>): List<InlineTagSpan> =
    INLINE_TAG_REGEX.findAll(body)
        .mapNotNull { match ->
            val start = match.range.first
            if (start > 0 && !body[start - 1].isWhitespace()) return@mapNotNull null
            val range = match.range
            if (overlapsAny(range, excludedRanges)) return@mapNotNull null
            val tag = match.groupValues[1]
            if (!isValidObsidianTag(tag)) return@mapNotNull null
            InlineTagSpan(range, tag)
        }
        .toList()

private fun overlapsAny(range: IntRange, sortedExcluded: List<IntRange>): Boolean {
    for (excluded in sortedExcluded) {
        if (excluded.first > range.last) break
        if (excluded.last >= range.first) return true
    }
    return false
}

private fun parseFlowArray(raw: String, out: MutableSet<String>) {
    val start = raw.indexOf('[')
    if (start < 0) return
    val end = findMatchingBracket(raw, start)
    val content = if (end > start) raw.substring(start + 1, end) else raw.substring(start + 1)
    for (item in splitUnquoted(content, ',')) {
        addParsedTag(item, out)
    }
}

private fun parseInlineScalarTags(raw: String, out: MutableSet<String>) {
    val parts = if (',' in raw) splitUnquoted(raw, ',') else raw.split(WHITESPACE_REGEX)
    for (part in parts) {
        addParsedTag(part, out)
    }
}

private fun addParsedTag(rawItem: String, out: MutableSet<String>) {
    val cleaned = unquoteYamlScalar(rawItem.trim()).trim().removePrefix("#")
    if (isValidObsidianTag(cleaned)) out += cleaned
}

private fun unquoteYamlScalar(raw: String): String {
    if (raw.length >= 2 && raw.startsWith('\'') && raw.endsWith('\'')) {
        return raw.substring(1, raw.length - 1).replace("''", "'")
    }
    if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
        val sb = StringBuilder()
        var i = 1
        while (i < raw.length - 1) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length - 1) {
                val next = raw[i + 1]
                when (next) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
    return raw
}

private fun splitUnquoted(text: String, delimiter: Char): List<String> {
    val parts = mutableListOf<String>()
    var quote: Char? = null
    var start = 0
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        when {
            quote == '\'' -> if (ch == '\'') {
                if (i + 1 < text.length && text[i + 1] == '\'') i++ else quote = null
            }
            quote == '"' -> when (ch) {
                '\\' -> i++
                '"' -> quote = null
            }
            ch == '\'' || ch == '"' -> quote = ch
            ch == delimiter -> {
                parts += text.substring(start, i)
                start = i + 1
            }
        }
        i++
    }
    parts += text.substring(start)
    return parts
}

private fun findMatchingBracket(text: String, openIndex: Int): Int {
    var quote: Char? = null
    var i = openIndex + 1
    while (i < text.length) {
        val ch = text[i]
        when {
            quote == '\'' -> if (ch == '\'') {
                if (i + 1 < text.length && text[i + 1] == '\'') i++ else quote = null
            }
            quote == '"' -> when (ch) {
                '\\' -> i++
                '"' -> quote = null
            }
            ch == '\'' || ch == '"' -> quote = ch
            ch == ']' -> return i
        }
        i++
    }
    return -1
}

private fun hasClosingBracket(text: CharSequence): Boolean = findMatchingBracket(text.toString(), 0) >= 0

private fun stripYamlComment(line: String): String {
    var quote: Char? = null
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            quote == '\'' -> if (ch == '\'') {
                if (i + 1 < line.length && line[i + 1] == '\'') i++ else quote = null
            }
            quote == '"' -> when (ch) {
                '\\' -> i++
                '"' -> quote = null
            }
            ch == '\'' || ch == '"' -> if (i == 0 || line[i - 1].isWhitespace() || line[i - 1] in "[:,") {
                quote = ch
            }
            ch == '#' && (i == 0 || line[i - 1].isWhitespace()) -> return line.substring(0, i)
        }
        i++
    }
    return line
}

private fun isHorizontalSpaces(line: CharSequence, from: Int): Boolean {
    for (i in from until line.length) {
        val ch = line[i]
        if (ch != ' ' && ch != '\t') return false
    }
    return true
}

private const val FRONTMATTER_FENCE = "---"
private const val YAML_DOCUMENT_END = "..."
private val YAML_TAGS_KEY = Regex("""tags?:""")
private val WHITESPACE_REGEX = Regex("""\s+""")
private val INLINE_TAG_REGEX = Regex("""#([\p{L}\p{M}\p{N}_/-]+)""")

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
