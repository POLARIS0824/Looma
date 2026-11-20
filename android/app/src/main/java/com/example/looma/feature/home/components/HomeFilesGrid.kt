package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.model.FolderPath
import com.example.looma.feature.home.HomeFilesState
import com.example.looma.feature.home.HomeFolderItem
import com.example.looma.feature.home.HomeNoteItem
import com.example.looma.feature.home.HomeSelection
import com.example.looma.feature.home.RecentReveal
import com.example.looma.ui.components.SectionHeader

@Composable
internal fun HomeFilesGrid(
    files: HomeFilesState,
    isGridMode: Boolean,
    selection: HomeSelection,
    recentReveal: RecentReveal?,
    onRecentRevealed: (RecentReveal) -> Unit,
    onFolderClick: (HomeFolderItem) -> Unit,
    onFolderLongClick: (HomeFolderItem) -> Unit,
    onNoteClick: (HomeNoteItem) -> Unit,
    onNoteLongClick: (HomeNoteItem) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
) {
    val selectedFolder = selection.folder?.path
    val recentState = rememberLazyListState()
    val acknowledge = rememberUpdatedState(onRecentRevealed)
    val targetIndex = recentReveal?.let { request -> files.recents.indexOfFirst { it.note.id == request.noteId } } ?: -1
    LaunchedEffect(recentReveal, targetIndex, files.path) {
        val request = recentReveal ?: return@LaunchedEffect
        if (!files.isAtRoot || targetIndex < 0) return@LaunchedEffect
        gridState.animateScrollToItem(0)
        recentState.animateScrollToItem(targetIndex)
        acknowledge.value(request)
    }
    HomeNoteGrid(
        isGridMode = isGridMode,
        modifier = modifier,
        state = gridState,
    ) {
        if (files.isAtRoot) {
            if (files.recents.isNotEmpty()) {
                item(key = "recents-header", span = StaggeredGridItemSpan.FullLine) {
                    SectionHeader(
                        title = stringResource(R.string.home_section_recents),
                        parentSpacing = HomeCardGap,
                    )
                }
                item(key = "recents-row", span = StaggeredGridItemSpan.FullLine) {
                    HomeRecentRow(
                        items = files.recents,
                        selection = selection.noteKeys,
                        listState = recentState,
                        onNoteClick = onNoteClick,
                        onNoteLongClick = onNoteLongClick,
                    )
                }
            }
            if (files.folders.isNotEmpty()) {
                item(key = "folders-header", span = StaggeredGridItemSpan.FullLine) {
                    SectionHeader(
                        title = stringResource(R.string.home_section_folders),
                        first = files.recents.isEmpty(),
                        parentSpacing = HomeCardGap,
                    )
                }
                folderSection(files.folders, isGridMode, selectedFolder, onFolderClick, onFolderLongClick)
            }
            if (files.notes.isNotEmpty()) {
                item(key = "notes-header", span = StaggeredGridItemSpan.FullLine) {
                    SectionHeader(
                        title = stringResource(R.string.home_section_notes),
                        first = files.recents.isEmpty() && files.folders.isEmpty(),
                        parentSpacing = HomeCardGap,
                    )
                }
            }
        } else {
            if (files.isEmpty) {
                item(key = "empty-folder", span = StaggeredGridItemSpan.FullLine) {
                    Text(
                        text = stringResource(R.string.home_empty_folder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
            folderSection(files.folders, isGridMode, selectedFolder, onFolderClick, onFolderLongClick)
        }

        items(
            items = files.notes,
            key = { "note:${it.listKey}" },
        ) { note ->
            HomeNoteCard(
                note = note,
                isSelected = note.listKey in selection.noteKeys,
                onClick = { onNoteClick(note) },
                onLongClick = { onNoteLongClick(note) },
            )
        }
    }
}

private fun LazyStaggeredGridScope.folderSection(
    folders: List<HomeFolderItem>,
    isGridMode: Boolean,
    selectedFolder: FolderPath?,
    onFolderClick: (HomeFolderItem) -> Unit,
    onFolderLongClick: (HomeFolderItem) -> Unit,
) {
    if (folders.isEmpty()) return
    if (isGridMode) {
        items(
            items = folders,
            key = { "folder:${it.path.relativePath}" },
        ) { folder ->
            HomeFolderCard(
                item = folder,
                isSelected = folder.path == selectedFolder,
                onClick = { onFolderClick(folder) },
                onLongClick = { onFolderLongClick(folder) },
            )
        }
    } else {
        item(key = "folders-group", span = StaggeredGridItemSpan.FullLine) {
            HomeFolderGroup(
                folders = folders,
                selected = selectedFolder,
                onFolderClick = onFolderClick,
                onFolderLongClick = onFolderLongClick,
            )
        }
    }
}
