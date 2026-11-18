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
            val parsed = parseTopLevelYamlKeyValue(rawLine)
            if (parsed != null && (parsed.first.equals("tags", ignoreCase = true) || parsed.first.equals("tag", ignoreCase = true))) {
                val cleanInline = stripYamlComment(parsed.second).trim()
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
                            val trimmed = cleanNext.trimStart()
                            val isSequenceItem = trimmed == "-" || trimmed.startsWith("- ") || trimmed.startsWith("-\t")
                            if (!nextLine[0].isWhitespace() && !isSequenceItem) break
                            if (isSequenceItem) {
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
    if (isUnquotedYamlNull(raw)) return
    val unquoted = tryUnquoteSingleYamlScalar(raw)
    if (unquoted != null) {
        for (part in unquoted.split(SCALAR_TAG_SPLIT_REGEX)) {
            val cleaned = part.trim().removePrefix("#")
            if (isValidObsidianTag(cleaned)) out += cleaned
        }
        return
    }
    val parts = if (',' in raw) splitUnquoted(raw, ',') else raw.split(WHITESPACE_REGEX)
    for (part in parts) {
        addParsedTag(part, out)
    }
}

private fun addParsedTag(rawItem: String, out: MutableSet<String>) {
    val trimmed = rawItem.trim()
    if (trimmed.isEmpty() || isUnquotedYamlNull(trimmed)) return
    val unquoted = tryUnquoteSingleYamlScalar(trimmed) ?: trimmed
    val cleaned = unquoted.trim().removePrefix("#")
    if (isValidObsidianTag(cleaned)) out += cleaned
}

private fun parseTopLevelYamlKeyValue(line: String): Pair<String, String>? {
    if (line.isEmpty() || line[0] == '#') return null
    if (line[0] == '\'' || line[0] == '"') {
        val (key, afterQuote) = parseQuotedYamlScalar(line, 0) ?: return null
        var pos = afterQuote
        while (pos < line.length && (line[pos] == ' ' || line[pos] == '\t')) pos++
        if (pos >= line.length || line[pos] != ':') return null
        return key to line.substring(pos + 1)
    }
    for (i in line.indices) {
        if (line[i] == ':' && (i + 1 == line.length || line[i + 1] == ' ' || line[i + 1] == '\t')) {
            val key = line.substring(0, i).trimEnd(' ', '\t')
            if (key.isEmpty()) return null
            return key to line.substring(i + 1)
        }
    }
    return null
}

private fun isUnquotedYamlNull(raw: String): Boolean =
    raw == "~" || raw.equals("null", ignoreCase = true)

private fun tryUnquoteSingleYamlScalar(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.length < 2 || (trimmed[0] != '\'' && trimmed[0] != '"')) return null
    val (value, end) = parseQuotedYamlScalar(trimmed, 0) ?: return null
    return if (end == trimmed.length) value else null
}

private fun parseQuotedYamlScalar(text: String, startIndex: Int): Pair<String, Int>? {
    val quote = text[startIndex]
    if (quote == '\'') {
        val sb = StringBuilder()
        var i = startIndex + 1
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\'') {
                if (i + 1 < text.length && text[i + 1] == '\'') {
                    sb.append('\'')
                    i += 2
                } else {
                    return sb.toString() to (i + 1)
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return null
    }
    if (quote == '"') {
        val sb = StringBuilder()
        var i = startIndex + 1
        while (i < text.length) {
            val ch = text[i]
            when (ch) {
                '"' -> return sb.toString() to (i + 1)
                '\\' -> {
                    if (i + 1 >= text.length) return null
                    when (val next = text[i + 1]) {
                        '0' -> { sb.append('\u0000'); i += 2 }
                        'a' -> { sb.append('\u0007'); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'v' -> { sb.append('\u000B'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        'e' -> { sb.append('\u001B'); i += 2 }
                        ' ' -> { sb.append(' '); i += 2 }
                        '"', '\\', '/' -> { sb.append(next); i += 2 }
                        'N' -> { sb.append('\u0085'); i += 2 }
                        '_' -> { sb.append('\u00A0'); i += 2 }
                        'L' -> { sb.append('\u2028'); i += 2 }
                        'P' -> { sb.append('\u2029'); i += 2 }
                        'x' -> {
                            if (i + 4 > text.length) return null
                            val code = text.substring(i + 2, i + 4).toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            i += 4
                        }
                        'u' -> {
                            if (i + 6 > text.length) return null
                            val code = text.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            i += 6
                        }
                        'U' -> {
                            if (i + 10 > text.length) return null
                            val code = text.substring(i + 2, i + 10).toIntOrNull(16) ?: return null
                            if (!Character.isValidCodePoint(code)) return null
                            sb.appendCodePoint(code)
                            i += 10
                        }
                        else -> { sb.append(next); i += 2 }
                    }
                }
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        return null
    }
    return null
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
private val WHITESPACE_REGEX = Regex("""\s+""")
private val SCALAR_TAG_SPLIT_REGEX = Regex("""[\s,]+""")
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
