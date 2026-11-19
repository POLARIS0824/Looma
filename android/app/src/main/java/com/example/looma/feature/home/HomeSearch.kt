package com.example.looma.feature.home

import com.example.looma.data.search.SemanticWorkState
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.search.NoteSearchMatch
import com.example.looma.domain.search.PassageAnchor
import com.example.looma.domain.search.SearchMatch
import com.example.looma.domain.search.SearchableNote
import com.example.looma.domain.search.searchNote

data class HomeSearchResult(
    val note: HomeNoteItem,
    val titleMatch: SearchMatch?,
    val tagMatches: List<SearchMatch>,
    val matches: List<SearchMatch>,
    val passage: PassageAnchor? = null,
    val section: String = "",
) {
    val id: NoteId get() = note.id

    val listKey: String get() = note.listKey
}

internal fun HomeNoteItem.toSearchableNote(): SearchableNote = SearchableNote(
    id = id,
    title = summary.title,
    tags = summary.tags,
)

internal fun mergeSearchMatches(
    notes: List<HomeNoteItem>,
    matches: List<NoteSearchMatch>,
): List<HomeSearchResult> {
    if (matches.isEmpty() || notes.isEmpty()) return emptyList()
    val notesById = notes.associateBy { it.id }
    return matches.mapNotNull { match ->
        val note = notesById[match.id] ?: return@mapNotNull null
        HomeSearchResult(
            note = note,
            titleMatch = match.hit.titleMatch,
            tagMatches = match.hit.tagMatches,
            matches = match.hit.matches,
        )
    }
}

internal fun searchNotes(
    notes: List<HomeNoteItem>,
    texts: Map<NoteId, String>,
    query: String,
    checkCancellation: () -> Unit = {},
): List<HomeSearchResult> = notes.asSequence()
    .onEach { checkCancellation() }
    .distinctBy { it.id }
    .mapNotNull { note ->
        searchNote(
            title = note.summary.title,
            tags = note.summary.tags,
            text = texts[note.id] ?: note.markdownSnippet,
            query = query,
        )?.let { hit ->
            HomeSearchResult(note, hit.titleMatch, hit.tagMatches, hit.matches)
        }
    }
    .toList()

internal fun List<HomeSearchResult>.orderedBy(sort: HomeSort): List<HomeSearchResult> =
    orderedByNote(sort) { it.note }

internal fun semanticAvailability(
    model: SemanticWorkState,
    hasIndex: Boolean,
): HomeSemanticSearch = when {
    model != SemanticWorkState.Ready -> HomeSemanticSearch.NeedsModel
    !hasIndex -> HomeSemanticSearch.NeedsIndex
    else -> HomeSemanticSearch.Ready
}
