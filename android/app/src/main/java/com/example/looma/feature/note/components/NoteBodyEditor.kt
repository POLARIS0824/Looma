package com.example.looma.feature.note.components

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.NoCopySpan
import android.text.Spannable
import android.text.Spanned
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.SurroundingText
import androidx.annotation.RequiresApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFrom
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.looma.R
import com.example.looma.feature.note.NoteEditorState
import com.example.looma.ui.components.PageMaxWidth
import com.example.looma.ui.components.pageMargin
import com.example.looma.ui.theme.LoomaTokens
import com.example.looma.ui.theme.rememberNoteBodyTypeface
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlin.math.abs
import kotlin.math.roundToInt

private val CursorWidth = 2.dp
private const val DefaultLineSpacingMultiplier = 1.5f
private const val HeaderResizeTolerancePx = 2

private data class SoraColors(
    val isDark: Boolean,
    val background: Int,
    val text: Int,
    val cursor: Int,
    val selectionBg: Int,
    val thumb: Int,
    val thumbPressed: Int,
    val actionBg: Int,
    val actionIcon: Int,
)

private data class AppliedSoraStyle(
    val typeface: Typeface,
    val textSizePx: Float,
    val lineSpacingMultiplier: Float,
    val cursorWidthPx: Float,
    val colors: SoraColors,
)

private class LoomaEditorColorScheme(
    isDark: Boolean,
    colors: SoraColors,
) : EditorColorScheme(isDark) {
    init {
        setColor(WHOLE_BACKGROUND, colors.background)
        setColor(TEXT_NORMAL, colors.text)
        setColor(UNDERLINE, colors.text)
        setColor(SELECTION_INSERT, colors.cursor)
        setColor(SELECTION_HANDLE, colors.cursor)
        setColor(SELECTED_TEXT_BACKGROUND, colors.selectionBg)
        setColor(SCROLL_BAR_THUMB, colors.thumb)
        setColor(SCROLL_BAR_THUMB_PRESSED, colors.thumbPressed)
        setColor(TEXT_ACTION_WINDOW_BACKGROUND, colors.actionBg)
        setColor(TEXT_ACTION_WINDOW_ICON_COLOR, colors.actionIcon)
    }
}

private object ComposingSpanMarker : NoCopySpan

private class LoomaCodeEditor(context: Context) : CodeEditor(context) {
    var appliedStyle: AppliedSoraStyle? = null
    var headerTopPx: Int = 0
    var headerHeightPx: Float = 0f
    var headerOffsetPx: Float = 0f
    var onHeaderOffsetChange: ((Float) -> Unit)? = null

    private var lastLayoutHeaderTopPx: Int = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawY = 0f
    private var draggingHeader = false
    private var awaitingPostHandoffMove = false

    fun syncSelectionToIme() {
        updateSelection()
    }

    override fun measureTextRegionOffset(): Float =
        if (isLineNumberEnabled) super.measureTextRegionOffset() else insertSelectionWidth / 2f

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return LoomaInputConnection(this, base)
    }

    override fun onSizeChanged(w: Int, h: Int, oldWidth: Int, oldHeight: Int) {
        val headerDelta = headerTopPx - lastLayoutHeaderTopPx
        lastLayoutHeaderTopPx = headerTopPx
        val heightDelta = oldHeight - h
        val headerExpandedOnly = headerDelta > 0 && heightDelta > 0 &&
            abs(heightDelta - headerDelta) <= HeaderResizeTolerancePx
        val previousAdjust = props.adjustToSelectionOnResize
        if (headerExpandedOnly) {
            props.adjustToSelectionOnResize = false
        }
        try {
            super.onSizeChanged(w, h, oldWidth, oldHeight)
        } finally {
            props.adjustToSelectionOnResize = previousAdjust
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (headerHeightPx <= 0f || eventHandler.handlingMotions()) {
            draggingHeader = false
            awaitingPostHandoffMove = false
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawY = event.rawY
                draggingHeader = false
                awaitingPostHandoffMove = false
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - lastRawY
                lastRawY = event.rawY
                if (awaitingPostHandoffMove) {
                    awaitingPostHandoffMove = false
                    return super.onTouchEvent(event)
                }
                if (!draggingHeader) {
                    val totalDy = abs(event.rawY - downRawY)
                    val totalDx = abs(event.rawX - downRawX)
                    if (totalDy <= touchSlop || totalDy <= totalDx) {
                        return super.onTouchEvent(event)
                    }
                }
                if (dy < 0f && headerOffsetPx < headerHeightPx && scrollMaxY > 0) {
                    val next = (headerOffsetPx - dy).coerceAtMost(headerHeightPx)
                    headerOffsetPx = next
                    onHeaderOffsetChange?.invoke(next)
                    if (!draggingHeader) {
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        draggingHeader = true
                    }
                    if (next >= headerHeightPx) {
                        draggingHeader = false
                        awaitingPostHandoffMove = true
                        val down = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_DOWN }
                        super.onTouchEvent(down)
                        down.recycle()
                    }
                    return true
                }
                if (dy > 0f && offsetY == 0 && headerOffsetPx > 0f) {
                    val next = (headerOffsetPx - dy).coerceAtLeast(0f)
                    headerOffsetPx = next
                    onHeaderOffsetChange?.invoke(next)
                    if (!draggingHeader) {
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        draggingHeader = true
                    }
                    return true
                }
                if (draggingHeader) return true
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (awaitingPostHandoffMove) {
                    awaitingPostHandoffMove = false
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                    return true
                }
                if (draggingHeader) {
                    draggingHeader = false
                    return true
                }
                return super.onTouchEvent(event)
            }
        }
        return super.onTouchEvent(event)
    }
}

private fun codePointsToCharsBefore(text: CharSequence, offset: Int, codePoints: Int): Int {
    if (codePoints <= 0 || offset <= 0) return 0
    if (codePoints >= offset) return offset
    val start = (offset.toLong() - codePoints.toLong() * 2L).coerceAtLeast(0L).toInt()
    val window = text.subSequence(start, offset)
    var index = window.length
    var remaining = codePoints
    while (index > 0 && remaining > 0) {
        index--
        if (index > 0 && Character.isLowSurrogate(window[index]) && Character.isHighSurrogate(window[index - 1])) {
            index--
        }
        remaining--
    }
    return window.length - index
}

private fun codePointsToCharsAfter(text: CharSequence, offset: Int, codePoints: Int): Int {
    val available = text.length - offset
    if (codePoints <= 0 || available <= 0) return 0
    if (codePoints >= available) return available
    val end = (offset.toLong() + codePoints.toLong() * 2L).coerceAtMost(text.length.toLong()).toInt()
    val window = text.subSequence(offset, end)
    var index = 0
    var remaining = codePoints
    while (index < window.length && remaining > 0) {
        if (index + 1 < window.length && Character.isHighSurrogate(window[index]) && Character.isLowSurrogate(window[index + 1])) {
            index += 2
        } else {
            index++
        }
        remaining--
    }
    return index
}

private class LoomaInputConnection(
    private val editor: LoomaCodeEditor,
    target: InputConnection,
) : InputConnectionWrapper(target, true) {
    private var batchEditDepth = 0

    override fun beginBatchEdit(): Boolean {
        batchEditDepth++
        return super.beginBatchEdit()
    }

    override fun endBatchEdit(): Boolean {
        val inBatch = super.endBatchEdit()
        if (batchEditDepth > 0) batchEditDepth--
        if (batchEditDepth == 0 && inBatch) {
            editor.syncSelectionToIme()
        }
        return inBatch
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (editor.hasComposingText() && text?.toString() == "\n") {
            super.finishComposingText()
        }
        val result = super.commitText(text, newCursorPosition)
        if (result && batchEditDepth == 0) {
            editor.syncSelectionToIme()
        }
        return result
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val result = super.setComposingText(text, newCursorPosition)
        if (result && batchEditDepth == 0) {
            editor.syncSelectionToIme()
        }
        return result
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        if (start != end && editor.hasComposingText()) {
            super.finishComposingText()
        }
        val result = super.setComposingRegion(start, end)
        if (result && batchEditDepth == 0) {
            editor.syncSelectionToIme()
        }
        return result
    }

    override fun finishComposingText(): Boolean {
        val result = super.finishComposingText()
        if (result && batchEditDepth == 0) {
            editor.syncSelectionToIme()
        }
        return result
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (!editor.editable || beforeLength < 0 || afterLength < 0) {
            return false
        }
        val content = editor.text
        val cursor = editor.cursor
        val beforeChars = if (beforeLength == Int.MAX_VALUE) {
            cursor.leftColumn
        } else {
            codePointsToCharsBefore(content, cursor.left, beforeLength)
        }
        val afterChars = if (afterLength == Int.MAX_VALUE) {
            content.getColumnCount(cursor.rightLine) - cursor.rightColumn
        } else {
            codePointsToCharsAfter(content, cursor.right, afterLength)
        }
        if (beforeChars == 0 && afterChars == 0) {
            return true
        }
        return super.deleteSurroundingText(beforeChars, afterChars)
    }

    override fun closeConnection() {
        batchEditDepth = 0
        super.closeConnection()
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        fixComposingSpan(super.getTextBeforeCursor(n, flags), flags)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
        fixComposingSpan(super.getTextAfterCursor(n, flags), flags)

    override fun getSelectedText(flags: Int): CharSequence? =
        fixComposingSpan(super.getSelectedText(flags), flags)

    @RequiresApi(Build.VERSION_CODES.S)
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
        val surrounding = super.getSurroundingText(beforeLength, afterLength, flags) ?: return null
        if (flags == InputConnection.GET_TEXT_WITH_STYLES) {
            fixComposingSpan(surrounding.text, flags)
        }
        return surrounding
    }

    private fun fixComposingSpan(text: CharSequence?, flags: Int): CharSequence? {
        if (flags != InputConnection.GET_TEXT_WITH_STYLES || text !is Spannable) return text
        for (span in text.getSpans(0, text.length, Any::class.java)) {
            if (span == Spanned.SPAN_COMPOSING) {
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                text.removeSpan(span)
                if (start in 0..end && end <= text.length) {
                    text.setSpan(
                        ComposingSpanMarker,
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING,
                    )
                }
            }
        }
        return text
    }
}

@Composable
internal fun NoteBodyEditor(
    editorState: NoteEditorState,
    active: Boolean,
    onScrollChanged: (offsetY: Int, byUser: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val typeface = rememberNoteBodyTypeface()
    val bodyStyle = LoomaTokens.type.noteBody
    val density = LocalDensity.current
    val textSizePx = with(density) { bodyStyle.fontSize.toPx() }
    val cursorWidthPx = with(density) { CursorWidth.toPx() }
    val lineSpacingMultiplier = if (bodyStyle.fontSize.value > 0f) {
        bodyStyle.lineHeight.value / bodyStyle.fontSize.value
    } else {
        DefaultLineSpacingMultiplier
    }
    val colorScheme = MaterialTheme.colorScheme
    val noteCanvas = LoomaTokens.colors.noteCanvas
    val colors = SoraColors(
        isDark = noteCanvas.luminance() < 0.5f,
        background = noteCanvas.toArgb(),
        text = colorScheme.onSurface.toArgb(),
        cursor = colorScheme.primary.toArgb(),
        selectionBg = colorScheme.primaryContainer.toArgb(),
        thumb = colorScheme.outlineVariant.toArgb(),
        thumbPressed = colorScheme.onSurfaceVariant.toArgb(),
        actionBg = colorScheme.surfaceContainerHigh.toArgb(),
        actionIcon = colorScheme.onSurface.toArgb(),
    )
    val latestOnScroll by rememberUpdatedState(onScrollChanged)
    var editorView by remember { mutableStateOf<LoomaCodeEditor?>(null) }
    var headerHeightPx by remember { mutableFloatStateOf(0f) }
    var firstRowBaselinePx by remember { mutableIntStateOf(0) }

    LaunchedEffect(header == null) {
        if (header == null) {
            headerHeightPx = 0f
            editorState.headerHeightPx = 0f
        }
    }

    DisposableEffect(editorState) {
        onDispose {
            editorView?.let { editorState.detach(it) }
        }
    }

    Box(
        modifier = modifier.fillMaxWidth().clipToBounds(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = PageMaxWidth)
                .fillMaxSize()
                .padding(horizontal = pageMargin()),
        ) {
            val bodyTopPx = (headerHeightPx - editorState.headerOffsetPx).coerceAtLeast(0f).roundToInt()
            val bodyTopDp = with(density) { bodyTopPx.toDp() }
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = bodyTopDp),
                factory = { ctx ->
                    LoomaCodeEditor(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        isWordwrap = true
                        isLineNumberEnabled = false
                        isScalable = false
                        isHighlightCurrentLine = false
                        isHighlightCurrentBlock = false
                        isHighlightBracketPair = false
                        nonPrintablePaintingFlags = 0
                        props.symbolPairAutoCompletion = false
                        props.autoIndent = false
                        props.stickyScroll = false
                        props.highlightMatchingDelimiters = false
                        props.textBackgroundWrapTextOnly = true
                        getComponent(EditorAutoCompletion::class.java).isEnabled = false
                        alpha = 0f
                        editorView = this
                    }
                },
                update = { ed ->
                    editorView = ed
                    ed.headerTopPx = bodyTopPx
                    ed.headerHeightPx = headerHeightPx
                    ed.headerOffsetPx = editorState.headerOffsetPx
                    ed.onHeaderOffsetChange = editorState::updateHeaderOffset
                    val applied = ed.appliedStyle
                    if (applied?.typeface !== typeface) {
                        ed.typefaceText = typeface
                    }
                    if (applied?.textSizePx != textSizePx) {
                        ed.setTextSizePx(textSizePx)
                    }
                    if (applied?.lineSpacingMultiplier != lineSpacingMultiplier) {
                        ed.setLineSpacing(0f, lineSpacingMultiplier)
                    }
                    if (applied?.cursorWidthPx != cursorWidthPx) {
                        ed.setCursorWidth(cursorWidthPx)
                    }
                    if (applied?.colors != colors) {
                        ed.colorScheme = LoomaEditorColorScheme(colors.isDark, colors)
                    }
                    ed.appliedStyle = AppliedSoraStyle(typeface, textSizePx, lineSpacingMultiplier, cursorWidthPx, colors)
                    val baselinePx = ed.getRowBaseline(0)
                    if (firstRowBaselinePx != baselinePx) {
                        firstRowBaselinePx = baselinePx
                    }
                    if (ed.editable != active) {
                        ed.editable = active
                    }
                    val targetAlpha = if (editorState.isLayoutReady) 1f else 0f
                    if (ed.alpha != targetAlpha) {
                        ed.alpha = targetAlpha
                    }
                    editorState.attach(ed) { y, byUser -> latestOnScroll(y, byUser) }
                },
                onRelease = { ed ->
                    editorState.detach(ed)
                    if (editorView === ed) editorView = null
                    ed.release()
                },
            )
            if (editorState.isEmpty) {
                val baselineDp = if (firstRowBaselinePx > 0) {
                    with(density) { firstRowBaselinePx.toDp() }
                } else {
                    Dp.Unspecified
                }
                Text(
                    text = stringResource(R.string.editor_placeholder),
                    style = LoomaTokens.type.noteBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = bodyTopDp, start = CursorWidth)
                        .paddingFrom(FirstBaseline, before = baselineDp),
                )
            }
            if (header != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged {
                            val h = it.height.toFloat()
                            headerHeightPx = h
                            editorState.headerHeightPx = h
                        }
                        .graphicsLayer { translationY = -editorState.headerOffsetPx }
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta -> editorState.dragHeaderBy(-delta) },
                        ),
                ) {
                    header()
                }
            }
        }
    }
}
