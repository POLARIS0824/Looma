package com.example.looma.feature.note

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.TextRange
import com.example.looma.domain.markdown.PreparedMarkdown

internal sealed interface ModeScrollRequest {
    data class ReadPassage(val source: String, val offset: Int) : ModeScrollRequest
    data class Read(val position: EditScrollPosition) : ModeScrollRequest
    data class ReadOffset(val itemIndex: Int, val offset: Int) : ModeScrollRequest
    data class Edit(val anchor: ReadingAnchor) : ModeScrollRequest
    data class EditOffset(val offset: Int) : ModeScrollRequest

    val isReadRequest: Boolean
        get() = this is Read || this is ReadOffset || this is ReadPassage
}

internal data class ModeScrollReturn(val source: String, val position: ModeScrollRequest)

@Stable
internal class NoteModeScrollState(
    val readScroll: LazyListState,
    private val contentBuffer: NoteEditorState,
) {
    private val modeScroll = NoteModeScroll(readScroll, contentBuffer)
    var pendingModeScroll by mutableStateOf<ModeScrollRequest?>(null)
        private set
    private var modeScrollReturn by mutableStateOf<ModeScrollReturn?>(null)

    val scrollObserver: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                onUserScroll()
            }
            return Offset.Zero
        }
    }

    fun onUserScroll() {
        pendingModeScroll = null
        modeScrollReturn = null
    }

    fun navigateToRange(mode: NoteMode, source: String, range: TextRange) {
        modeScrollReturn = null
        if (mode == NoteMode.Edit) {
            contentBuffer.setSelection(range)
            pendingModeScroll = ModeScrollRequest.Edit(ReadingAnchor.edit(range.min, null, 0f, false))
        } else {
            pendingModeScroll = ModeScrollRequest.ReadPassage(source, range.min)
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
        val text = contentBuffer.snapshot()
        val returnPosition = modeScrollReturn?.takeIf { it.source == text }?.position
        pendingModeScroll = returnPosition ?: when (currentMode) {
            NoteMode.Read -> preview?.visibleBlocks()?.let {
                modeScroll.captureRead(
                    blocks = it.blocks,
                    bodyStart = it.index.bodyStart,
                    hasHeader = showInlineTitle,
                )?.let { anchor -> ModeScrollRequest.Edit(anchor) }
            }
            NoteMode.Edit -> modeScroll.captureEdit()
                ?.let { position -> ModeScrollRequest.Read(position) }
        }
        modeScrollReturn = if (returnPosition == null) {
            val position = when (currentMode) {
                NoteMode.Read -> ModeScrollRequest.ReadOffset(
                    readScroll.firstVisibleItemIndex,
                    readScroll.firstVisibleItemScrollOffset,
                )
                NoteMode.Edit -> ModeScrollRequest.EditOffset(contentBuffer.scrollOffsetY)
            }
            ModeScrollReturn(text, position)
        } else {
            null
        }
        return next
    }

    fun canShowOpeningPreview(previewIsCurrent: Boolean, passageReady: Boolean): Boolean =
        previewIsCurrent && passageReady && when (val request = pendingModeScroll) {
            is ModeScrollRequest.Read -> request.position.atTop
            is ModeScrollRequest.ReadOffset -> request.itemIndex == 0
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
            is ModeScrollRequest.ReadPassage -> {
                currentPreview ?: return
                if (currentPreview.source == request.source) {
                    modeScroll.openPassage(
                        request.offset,
                        currentPreview.blocks,
                        currentPreview.index.bodyStart,
                        showInlineTitle,
                    )
                }
            }
            is ModeScrollRequest.Read -> {
                currentPreview ?: return
                modeScroll.restoreRead(
                    position = request.position,
                    blocks = currentPreview.blocks,
                    bodyStart = currentPreview.index.bodyStart,
                    hasHeader = showInlineTitle,
                )
            }
            is ModeScrollRequest.ReadOffset -> {
                currentPreview ?: return
                readScroll.scrollToItem(request.itemIndex, request.offset)
            }
            is ModeScrollRequest.Edit -> modeScroll.restoreEdit(request.anchor)
            is ModeScrollRequest.EditOffset -> modeScroll.restoreEdit(request.offset)
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
