package com.example.looma.feature.note

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import com.example.looma.domain.markdown.PreparedMarkdown

internal sealed interface ModeScrollRequest {
    data class Read(val sourceOffset: Int, val atTop: Boolean, val expectedSource: String? = null) : ModeScrollRequest
    data class Edit(val anchor: ReadingAnchor) : ModeScrollRequest

    val isReadRequest: Boolean
        get() = this is Read
}

@Stable
internal class NoteModeScrollState(
    val readScroll: LazyListState,
    private val contentBuffer: NoteEditorState,
) {
    private val modeScroll = NoteModeScroll(readScroll, contentBuffer)
    var pendingModeScroll by mutableStateOf<ModeScrollRequest?>(null)
        private set

    fun navigateToRange(mode: NoteMode, source: String, range: TextRange) {
        if (mode == NoteMode.Edit) {
            contentBuffer.setSelection(range)
            pendingModeScroll = ModeScrollRequest.Edit(ReadingAnchor.edit(range.min, null, 0f, false))
        } else {
            pendingModeScroll = ModeScrollRequest.Read(sourceOffset = range.min, atTop = false, expectedSource = source)
        }
    }

    fun currentWeaveFocus(
        mode: NoteMode,
        preview: PreparedMarkdown?,
        showInlineTitle: Boolean,
    ): TextRange? = if (mode == NoteMode.Edit) {
        contentBuffer.selection
    } else {
        preview?.visibleBlocks()?.let {
            modeScroll.captureRead(it.blocks, it.index.bodyStart, showInlineTitle)?.let { anchor ->
                TextRange(anchor.sourceOffset)
            }
        }
    }

    fun prepareModeToggle(
        currentMode: NoteMode,
        preview: PreparedMarkdown?,
        showInlineTitle: Boolean,
    ): NoteMode {
        val next = if (currentMode == NoteMode.Read) NoteMode.Edit else NoteMode.Read
        pendingModeScroll = when (currentMode) {
            NoteMode.Read -> preview?.visibleBlocks()?.let {
                modeScroll.captureRead(
                    blocks = it.blocks,
                    bodyStart = it.index.bodyStart,
                    hasHeader = showInlineTitle,
                )?.let { anchor ->
                    contentBuffer.prepareHeaderForScroll(atTop = anchor.atTop)
                    ModeScrollRequest.Edit(anchor)
                }
            }
            NoteMode.Edit -> modeScroll.captureEdit()
                ?.let { position -> ModeScrollRequest.Read(position.sourceOffset, position.atTop) }
        }
        return next
    }

    fun canShowOpeningPreview(previewIsCurrent: Boolean, passageReady: Boolean): Boolean =
        previewIsCurrent && passageReady && when (val request = pendingModeScroll) {
            is ModeScrollRequest.Read -> request.atTop
            else -> readScroll.firstVisibleItemIndex == 0
        }

    suspend fun openPassage(
        offset: Int,
        preview: PreparedMarkdown.Ready,
        showInlineTitle: Boolean,
    ) {
        modeScroll.openPassage(offset, preview.blocks, preview.index.bodyStart, showInlineTitle)
    }

    suspend fun applyPendingScroll(
        request: ModeScrollRequest?,
        preview: PreparedMarkdown?,
        showInlineTitle: Boolean,
    ) {
        val currentPreview = (preview as? PreparedMarkdown.Ready)?.takeIf {
            it.source == contentBuffer.snapshot()
        }
        when (request) {
            is ModeScrollRequest.Read -> {
                if (preview is PreparedMarkdown.Failed) {
                    if (pendingModeScroll === request) pendingModeScroll = null
                    return
                }
                currentPreview ?: return
                if (request.expectedSource == null || currentPreview.source == request.expectedSource) {
                    modeScroll.restoreRead(
                        position = EditScrollPosition(request.sourceOffset, request.atTop),
                        blocks = currentPreview.blocks,
                        bodyStart = currentPreview.index.bodyStart,
                        hasHeader = showInlineTitle,
                    )
                }
            }
            is ModeScrollRequest.Edit -> modeScroll.restoreEdit(request.anchor)
            null -> return
        }
        if (pendingModeScroll === request) pendingModeScroll = null
    }
}

@Composable
internal fun rememberNoteModeScrollState(
    contentBuffer: NoteEditorState,
    readScroll: LazyListState = rememberLazyListState(),
): NoteModeScrollState = remember(contentBuffer, readScroll) {
    NoteModeScrollState(readScroll = readScroll, contentBuffer = contentBuffer)
}

internal fun PreparedMarkdown.visibleBlocks(): PreparedMarkdown.Ready? = when (this) {
    is PreparedMarkdown.Ready -> this
    is PreparedMarkdown.Opening -> preview
    is PreparedMarkdown.Failed -> null
}
