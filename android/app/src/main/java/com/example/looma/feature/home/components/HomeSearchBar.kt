@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.example.looma.feature.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarScrollBehavior
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.home.HomeNoteItem
import com.example.looma.feature.home.HomeUiState
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Composable
internal fun HomeSearchBar(
    state: HomeUiState,
    scrollBehavior: SearchBarScrollBehavior,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSearchResultClick: (NoteId) -> Unit,
    onMenuClick: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    val textFieldState = rememberTextFieldState(initialText = state.query)
    val searchBarState = rememberSearchBarState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val pageColor = LoomaTokens.colors.page
    val searchBarColors = SearchBarDefaults.containedColors(searchBarState)
    val appBarColors = SearchBarDefaults.appBarWithSearchColors(
        searchBarColors = searchBarColors,
        scrolledSearchBarContainerColor = LoomaTokens.colors.searchField,
        appBarContainerColor = pageColor,
        scrolledAppBarContainerColor = pageColor,
        appBarNavigationIconColor = LoomaTokens.colors.title,
        appBarActionIconColor = LoomaTokens.colors.title,
    )

    val collapse: () -> Unit = {
        keyboard?.hide()
        scope.launch { searchBarState.animateToCollapsed() }
    }

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect(onQueryChange)
    }

    LaunchedEffect(searchBarState, textFieldState) {
        snapshotFlow { searchBarState.currentValue }
            .filter { it == SearchBarValue.Collapsed }
            .collect { textFieldState.setTextAndPlaceCursorAtEnd("") }
    }

    val iconEnterStart = searchIconEnter(Alignment.Start)
    val iconExitStart = searchIconExit(Alignment.Start)
    val iconEnterEnd = searchIconEnter(Alignment.End)
    val iconExitEnd = searchIconExit(Alignment.End)

    val inputField: @Composable () -> Unit = {
        val expanded = searchBarState.currentValue == SearchBarValue.Expanded
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            modifier = Modifier.fillMaxWidth(),
            colors = searchBarColors.inputFieldColors,
            textStyle = LoomaTokens.type.body,
            onSearch = {
                onSearchSubmit()
                keyboard?.hide()
                scope.launch { searchBarState.animateToCollapsed() }
            },
            placeholder = {
                HomeSearchPlaceholder(
                    text = stringResource(R.string.home_search_placeholder, state.vaultName),
                )
            },
            leadingIcon = {
                AnimatedVisibility(
                    visible = expanded,
                    enter = iconEnterStart,
                    exit = iconExitStart,
                ) {
                    IconButton(onClick = collapse) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_arrow_back,
                            contentDescription = stringResource(R.string.home_close_search_description),
                        )
                    }
                }
            },
            trailingIcon = {
                AnimatedVisibility(
                    visible = expanded && textFieldState.text.isNotEmpty(),
                    enter = iconEnterEnd,
                    exit = iconExitEnd,
                ) {
                    IconButton(
                        onClick = { textFieldState.setTextAndPlaceCursorAtEnd("") },
                    ) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_close,
                            contentDescription = stringResource(R.string.home_clear_search_description),
                        )
                    }
                }
            },
        )
    }

    AppBarWithSearch(
        state = searchBarState,
        scrollBehavior = scrollBehavior,
        colors = appBarColors,
        inputField = inputField,
        navigationIcon = {
            Box(Modifier.graphicsLayer { alpha = 1f - searchBarState.progress }) {
                IconButton(onClick = onMenuClick) {
                    LoomaIcon(
                        iconRes = R.drawable.ic_menu,
                        contentDescription = stringResource(R.string.home_menu_description),
                    )
                }
            }
        },
        actions = {
            Box(
                Modifier
                    .graphicsLayer { alpha = 1f - searchBarState.progress }
                    .padding(start = 8.dp, end = 8.dp),
            ) {
                HomeAvatarButton(onClick = onAvatarClick)
            }
        },
    )

    ExpandedFullScreenContainedSearchBar(
        state = searchBarState,
        inputField = inputField,
        colors = searchBarColors,
    ) {
        HomeSearchResults(
            query = state.query,
            notes = state.searchResults,
            recentSearches = state.recentSearches,
            onNoteClick = { noteId ->
                collapse()
                onSearchResultClick(noteId)
            },
            onRecentSearchClick = { query ->
                textFieldState.setTextAndPlaceCursorAtEnd(query)
            },
        )
    }
}

@Composable
private fun HomeSearchPlaceholder(text: String) {
    Text(
        text = text,
        modifier = Modifier.clearAndSetSemantics {},
        style = LoomaTokens.type.body,
        color = LoomaTokens.colors.muted,
        maxLines = 1,
    )
}

@Composable
private fun HomeAvatarButton(onClick: () -> Unit) {
    val avatarDescription = stringResource(R.string.home_avatar_description)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .semantics { contentDescription = avatarDescription },
        shape = CircleShape,
        color = LoomaTokens.colors.accentContainer,
        border = BorderStroke(2.dp, LoomaTokens.colors.avatarRing),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.home_avatar_letter),
                style = LoomaTokens.type.avatar,
            )
        }
    }
}

@Composable
private fun searchIconEnter(align: Alignment.Horizontal): EnterTransition {
    val motion = MaterialTheme.motionScheme
    return fadeIn(motion.fastEffectsSpec()) +
        expandHorizontally(expandFrom = align, animationSpec = motion.fastSpatialSpec<IntSize>())
}

@Composable
private fun searchIconExit(align: Alignment.Horizontal): ExitTransition {
    val motion = MaterialTheme.motionScheme
    return fadeOut(motion.fastEffectsSpec()) +
        shrinkHorizontally(shrinkTowards = align, animationSpec = motion.fastSpatialSpec<IntSize>())
}

@Composable
internal fun HomeSearchResults(
    query: String,
    notes: List<HomeNoteItem>,
    onNoteClick: (NoteId) -> Unit,
    recentSearches: List<String> = emptyList(),
    onRecentSearchClick: (String) -> Unit = {},
) {
    when {
        query.isBlank() && recentSearches.isNotEmpty() -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.home_recent_searches),
                    style = LoomaTokens.type.sectionTitle,
                    color = LoomaTokens.colors.title,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(recentSearches) { search ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = search,
                            style = LoomaTokens.type.cardTitle,
                            color = LoomaTokens.colors.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { LoomaIcon(R.drawable.ic_history, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRecentSearchClick(search) },
                )
            }
        }

        query.isBlank() -> HomeSearchHint(stringResource(R.string.home_search_hint))
        notes.isEmpty() -> HomeSearchHint(stringResource(R.string.home_search_no_match))
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(notes, key = { it.listKey }) { note ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = note.summary.title,
                            style = LoomaTokens.type.cardTitle,
                            color = LoomaTokens.colors.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = note.summary.excerpt.ifEmpty { note.id.path.relativePath },
                            style = LoomaTokens.type.cardBody,
                            color = LoomaTokens.colors.body,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { LoomaIcon(R.drawable.ic_note, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = stringResource(R.string.home_open_note_label)) {
                            onNoteClick(note.id)
                        },
                )
            }
        }
    }
}

@Composable
private fun HomeSearchHint(text: String) {
    Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
        Text(text, style = LoomaTokens.type.supporting, color = LoomaTokens.colors.muted)
    }
}
