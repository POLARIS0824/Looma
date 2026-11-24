package com.example.looma.domain.wiki

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.removeMarkdownSuffix

data class WikiNote(
    val id: NoteId,
    val title: String,
)

data class Backlink(
    val id: NoteId,
    val title: String,
)

data class WikiContext(
    private val targets: Map<String, NoteId> = emptyMap(),
    val backlinks: List<Backlink> = emptyList(),
    val linkables: List<WikiNote> = emptyList(),
    private val sourcePath: NotePath? = null,
    private val notes: List<WikiNote> = emptyList(),
    private val byPath: Map<String, WikiNote>? = null,
    private val byName: Map<String, List<WikiNote>>? = null,
) {
    internal fun localNote(destination: String): NoteId? {
        val path = LocalNoteLink.resolve(sourcePath ?: return null, destination) ?: return null
        return notes.firstOrNull { it.id.path == path }?.id
    }

    fun resolve(target: String): NoteId? {
        targets[normalizeWikiName(target)]?.let { return it }
        val from = sourcePath ?: return null
        return resolveWikiTarget(target, from, notes, byPath, byName)
    }

    companion object {
        val Empty = WikiContext()
    }
}

class WikiGraph(
    notes: List<WikiNote>,
    private val linksByNote: Map<NoteId, List<String>>,
) {
    private val byPath: Map<String, WikiNote>
    private val byName: Map<String, List<WikiNote>>
    private val all: List<WikiNote>
    private val titles: Map<NoteId, String>

    init {
        val sorted = notes.sortedWith(compareBy({ it.id.path.relativePath.length }, { it.id.path.relativePath }))
        byPath = buildMap(sorted.size) {
            for (note in sorted) {
                val key = normalizeWikiName(note.id.path.relativePath)
                if (key.isNotEmpty()) getOrPut(key) { note }
            }
        }
        byName = sorted.groupBy { baseNameOf(it.id.path) }
        all = sorted
        titles = notes.associate { it.id to it.title }
    }

    fun resolve(target: String, from: NotePath): NoteId? =
        resolveWikiTarget(target, from, all, byPath, byName)

    fun backlinksOf(note: NoteId): List<Backlink> = linksByNote.entries
        .asSequence()
        .filter { (source, targets) -> source != note && targets.any { resolve(it, source.path) == note } }
        .map { (source, _) -> Backlink(id = source, title = titles[source].orEmpty().ifBlank { baseNameOf(source.path) }) }
        .sortedWith(compareBy({ it.title.lowercase() }, { it.id.path.relativePath }))
        .toList()

    fun referrersOf(note: NoteId): List<NoteId> = linksByNote.entries
        .asSequence()
        .filter { (source, targets) -> source == note || targets.any { resolve(it, source.path) == note } }
        .map { it.key }
        .sortedBy { it.path.relativePath }
        .toList()

    fun context(note: NoteId): WikiContext = WikiContext(
        targets = linksByNote[note].orEmpty()
            .mapNotNull { target -> resolve(target, note.path)?.let { normalizeWikiName(target) to it } }
            .toMap(),
        backlinks = backlinksOf(note),
        linkables = all
            .asSequence()
            .filter { it.id != note }
            .map { WikiNote(it.id, titles[it.id].orEmpty().ifBlank { baseNameOf(it.id.path) }) }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.id.path.relativePath }))
            .toList(),
        sourcePath = note.path,
        notes = all,
        byPath = byPath,
        byName = byName,
    )

    companion object {
        internal fun baseNameOf(path: NotePath): String = normalizeWikiName(path.relativePath.substringAfterLast('/'))
    }
}

private fun resolveWikiTarget(
    target: String,
    from: NotePath,
    sortedNotes: List<WikiNote>,
    byPath: Map<String, WikiNote>? = null,
    byName: Map<String, List<WikiNote>>? = null,
): NoteId? {
    val key = normalizeWikiName(target)
    if (key.isEmpty()) return null
    if (byPath != null) {
        byPath[key]?.let { return it.id }
    } else {
        sortedNotes.firstOrNull { normalizeWikiName(it.id.path.relativePath) == key }?.let { return it.id }
    }

    val candidates = if ('/' in key) {
        val suffix = "/$key"
        sortedNotes.filter { normalizeWikiName(it.id.path.relativePath).endsWith(suffix) }
    } else if (byName != null) {
        byName[key].orEmpty()
    } else {
        sortedNotes.filter { WikiGraph.baseNameOf(it.id.path) == key }
    }
    if (candidates.isEmpty()) return null

    val sourceFolder = from.relativePath.substringBeforeLast('/', missingDelimiterValue = "")
    return (candidates.firstOrNull {
        it.id.path.relativePath.substringBeforeLast('/', missingDelimiterValue = "") == sourceFolder
    } ?: candidates.first()).id
}

fun normalizeWikiName(raw: String): String {
    val slashed = raw.trim().replace('\\', '/')
    val withoutSuffix = if (slashed.endsWith(".md", ignoreCase = true)) slashed.dropLast(3) else slashed
    return withoutSuffix.trim('/').lowercase()
}

fun wikiPathOf(path: NotePath): String =
    path.relativePath.replace('\\', '/').removeMarkdownSuffix()

fun wikiNameOf(path: NotePath): String = wikiPathOf(path).substringAfterLast('/')

fun retargetAfterRename(old: NotePath, new: NotePath): (String) -> String? {
    val oldName = normalizeWikiName(wikiNameOf(old))
    val oldPath = normalizeWikiName(wikiPathOf(old))
    return { target ->
        when (normalizeWikiName(target)) {
            oldName -> wikiNameOf(new)
            oldPath -> wikiPathOf(new)
            else -> null
        }
    }
}
