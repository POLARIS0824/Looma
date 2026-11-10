package com.example.looma.feature.home.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.model.FolderPath
import com.example.looma.feature.home.HomeFolderItem
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun HomeFolderCard(
    item: HomeFolderItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val colors = homeCardColors(isSelected)
    val actionsLabel = stringResource(R.string.home_folder_actions_description)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .semantics { selected = isSelected }
            .combinedClickable(
                onLongClickLabel = actionsLabel,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = shape,
        color = colors.container,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderIcon(tint = folderIconTint(isSelected))
            Spacer(Modifier.width(12.dp))
            FolderName(
                name = item.name,
                color = colors.title,
                maxLines = 2,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            if (isSelected) {
                Spacer(Modifier.width(8.dp))
                SelectedCheck()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeFolderGroup(
    folders: List<HomeFolderItem>,
    selected: FolderPath?,
    onFolderClick: (HomeFolderItem) -> Unit,
    onFolderLongClick: (HomeFolderItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (folders.isEmpty()) return
    val actionsLabel = stringResource(R.string.home_folder_actions_description)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        folders.forEachIndexed { index, folder ->
            val isSelected = folder.path == selected
            val colors = homeCardColors(isSelected)
            val iconTint = folderIconTint(isSelected)
            SegmentedListItem(
                onClick = { onFolderClick(folder) },
                onLongClick = { onFolderLongClick(folder) },
                onLongClickLabel = actionsLabel,
                shapes = ListItemDefaults.segmentedShapes(index, folders.size),
                colors = ListItemDefaults.segmentedColors(
                    containerColor = colors.container,
                    contentColor = colors.title,
                    leadingContentColor = iconTint,
                    trailingContentColor = colors.title,
                ),
                modifier = Modifier.semantics { this.selected = isSelected },
                leadingContent = { FolderIcon(tint = iconTint) },
                trailingContent = if (isSelected) {
                    { SelectedCheck() }
                } else {
                    null
                },
            ) {
                FolderName(name = folder.name, color = colors.title, maxLines = 1)
            }
        }
    }
}

@Composable
private fun folderIconTint(isSelected: Boolean): Color =
    if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else LoomaTokens.colors.muted

@Composable
private fun FolderIcon(tint: Color) {
    LoomaIcon(
        iconRes = R.drawable.ic_folder,
        contentDescription = null,
        tint = tint,
    )
}

@Composable
private fun SelectedCheck() {
    LoomaIcon(
        iconRes = R.drawable.ic_check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun FolderName(
    name: String,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = name,
        style = LoomaTokens.type.folderName,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
