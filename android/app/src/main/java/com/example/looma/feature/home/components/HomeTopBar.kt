@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBarScrollBehavior
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.example.looma.R
import com.example.looma.feature.home.HomeCrumb
import com.example.looma.feature.home.HomeSelection
import com.example.looma.feature.home.HomeSelectionActions
import com.example.looma.feature.home.HomeTopBarMode
import com.example.looma.ui.components.LoomaIcon

@Composable
internal fun HomeTopBarHost(
    mode: HomeTopBarMode,
    selection: HomeSelection,
    selectedNoteCount: Int,
    crumbs: List<HomeCrumb>,
    vaultName: String,
    isGridMode: Boolean,
    searchBarState: SearchBarState,
    inputField: @Composable () -> Unit,
    rootScrollBehavior: SearchBarScrollBehavior,
    folderScrollBehavior: TopAppBarScrollBehavior,
    onMenuClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onNavigateUp: () -> Unit,
    onToggleLayout: () -> Unit,
    onSortClick: () -> Unit,
    selectionActions: HomeSelectionActions,
) {
    when (mode) {
        HomeTopBarMode.Selection -> HomeSelectionBar(
            selection = selection,
            selectedNoteCount = selectedNoteCount,
            actions = selectionActions,
        )

        HomeTopBarMode.Root -> HomeRootTopBar(
            searchBarState = searchBarState,
            inputField = inputField,
            scrollBehavior = rootScrollBehavior,
            onMenuClick = onMenuClick,
            onAvatarClick = onAvatarClick,
        )

        HomeTopBarMode.Folder -> HomeFolderTopBar(
            crumbs = crumbs,
            searchBarState = searchBarState,
            scrollBehavior = folderScrollBehavior,
            vaultName = vaultName,
            isGridMode = isGridMode,
            onNavigateUp = onNavigateUp,
            onToggleLayout = onToggleLayout,
            onSortClick = onSortClick,
        )
    }
}

@Composable
internal fun HomeListControls(
    isGridMode: Boolean,
    onToggleLayout: () -> Unit,
    onSortClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggleLayout) {
            LoomaIcon(
                iconRes = if (isGridMode) R.drawable.ic_view_agenda else R.drawable.ic_grid,
                contentDescription = stringResource(
                    if (isGridMode) R.string.home_switch_to_list_description else R.string.home_switch_to_grid_description,
                ),
            )
        }
        IconButton(onClick = onSortClick) {
            LoomaIcon(
                iconRes = R.drawable.ic_swap_vert,
                contentDescription = stringResource(R.string.home_sort_description),
            )
        }
    }
}
