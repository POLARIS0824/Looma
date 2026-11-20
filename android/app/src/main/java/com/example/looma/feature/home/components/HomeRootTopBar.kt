@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.example.looma.feature.home.components

import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarScrollBehavior
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun HomeRootTopBar(
    searchBarState: SearchBarState,
    inputField: @Composable () -> Unit,
    scrollBehavior: SearchBarScrollBehavior,
    onMenuClick: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    val pageColor = LoomaTokens.colors.page
    AppBarWithSearch(
        state = searchBarState,
        scrollBehavior = scrollBehavior,
        colors = SearchBarDefaults.appBarWithSearchColors(
            searchBarColors = homeSearchBarColors(),
            scrolledSearchBarContainerColor = LoomaTokens.colors.searchField,
            appBarContainerColor = pageColor,
            scrolledAppBarContainerColor = pageColor,
            appBarNavigationIconColor = MaterialTheme.colorScheme.onSurface,
            appBarActionIconColor = MaterialTheme.colorScheme.onSurface,
        ),
        inputField = inputField,
        navigationIcon = {
            Box(Modifier.graphicsLayer { alpha = 1f - searchBarState.progress }) {
                IconButton(onClick = onMenuClick, shapes = IconButtonDefaults.shapes()) {
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
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.home_avatar_letter),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
        }
    }
}
