package com.example.looma.domain.search

import com.example.looma.domain.markdown.frontmatterEnd

data class SearchMatch(
    val text: String,
    val ranges: List<IntRange>,
)

data class NoteSearchHit(
    val titleMatch: SearchMatch?,
    val tagMatches: List<SearchMatch>,
    val matches: List<SearchMatch>,
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
    val tagMatches = tags.asSequence()
        .mapNotNull { tag -> matchOf("#$tag", keyword) }
        .take(DEFAULT_MAX_MATCHES)
        .toList()
    val matches = bodyMatches(text, title.takeIf { titleMatch != null }?.trim(), keyword, maxMatches)
    if (titleMatch == null && tagMatches.isEmpty() && matches.isEmpty()) return null
    return NoteSearchHit(titleMatch, tagMatches, matches)
}

private fun bodyMatches(text: String, matchedTitle: String?, keyword: String, limit: Int): List<SearchMatch> {
    if (limit <= 0) return emptyList()
    return text.substring(frontmatterEnd(text))
        .lineSequence()
        .filterNot { line ->
            matchedTitle != null &&
                line.startsWith("# ") &&
                line.substring(2).trim().equals(matchedTitle, ignoreCase = true)
        }
        .mapNotNull { line -> bodyMatch(line, keyword) }
        .distinctBy { it.text }
        .take(limit)
        .toList()
}

private fun bodyMatch(line: String, keyword: String): SearchMatch? {
    val first = line.indexOf(keyword, ignoreCase = true)
    if (line.length <= MAX_MARKDOWN_CONTEXT_CHARS &&
        (first >= 0 || mayMatchAfterCleanup(line, keyword))
    ) {
        matchOf(plainText(line), keyword)?.let { return it }
    }
    return matchOf(line, keyword, first)
}

private fun mayMatchAfterCleanup(line: String, keyword: String): Boolean {
    if (keyword.length > line.length || line.none { it in "`[_*~" }) return false
    var matched = 0
    for (char in line) {
        if (char.equals(keyword[matched], ignoreCase = true)) matched++
        if (matched == keyword.length) return true
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

private fun plainText(line: String): String {
    if (RULE.matches(line)) return ""
    return line
        .replace(WIKI_IMAGE, "")
        .replace(MARKDOWN_IMAGE, "")
        .replace(WIKI_LINK) { match -> wikiLabel(match.groupValues[1]) }
        .replace(MARKDOWN_LINK, "$1")
        .replace(CHECKBOX, "")
        .replace(LIST_MARKER, "")
        .replace(HEADING, "")
        .replace(QUOTE, "")
        .replace(BOLD, "$1")
        .replace(STRIKE, "$1")
        .replace(BOLD_UNDERSCORE, "$1")
        .replace(ITALIC, "$1")
        .replace(ITALIC_UNDERSCORE, "$1")
        .replace(CODE_SPAN, "$1")
        .trim()
}

private fun wikiLabel(link: String): String {
    val alias = link.substringAfter('|', "").trim()
    val target = link.substringBefore('|')
    return alias.ifEmpty { target.substringBefore('#').ifBlank { target } }
}

private const val DEFAULT_MAX_MATCHES = 3
private const val MAX_MARKDOWN_CONTEXT_CHARS = 2_048
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
