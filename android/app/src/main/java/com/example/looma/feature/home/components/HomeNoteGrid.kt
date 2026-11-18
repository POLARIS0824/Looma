package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.looma.ui.theme.LoomaTokens

private val FabClearance = 96.dp
private val DefaultContentPadding = PaddingValues(
    start = LoomaTokens.spacing.pageMargin,
    end = LoomaTokens.spacing.pageMargin,
    top = 8.dp,
    bottom = FabClearance,
)

@Composable
internal fun HomeNoteGrid(
    isGridMode: Boolean,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = DefaultContentPadding,
    content: LazyStaggeredGridScope.() -> Unit,
) {
    LazyVerticalStaggeredGrid(
        state = state,
        columns = if (isGridMode) {
            StaggeredGridCells.Adaptive(minSize = 150.dp)
        } else {
            StaggeredGridCells.Fixed(1)
        },
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(LoomaTokens.spacing.gridGap),
        verticalItemSpacing = LoomaTokens.spacing.gridGap,
        content = content,
    )
}
