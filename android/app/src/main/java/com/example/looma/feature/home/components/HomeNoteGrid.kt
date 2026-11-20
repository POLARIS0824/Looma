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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.looma.ui.components.isCompactWindow
import com.example.looma.ui.components.pageMargin

private val FabClearance = 96.dp
internal val HomeCardGap = 8.dp

@Composable
internal fun homeGridPadding(bottom: Dp = FabClearance): PaddingValues {
    val margin = pageMargin()
    return PaddingValues(start = margin, end = margin, top = 8.dp, bottom = bottom)
}

@Composable
internal fun HomeNoteGrid(
    isGridMode: Boolean,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = homeGridPadding(),
    content: LazyStaggeredGridScope.() -> Unit,
) {
    LazyVerticalStaggeredGrid(
        state = state,
        columns = if (isGridMode) {
            StaggeredGridCells.Adaptive(minSize = 150.dp)
        } else if (isCompactWindow()) {
            StaggeredGridCells.Fixed(1)
        } else {
            StaggeredGridCells.Adaptive(minSize = 360.dp)
        },
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(HomeCardGap),
        verticalItemSpacing = HomeCardGap,
        content = content,
    )
}
