@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.home.HomeCrumb
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens
import kotlinx.coroutines.launch

private val SearchAppBarVerticalPadding = 4.dp

@Composable
internal fun HomeFolderTopBar(
    crumbs: List<HomeCrumb>,
    searchBarState: SearchBarState,
    scrollBehavior: TopAppBarScrollBehavior,
    vaultName: String,
    isGridMode: Boolean,
    onNavigateUp: () -> Unit,
    onToggleLayout: () -> Unit,
    onSortClick: () -> Unit,
) {
    val rootLabel = stringResource(R.string.home_breadcrumb_root)
    val labels = crumbs.map { it.label ?: rootLabel }
    val scope = rememberCoroutineScope()
    val searchPlaceholder = stringResource(R.string.home_search_placeholder, vaultName)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val bodyStyle = LoomaTokens.type.body
    val searchFieldPadding = TextFieldDefaults.contentPaddingWithoutLabel()
    val (searchFieldHeight, searchAppBarHeight) = remember(searchPlaceholder, bodyStyle, density, searchFieldPadding) {
        val searchLineHeight = textMeasurer.measure(
            text = searchPlaceholder,
            style = bodyStyle,
            maxLines = 1,
        ).size.height
        with(density) {
            val textPadding = searchFieldPadding.calculateTopPadding() + searchFieldPadding.calculateBottomPadding()
            val fieldHeight = maxOf(
                SearchBarDefaults.InputFieldHeight.roundToPx(),
                searchLineHeight + textPadding.roundToPx(),
            )
            fieldHeight.toDp() to (fieldHeight + 2 * SearchAppBarVerticalPadding.roundToPx()).toDp()
        }
    }
    TopAppBar(
        expandedHeight = searchAppBarHeight,
        title = {
            Text(
                text = labels.lastOrNull().orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        subtitle = {
            Text(
                text = labels.dropLast(1).joinToString(separator = " / "),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateUp) {
                LoomaIcon(
                    iconRes = R.drawable.ic_arrow_back,
                    contentDescription = stringResource(R.string.home_breadcrumb_back_description),
                )
            }
        },
        actions = {
            SearchBar(
                state = searchBarState,
                inputField = {
                    Box(contentAlignment = Alignment.Center) {
                        IconButton(onClick = { scope.launch { searchBarState.animateToExpanded() } }) {
                            LoomaIcon(
                                iconRes = R.drawable.ic_search,
                                contentDescription = searchPlaceholder,
                            )
                        }
                    }
                },
                modifier = Modifier
                    .height(searchFieldHeight)
                    .graphicsLayer { alpha = 1f - searchBarState.progress },
                shape = CircleShape,
                colors = SearchBarDefaults.colors(containerColor = Color.Transparent),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            )
            HomeListControls(
                isGridMode = isGridMode,
                onToggleLayout = onToggleLayout,
                onSortClick = onSortClick,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = LoomaTokens.colors.page,
            scrolledContainerColor = LoomaTokens.colors.page,
            navigationIconContentColor = LoomaTokens.colors.title,
            titleContentColor = LoomaTokens.colors.title,
            subtitleContentColor = LoomaTokens.colors.muted,
            actionIconContentColor = LoomaTokens.colors.title,
        ),
        scrollBehavior = scrollBehavior,
    )
}
