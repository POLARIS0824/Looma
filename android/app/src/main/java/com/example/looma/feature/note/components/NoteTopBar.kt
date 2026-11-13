@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.looma.feature.note.components

import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.looma.R
import com.example.looma.feature.note.NoteMode
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun NoteTopBar(
    mode: NoteMode,
    onNavigateUp: () -> Unit,
    onToggleMode: (() -> Unit)?,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    title: @Composable () -> Unit = {},
) {
    CenterAlignedTopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onNavigateUp, shapes = IconButtonDefaults.shapes()) {
                LoomaIcon(
                    iconRes = R.drawable.ic_arrow_back,
                    contentDescription = stringResource(R.string.editor_back),
                )
            }
        },
        actions = {
            val toggle = onToggleMode
            if (toggle != null) {
                IconButton(onClick = toggle, shapes = IconButtonDefaults.shapes()) {
                    LoomaIcon(
                        iconRes = if (mode == NoteMode.Read) {
                            R.drawable.ic_editor_edit
                        } else {
                            R.drawable.ic_editor_preview
                        },
                        contentDescription = stringResource(
                            if (mode == NoteMode.Read) R.string.note_edit else R.string.note_read,
                        ),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = LoomaTokens.colors.noteCanvas,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        scrollBehavior = scrollBehavior,
    )
}
