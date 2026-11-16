package com.example.looma.feature.home

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.search.SearchMatch
import com.example.looma.domain.search.searchNote
import java.util.Locale

data class HomeSearchResult(
    val note: HomeNoteItem,
    val titleMatch: SearchMatch?,
    val tagMatches: List<SearchMatch>,
    val matches: List<SearchMatch>,
) {
    val id: NoteId get() = note.id

    val listKey: String get() = note.listKey
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
            text = texts[note.id] ?: note.searchableText,
            query = query,
        )?.let { hit ->
            HomeSearchResult(note, hit.titleMatch, hit.tagMatches, hit.matches)
        }
    }
    .toList()

internal fun List<HomeSearchResult>.orderedBy(sort: HomeSort): List<HomeSearchResult> = when (sort) {
    HomeSort.LAST_MODIFIED -> sortedWith(
        compareByDescending<HomeSearchResult> { it.note.summary.modifiedAt }.thenBy { it.listKey },
    )

    HomeSort.TITLE -> sortedWith(
        compareBy<HomeSearchResult> { it.note.summary.title.lowercase(Locale.ROOT) }.thenBy { it.listKey },
    )

    HomeSort.TITLE_DESCENDING -> sortedWith(
        compareByDescending<HomeSearchResult> { it.note.summary.title.lowercase(Locale.ROOT) }.thenBy { it.listKey },
    )
}
