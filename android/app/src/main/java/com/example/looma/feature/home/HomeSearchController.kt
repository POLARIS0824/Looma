package com.example.looma.feature.home

import com.example.looma.data.note.NoteRepository
import com.example.looma.data.search.SearchHistoryRepository
import com.example.looma.data.search.SemanticIndexMissingException
import com.example.looma.data.search.SemanticModelMissingException
import com.example.looma.data.search.SemanticSearchRepository
import com.example.looma.data.search.SemanticWorkState
import com.example.looma.domain.search.SearchMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class HomeSearchController(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<HomeUiState>,
    private val noteRepository: NoteRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val semanticSearchRepository: SemanticSearchRepository,
) {
    private var searchJob: Job? = null
    private var semanticAvailabilityJob: Job? = null
    var resultsStale: Boolean = false
        private set

    fun clearSearch() {
        searchJob?.cancel()
    }

    fun markResultsStale() {
        resultsStale = true
    }

    fun clearResultsStale() {
        resultsStale = false
    }

    fun setQuery(value: String) {
        if (uiState.value.search.query == value) return
        uiState.update {
            it.copy(
                search = it.search.copy(
                    query = value,
                    results = emptyList(),
                    isSearching = false,
                    semanticSearch = if (it.search.semanticSearch == HomeSemanticSearch.SearchFailed) {
                        HomeSemanticSearch.Ready
                    } else {
                        it.search.semanticSearch
                    },
                ),
            )
        }
        if (!uiState.value.isLoading) {
            scheduleSearch(value)
        } else {
            clearSearch()
        }
    }

    fun setSemanticSearch(enabled: Boolean) {
        semanticAvailabilityJob?.cancel()
        if (!enabled) {
            selectSearchMode(HomeSemanticSearch.Off, debounce = false)
            unloadSemanticModel()
        } else if (!semanticSearchRepository.supported) {
            selectSearchMode(HomeSemanticSearch.Unsupported, debounce = false)
        } else {
            startSemanticAvailabilityObserver()
        }
    }

    private fun startSemanticAvailabilityObserver() {
        semanticAvailabilityJob?.cancel()
        uiState.update {
            it.copy(
                search = it.search.copy(
                    semanticSearch = HomeSemanticSearch.Checking,
                    results = emptyList(),
                    isSearching = false,
                ),
            )
        }
        semanticAvailabilityJob = scope.launch {
            var previousAvailability: HomeSemanticSearch? = null
            var wasIndexReady = false
            combine(
                semanticSearchRepository.modelState,
                semanticSearchRepository.hasIndex,
                semanticSearchRepository.indexState,
            ) { model, hasIndex, indexState ->
                val availability = semanticAvailability(model, hasIndex)
                val status = if (availability != HomeSemanticSearch.Ready) availability else when (indexState) {
                    SemanticWorkState.Queued, is SemanticWorkState.Running -> HomeSemanticSearch.Updating
                    SemanticWorkState.Ready -> HomeSemanticSearch.Ready
                    else -> HomeSemanticSearch.Partial
                }
                status to (indexState == SemanticWorkState.Ready)
            }.distinctUntilChanged().collect { (availability, isIndexReady) ->
                val finishedIndexing = availability == HomeSemanticSearch.Ready && isIndexReady && !wasIndexReady
                if (availability != previousAvailability || finishedIndexing) {
                    selectSearchMode(availability, debounce = false)
                }
                previousAvailability = availability
                wasIndexReady = isIndexReady
            }
        }
    }

    private fun selectSearchMode(status: HomeSemanticSearch, debounce: Boolean = true) {
        uiState.update {
            it.copy(
                search = it.search.copy(
                    semanticSearch = status,
                    results = emptyList(),
                    isSearching = false,
                ),
            )
        }
        if (!uiState.value.isLoading) {
            scheduleSearch(uiState.value.search.query, debounce = debounce, retryFailedSemantic = false)
        } else {
            clearSearch()
        }
    }

    fun retrySemanticSearch() {
        if (uiState.value.search.semanticSearch == HomeSemanticSearch.SearchFailed) {
            scheduleSearch(uiState.value.search.query, debounce = false, retryFailedSemantic = true)
        }
    }

    fun onSearchOpened() {
        val semantic = uiState.value.search.semanticSearch
        if (semantic != HomeSemanticSearch.Off &&
            semantic != HomeSemanticSearch.Unsupported &&
            semanticAvailabilityJob?.isActive != true
        ) {
            startSemanticAvailabilityObserver()
        }
    }

    fun onSearchClosed() {
        semanticAvailabilityJob?.cancel()
        setQuery("")
        unloadSemanticModel()
    }

    fun unloadSemanticModel() {
        scope.launch { semanticSearchRepository.releaseModel() }
    }

    fun scheduleSearch(
        value: String,
        debounce: Boolean = true,
        retryFailedSemantic: Boolean = false,
    ) {
        searchJob?.cancel()
        val keyword = value.trim()
        if (keyword.isEmpty()) return
        val semantic = uiState.value.search.semanticSearch
        if (semantic != HomeSemanticSearch.Off) {
            val canSearch = semantic.showsResults ||
                (retryFailedSemantic && semantic == HomeSemanticSearch.SearchFailed)
            if (canSearch) scheduleSemanticSearch(value, keyword, debounce)
            return
        }
        uiState.update { it.copy(search = it.search.copy(isSearching = true)) }
        searchJob = scope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val snapshotState = uiState.value
            val vaultId = snapshotState.vaultId
            val notes = snapshotState.notes
            val sort = snapshotState.sort
            val results = if (vaultId == null || notes.isEmpty()) {
                emptyList()
            } else {
                try {
                    withContext(Dispatchers.Default) {
                        val matches = noteRepository.search(
                            candidates = notes.map { it.toSearchableNote() },
                            query = keyword,
                        )
                        mergeSearchMatches(notes, matches).orderedBy(sort)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
            uiState.update { current ->
                if (current.search.query != value) {
                    current
                } else {
                    val sortedResults = if (sort == current.sort) results else results.orderedBy(current.sort)
                    current.copy(
                        search = current.search.copy(
                            results = sortedResults,
                            isSearching = false,
                        ),
                    )
                }
            }
        }
    }

    private fun scheduleSemanticSearch(value: String, keyword: String, debounce: Boolean) {
        val available = uiState.value.search.semanticSearch.takeIf { it.canSearch && it != HomeSemanticSearch.SearchFailed }
            ?: HomeSemanticSearch.Ready
        uiState.update {
            it.copy(
                search = it.search.copy(
                    semanticSearch = available,
                    isSearching = true,
                ),
            )
        }
        searchJob = scope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val notes = uiState.value.notes.associateBy { it.id }
            val (results, status) = try {
                semanticSearchRepository.search(keyword, notes.values.map { it.toSearchableNote() }).mapNotNull { hit ->
                    val note = notes[hit.id] ?: return@mapNotNull null
                    HomeSearchResult(
                        note = note,
                        titleMatch = null,
                        tagMatches = emptyList(),
                        matches = listOf(SearchMatch(hit.excerpt, emptyList())),
                        passage = hit.anchor,
                        section = hit.section,
                    )
                } to available
            } catch (e: CancellationException) {
                throw e
            } catch (_: SemanticModelMissingException) {
                emptyList<HomeSearchResult>() to HomeSemanticSearch.NeedsModel
            } catch (_: SemanticIndexMissingException) {
                emptyList<HomeSearchResult>() to HomeSemanticSearch.NeedsIndex
            } catch (_: Exception) {
                emptyList<HomeSearchResult>() to HomeSemanticSearch.SearchFailed
            } catch (_: LinkageError) {
                emptyList<HomeSearchResult>() to HomeSemanticSearch.Unsupported
            }
            uiState.update {
                if (it.search.query == value && it.search.semanticSearch.canSearch) {
                    it.copy(
                        search = it.search.copy(
                            results = results,
                            semanticSearch = status,
                            isSearching = false,
                        ),
                    )
                } else {
                    it
                }
            }
        }
    }

    fun recordSearch() {
        val query = uiState.value.search.query
        if (query.isBlank()) return
        scope.launch {
            try {
                searchHistoryRepository.record(query)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            loadRecentSearches()
        }
    }

    suspend fun loadRecentSearches() {
        val recent = try {
            searchHistoryRepository.recent()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        uiState.update { it.copy(search = it.search.copy(recentSearches = recent)) }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 180L
    }
}
