package com.example.looma.domain.search

import com.example.looma.domain.markdown.forEachLineRange
import com.example.looma.domain.markdown.frontmatterEnd
import com.example.looma.domain.markdown.isFrontmatterClosing
import com.example.looma.domain.markdown.isFrontmatterOpening
import com.example.looma.domain.model.NoteId
import java.io.BufferedReader

data class SearchMatch(
    val text: String,
    val ranges: List<IntRange>,
)

data class NoteSearchHit(
    val titleMatch: SearchMatch?,
    val tagMatches: List<SearchMatch>,
    val matches: List<SearchMatch>,
)

data class SearchableNote(
    val id: NoteId,
    val title: String,
    val tags: Set<String>,
)

data class NoteSearchMatch(
    val id: NoteId,
    val hit: NoteSearchHit,
)

fun searchNote(
    title: String,
    tags: Set<String>,
    text: String,
    query: String,
    maxMatches: Int = DEFAULT_MAX_MATCHES,
): NoteSearchHit? {
    val keyword = query.trim()
    if (keyword.isEmpty()) return null
    val titleMatch = matchOf(title, keyword)
    val tagMatches = matchTags(tags, keyword)
    val matches = bodyMatches(text, title.takeIf { titleMatch != null }?.trim(), keyword, maxMatches)
    if (titleMatch == null && tagMatches.isEmpty() && matches.isEmpty()) return null
    return NoteSearchHit(titleMatch, tagMatches, matches)
}

fun searchNote(
    title: String,
    tags: Set<String>,
    reader: BufferedReader,
    query: String,
    maxMatches: Int = DEFAULT_MAX_MATCHES,
    checkActive: () -> Unit = {},
): NoteSearchHit? {
    val keyword = query.trim()
    if (keyword.isEmpty()) return null
    val titleMatch = matchOf(title, keyword)
    val tagMatches = matchTags(tags, keyword)
    val matches = bodyMatches(reader, title.takeIf { titleMatch != null }?.trim(), keyword, maxMatches, checkActive)
    if (titleMatch == null && tagMatches.isEmpty() && matches.isEmpty()) return null
    return NoteSearchHit(titleMatch, tagMatches, matches)
}

private fun matchTags(tags: Set<String>, keyword: String): List<SearchMatch> {
    if (tags.isEmpty()) return emptyList()
    val results = ArrayList<SearchMatch>(minOf(tags.size, DEFAULT_MAX_MATCHES))
    for (tag in tags) {
        val match = matchOf("#$tag", keyword) ?: continue
        results.add(match)
        if (results.size >= DEFAULT_MAX_MATCHES) break
    }
    return results
}

private fun bodyMatches(text: String, matchedTitle: String?, keyword: String, limit: Int): List<SearchMatch> {
    if (limit <= 0) return emptyList()
    val bodyStart = frontmatterEnd(text)
    if (bodyStart >= text.length) return emptyList()
    if (text.indexOf(keyword, bodyStart, ignoreCase = true) < 0 && !hasInlineSyntax(text, bodyStart, text.length)) {
        return emptyList()
    }
    val results = ArrayList<SearchMatch>(minOf(limit, DEFAULT_MAX_MATCHES))
    val seen = HashSet<String>(minOf(limit, DEFAULT_MAX_MATCHES) * 2)
    forEachLineRange(text, bodyStart) { start, end, _ ->
        if (!isRepeatedHeading(text, start, end, matchedTitle)) {
            val first = indexOfInRange(text, start, end, keyword)
            if (first >= 0 || (end - start <= MAX_MARKDOWN_CONTEXT_CHARS && mayMatchAfterCleanup(text, start, end, keyword))) {
                val line = text.substring(start, end)
                val match = bodyMatch(line, keyword, first)
                if (match != null && seen.add(match.text)) {
                    results.add(match)
                    if (results.size >= limit) return@forEachLineRange false
                }
            }
        }
        true
    }
    return results
}

private fun bodyMatches(
    reader: BufferedReader,
    matchedTitle: String?,
    keyword: String,
    limit: Int,
    checkActive: () -> Unit,
): List<SearchMatch> {
    if (limit <= 0) return emptyList()
    fun nextLine(): String? {
        checkActive()
        return reader.readLine()
    }
    val firstLine = nextLine() ?: return emptyList()
    val results = ArrayList<SearchMatch>(minOf(limit, DEFAULT_MAX_MATCHES))
    val seen = HashSet<String>(minOf(limit, DEFAULT_MAX_MATCHES) * 2)

    fun accept(line: String): Boolean {
        if (isRepeatedHeading(line, 0, line.length, matchedTitle)) return false
        val first = line.indexOf(keyword, ignoreCase = true)
        if (first < 0 && (line.length > MAX_MARKDOWN_CONTEXT_CHARS || !mayMatchAfterCleanup(line, 0, line.length, keyword))) {
            return false
        }
        val match = bodyMatch(line, keyword, first) ?: return false
        if (seen.add(match.text)) {
            results.add(match)
            if (results.size >= limit) return true
        }
        return false
    }

    if (isFrontmatterOpening(firstLine)) {
        accept(firstLine)
        while (true) {
            val line = nextLine() ?: return results
            if (isFrontmatterClosing(line)) {
                results.clear()
                seen.clear()
                break
            }
            if (results.size < limit) accept(line)
        }
    } else if (accept(firstLine)) {
        return results
    }

    while (true) {
        val line = nextLine() ?: break
        if (accept(line)) return results
    }
    return results
}

private fun isRepeatedHeading(
    text: CharSequence,
    start: Int,
    end: Int,
    matchedTitle: String?,
): Boolean {
    if (matchedTitle == null || end - start < 2 || text[start] != '#' || text[start + 1] != ' ') {
        return false
    }
    var left = start + 2
    var right = end
    while (left < right && text[left].isWhitespace()) left++
    while (right > left && text[right - 1].isWhitespace()) right--
    return right - left == matchedTitle.length &&
        text.regionMatches(left, matchedTitle, 0, matchedTitle.length, ignoreCase = true)
}

private fun indexOfInRange(text: CharSequence, start: Int, end: Int, keyword: String): Int {
    val keywordLength = keyword.length
    val lastStart = end - keywordLength
    if (lastStart < start) return -1
    val firstChar = keyword[0]
    var index = start
    while (index <= lastStart) {
        if (text[index].equals(firstChar, ignoreCase = true) &&
            text.regionMatches(index, keyword, 0, keywordLength, ignoreCase = true)
        ) {
            return index - start
        }
        index++
    }
    return -1
}

private fun bodyMatch(line: String, keyword: String, first: Int): SearchMatch? {
    if (line.length <= MAX_MARKDOWN_CONTEXT_CHARS &&
        (first >= 0 || mayMatchAfterCleanup(line, 0, line.length, keyword))
    ) {
        matchOf(plainText(line), keyword)?.let { return it }
    }
    return matchOf(line, keyword, first)
}

internal fun hasInlineSyntax(text: CharSequence, start: Int, end: Int): Boolean {
    for (index in start until end) {
        when (text[index]) {
            '`', '[', '_', '*', '~' -> return true
        }
    }
    return false
}

private fun mayMatchAfterCleanup(
    text: CharSequence,
    start: Int,
    end: Int,
    keyword: String,
): Boolean {
    val keywordLength = keyword.length
    if (keywordLength <= 1 || keywordLength > end - start) return false

    val firstChar = keyword[0]
    var firstPos = -1
    for (index in start..end - keywordLength) {
        if (text[index].equals(firstChar, ignoreCase = true)) {
            firstPos = index
            break
        }
    }
    if (firstPos < 0) return false

    val lastChar = keyword[keywordLength - 1]
    var lastPos = -1
    for (index in end - 1 downTo firstPos + keywordLength - 1) {
        if (text[index].equals(lastChar, ignoreCase = true)) {
            lastPos = index
            break
        }
    }
    if (lastPos < 0) return false

    var interiorDelimiter = false
    for (index in firstPos + 1 until lastPos) {
        when (text[index]) {
            '`', '[', ']', '_', '*', '~' -> {
                interiorDelimiter = true
                break
            }
        }
    }
    if (!interiorDelimiter) return false

    var matched = 1
    for (index in firstPos + 1..lastPos) {
        if (text[index].equals(keyword[matched], ignoreCase = true)) {
            matched++
            if (matched == keywordLength) return true
        }
    }
    return false
}

private fun matchOf(
    line: String,
    keyword: String,
    first: Int = line.indexOf(keyword, ignoreCase = true),
): SearchMatch? {
    if (first < 0) return null
    val start = if (line.length <= MAX_MATCH_CHARS) 0 else {
        line.codePointBoundaryAtOrAfter((first - LEAD_CHARS).coerceAtLeast(0))
    }
    val end = line.codePointBoundaryAtOrAfter(
        maxOf(start + MAX_MATCH_CHARS, first + keyword.length).coerceAtMost(line.length),
    )
    val prefix = if (start > 0) ELLIPSIS else ""
    val suffix = if (end < line.length) ELLIPSIS else ""
    val context = line.substring(start, end)
    val ranges = buildList {
        var index = first - start
        while (index >= 0) {
            val matchEnd = index + keyword.length
            add(index + prefix.length until matchEnd + prefix.length)
            index = context.indexOf(keyword, matchEnd, ignoreCase = true)
        }
    }
    return SearchMatch(prefix + context + suffix, ranges)
}

private fun String.codePointBoundaryAtOrAfter(index: Int): Int =
    if (index in 1 until length && this[index - 1].isHighSurrogate() && this[index].isLowSurrogate()) {
        index + 1
    } else {
        index
    }

internal fun plainText(line: String): String {
    val firstNonSpace = line.firstNonWhitespaceIndex()
    if (firstNonSpace < 0) return ""
    val firstChar = line[firstNonSpace]
    if ((firstChar == '-' || firstChar == '*' || firstChar == '_') && RULE.matches(line)) return ""

    var current = line
    if ('[' in current) {
        if ("![[" in current) current = current.replace(WIKI_IMAGE, "")
        if ("![" in current && "](" in current) current = current.replace(MARKDOWN_IMAGE, "")
        if ("[[" in current) current = current.replace(WIKI_LINK) { match -> wikiLabel(match.groupValues[1]) }
        if ("](" in current) current = current.replace(MARKDOWN_LINK, "$1")
    }
    if (current.startsWithTaskOrListLeader()) {
        if ('[' in current) current = current.replace(CHECKBOX, "")
        if (current.startsWithTaskOrListLeader()) current = current.replace(LIST_MARKER, "")
    }
    if (current.startsWithHeadingLeader()) {
        current = current.replace(HEADING, "")
    }
    if (current.startsWithQuoteLeader()) {
        current = current.replace(QUOTE, "")
    }
    if ('*' in current) {
        if ("**" in current) current = current.replace(BOLD, "$1")
        if ('*' in current) current = current.replace(ITALIC, "$1")
    }
    if ("~~" in current) {
        current = current.replace(STRIKE, "$1")
    }
    if ('_' in current) {
        if ("__" in current) current = current.replace(BOLD_UNDERSCORE, "$1")
        if ('_' in current) current = current.replace(ITALIC_UNDERSCORE, "$1")
    }
    if ('`' in current) {
        current = current.replace(CODE_SPAN, "$1")
    }
    return current.trim()
}

private fun String.firstNonWhitespaceIndex(): Int {
    for (index in indices) {
        if (!this[index].isWhitespace()) return index
    }
    return -1
}

private fun String.startsWithTaskOrListLeader(): Boolean {
    val index = firstNonWhitespaceIndex()
    if (index < 0) return false
    val char = this[index]
    return char == '-' || char == '*' || char == '+' || char.isDigit()
}

private fun String.startsWithHeadingLeader(): Boolean {
    val index = firstNonWhitespaceIndex()
    return index in 0..3 && this[index] == '#'
}

private fun String.startsWithQuoteLeader(): Boolean {
    val index = firstNonWhitespaceIndex()
    return index >= 0 && this[index] == '>'
}

private fun wikiLabel(link: String): String {
    val alias = link.substringAfter('|', "").trim()
    val target = link.substringBefore('|')
    return alias.ifEmpty { target.substringBefore('#').ifBlank { target } }
}

internal const val DEFAULT_MAX_MATCHES = 3
internal const val MAX_MARKDOWN_CONTEXT_CHARS = 2_048
private const val MAX_MATCH_CHARS = 140
private const val LEAD_CHARS = 28
private const val ELLIPSIS = "\u2026"

private val WIKI_IMAGE = Regex("""!\[\[[^]]*]]""")
private val MARKDOWN_IMAGE = Regex("""!\[[^]]*]\([^)]*\)""")
private val WIKI_LINK = Regex("""\[\[([^]]*)]]""")
private val MARKDOWN_LINK = Regex("""\[([^]]*)]\([^)]*\)""")
private val CHECKBOX = Regex("""^\s*[-*+]\s+\[[ xX]]\s+""")
private val LIST_MARKER = Regex("""^\s*(?:[-*+]|\d+[.)])\s+""")
private val HEADING = Regex("""^\s{0,3}#{1,6}\s+""")
private val QUOTE = Regex("""^\s*>\s?""")
private val RULE = Regex("""^\s*(?:-{3,}|\*{3,}|_{3,})\s*$""")
private val BOLD = Regex("""\*\*([^*]+)\*\*""")
private val STRIKE = Regex("""~~([^~]+)~~""")
private val BOLD_UNDERSCORE = Regex("""__([^_]+)__""")
private val ITALIC = Regex("""(?<!\*)\*([^*]+)\*(?!\*)""")
private val ITALIC_UNDERSCORE = Regex("""(?<!_)_([^_]+)_(?!_)""")
private val CODE_SPAN = Regex("""`([^`]*)`""")
