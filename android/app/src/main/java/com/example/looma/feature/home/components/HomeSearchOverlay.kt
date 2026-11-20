package com.example.looma.feature.home.components

import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.home.HomeSearchActions
import com.example.looma.feature.home.HomeSearchUiState
import com.example.looma.feature.home.HomeSemanticSearch
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@Stable
internal class HomeSearchState(
    val bar: SearchBarState,
    val field: TextFieldState,
    val collapse: () -> Unit,
) {
    var keyboardDismissRequest by mutableIntStateOf(0)
        private set

    fun submit(onSearchSubmit: () -> Unit) {
        onSearchSubmit()
        if (bar.currentValue == SearchBarValue.Expanded) keyboardDismissRequest++
    }
}

@Composable
internal fun rememberHomeSearchState(initialQuery: String): HomeSearchState {
    val bar = rememberSearchBarState()
    val field = rememberTextFieldState(initialText = initialQuery)
    val scope = rememberCoroutineScope()
    val keyboard by rememberUpdatedState(LocalSoftwareKeyboardController.current)
    return remember(bar, field, scope) {
        HomeSearchState(bar, field, collapse = {
            keyboard?.hide()
            scope.launch { bar.animateToCollapsed() }
        })
    }
}

@Composable
internal fun HomeSearchOverlay(
    searchState: HomeSearchUiState,
    isGridMode: Boolean,
    actions: HomeSearchActions,
    search: HomeSearchState,
    inputField: @Composable () -> Unit,
    homeVisible: Boolean,
) {
    val searchBarState = search.bar
    val textFieldState = search.field
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val isSearchExpanded =
        searchBarState.currentValue == SearchBarValue.Expanded ||
            searchBarState.targetValue == SearchBarValue.Expanded

    LaunchedEffect(isSearchExpanded, homeVisible) {
        if (isSearchExpanded || !homeVisible) {
            focusManager.clearFocus(force = true)
        }
    }

    val updateQuery by rememberUpdatedState(actions.onQueryChange)
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { updateQuery(it) }
    }

    var hasAutoFocusedSearch by remember(searchBarState) {
        mutableStateOf(searchBarState.currentValue == SearchBarValue.Expanded)
    }
    val notifySearchOpened by rememberUpdatedState(actions.onSearchOpened)
    val notifySearchClosed by rememberUpdatedState(actions.onSearchClosed)
    LaunchedEffect(searchBarState, textFieldState) {
        snapshotFlow { searchBarState.currentValue }
            .drop(1)
            .collect { value ->
                when (value) {
                    SearchBarValue.Expanded -> notifySearchOpened()
                    SearchBarValue.Collapsed -> {
                        hasAutoFocusedSearch = false
                        textFieldState.setTextAndPlaceCursorAtEnd("")
                        notifySearchClosed()
                    }
                }
            }
    }

    val resultsFocusRequester = remember { FocusRequester() }
    val keyboardDismissRequest = search.keyboardDismissRequest
    LaunchedEffect(keyboardDismissRequest) {
        if (keyboardDismissRequest > 0) resultsFocusRequester.requestFocus()
    }

    if (homeVisible) {
        ExpandedFullScreenSearchBar(
            state = searchBarState,
            inputField = {
                var blockMountAutoFocus by remember { mutableStateOf(hasAutoFocusedSearch) }
                LaunchedEffect(Unit) {
                    hasAutoFocusedSearch = true
                    withFrameNanos { }
                    blockMountAutoFocus = false
                }
                Box(
                    Modifier
                        .padding(horizontal = 8.dp * searchBarState.progress)
                        .focusProperties {
                            onEnter = {
                                if (blockMountAutoFocus) {
                                    blockMountAutoFocus = false
                                    if (!resultsFocusRequester.requestFocus()) {
                                        cancelFocusChange()
                                    }
                                }
                            }
                        }
                        .focusGroup(),
                ) {
                    inputField()
                }
            },
            collapsedShape = CircleShape,
            colors = homeSearchBarColors(searchBarState.progress),
        ) {
            HomeSearchPanel(
                progress = searchBarState.progress,
                focusRequester = resultsFocusRequester,
            ) {
                Column(Modifier.fillMaxSize()) {
                    val semantic = searchState.semanticSearch
                    HomeSemanticSearchControls(
                        state = semantic,
                        onToggle = actions.onSemanticSearchToggle,
                        onManage = {
                            keyboard?.hide()
                            actions.onManageSemanticSearch()
                        },
                        onRetry = actions.onRetrySemanticSearch,
                    )
                    if (semantic == HomeSemanticSearch.Off || semantic == HomeSemanticSearch.Ready) {
                        HomeSearchResults(
                            query = searchState.query,
                            results = searchState.results,
                            isGridMode = isGridMode,
                            isSearching = searchState.isSearching,
                            recentSearches = searchState.recentSearches,
                            semanticSearch = semantic == HomeSemanticSearch.Ready,
                            onNoteClick = { result ->
                                keyboard?.hide()
                                actions.onSearchResultClick(result)
                            },
                            onRecentSearchClick = { query ->
                                textFieldState.setTextAndPlaceCursorAtEnd(query)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeSearchField(
    vaultName: String,
    search: HomeSearchState,
    onSearchSubmit: () -> Unit,
    showCollapsedActions: Boolean,
    collapsedActions: @Composable () -> Unit,
) {
    val searchBarState = search.bar
    val textFieldState = search.field
    val expanded = searchBarState.currentValue == SearchBarValue.Expanded
    SearchBarDefaults.InputField(
        textFieldState = textFieldState,
        searchBarState = searchBarState,
        modifier = Modifier.fillMaxWidth(),
        colors = homeSearchInputFieldColors(),
        textStyle = MaterialTheme.typography.bodyLarge,
        onSearch = { search.submit(onSearchSubmit) },
        placeholder = {
            HomeSearchPlaceholder(
                text = stringResource(R.string.home_search_placeholder, vaultName),
            )
        },
        leadingIcon = {
            AnimatedVisibility(
                visible = expanded,
                enter = searchIconEnter(Alignment.Start),
                exit = searchIconExit(Alignment.Start),
            ) {
                IconButton(onClick = search.collapse, shapes = IconButtonDefaults.shapes()) {
                    LoomaIcon(
                        iconRes = R.drawable.ic_arrow_back,
                        contentDescription = stringResource(R.string.home_close_search_description),
                    )
                }
            }
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(
                    visible = !expanded && showCollapsedActions,
                    enter = searchIconEnter(Alignment.End),
                    exit = searchIconExit(Alignment.End),
                ) {
                    collapsedActions()
                }
                AnimatedVisibility(
                    visible = expanded && textFieldState.text.isNotEmpty(),
                    enter = searchIconEnter(Alignment.End),
                    exit = searchIconExit(Alignment.End),
                ) {
                    IconButton(onClick = { textFieldState.setTextAndPlaceCursorAtEnd("") }, shapes = IconButtonDefaults.shapes()) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_close,
                            contentDescription = stringResource(R.string.home_clear_search_description),
                        )
                    }
                }
            }
        },
    )
}

@Composable
internal fun homeSearchBarColors(progress: Float) = SearchBarDefaults.colors(
    containerColor = lerp(LoomaTokens.colors.searchField, LoomaTokens.colors.page, progress.coerceIn(0f, 1f)),
    dividerColor = MaterialTheme.colorScheme.outlineVariant,
    inputFieldColors = homeSearchInputFieldColors(),
)

@Composable
private fun homeSearchInputFieldColors() = SearchBarDefaults.inputFieldColors(
    focusedContainerColor = LoomaTokens.colors.searchField,
    unfocusedContainerColor = LoomaTokens.colors.searchField,
    disabledContainerColor = LoomaTokens.colors.searchField,
)

@Composable
private fun HomeSearchPlaceholder(text: String) {
    Text(
        text = text,
        modifier = Modifier.clearAndSetSemantics {},
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun HomeSearchPanel(
    progress: Float,
    focusRequester: FocusRequester,
    content: @Composable () -> Unit,
) {
    val lift = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .graphicsLayer { translationY = (1f - lift) * 12.dp.toPx() },
    ) {
        content()
    }
}

@Composable
private fun searchIconEnter(align: Alignment.Horizontal): EnterTransition {
    val motion = MaterialTheme.motionScheme
    return fadeIn(motion.fastEffectsSpec()) +
        expandHorizontally(expandFrom = align, animationSpec = motion.fastSpatialSpec())
}

@Composable
private fun searchIconExit(align: Alignment.Horizontal): ExitTransition {
    val motion = MaterialTheme.motionScheme
    return fadeOut(motion.fastEffectsSpec()) +
        shrinkHorizontally(shrinkTowards = align, animationSpec = motion.fastSpatialSpec())
}
