@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.looma.R
import com.example.looma.feature.home.HomeCrumb
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun HomeFolderTopBar(
    crumbs: List<HomeCrumb>,
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigateUp: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val rootLabel = stringResource(R.string.home_breadcrumb_root)
    val labels = crumbs.map { it.label ?: rootLabel }
    MediumFlexibleTopAppBar(
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
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = LoomaTokens.colors.page,
            navigationIconContentColor = LoomaTokens.colors.title,
            titleContentColor = LoomaTokens.colors.title,
            subtitleContentColor = LoomaTokens.colors.muted,
            actionIconContentColor = LoomaTokens.colors.title,
        ),
        scrollBehavior = scrollBehavior,
    )
}
