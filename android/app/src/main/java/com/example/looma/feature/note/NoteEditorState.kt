package com.example.looma.feature.note

import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.LayoutStateChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.util.IntPair
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private enum class LayoutPhase {
    Unbound,
    LayoutBusy,
    Ready,
}

internal data class EditorScrollMetrics(
    val offsetPx: Float = 0f,
    val maxOffsetPx: Float = 0f,
    val viewportPx: Float = 0f,
)

private sealed interface EditorSession {
    data object Detached : EditorSession
    data class Attached(
        val editor: CodeEditor,
        val subscriptions: List<SubscriptionReceipt<*>>,
        val layoutChangeListener: View.OnLayoutChangeListener,
        val phase: LayoutPhase,
    ) : EditorSession
}

internal class NoteEditorState(
    initialText: String = "",
    initialSelection: TextRange = TextRange.Zero,
) {
    val content: Content = Content(initialText)

    var contentVersion by mutableLongStateOf(0L)
        private set

    private val _selection = mutableStateOf(clampedRange(initialSelection, initialText.length))
    val selection: TextRange get() = _selection.value

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    var isEmpty by mutableStateOf(initialText.isEmpty())
        private set

    var headerOffsetPx by mutableFloatStateOf(0f)
        private set

    private var _headerHeightPx by mutableFloatStateOf(0f)
    var headerHeightPx: Float
        get() = _headerHeightPx
        set(value) {
            val clamped = value.coerceAtLeast(0f)
            _headerHeightPx = clamped
            if (clamped == 0f) {
                collapseHeaderOnMeasure = false
            }
            if (clamped > 0f && collapseHeaderOnMeasure) {
                headerOffsetPx = clamped
                collapseHeaderOnMeasure = false
            } else if (headerOffsetPx > clamped) {
                headerOffsetPx = clamped
            }
            syncScrollMetrics()
        }

    var scrollMetrics by mutableStateOf(EditorScrollMetrics())
        private set

    private var collapseHeaderOnMeasure: Boolean = false
    private var cachedVersion: Long = 0L
    private var cachedText: String = initialText
    private var session by mutableStateOf<EditorSession>(EditorSession.Detached)
    private var scrollCallback: ((offsetY: Int) -> Unit)? = null

    private val attachedEditor: CodeEditor?
        get() = (session as? EditorSession.Attached)?.editor

    private val isContentBound: Boolean
        get() = (session as? EditorSession.Attached)?.let { it.phase != LayoutPhase.Unbound } == true

    val isLayoutReady: Boolean
        get() = (session as? EditorSession.Attached)?.phase == LayoutPhase.Ready

    init {
        applyCursorSelection(content, selection)
        content.addContentListener(object : ContentListener {
            override fun beforeReplace(content: Content) = Unit

            override fun afterInsert(
                content: Content,
                startLine: Int,
                startColumn: Int,
                endLine: Int,
                endColumn: Int,
                insertedContent: CharSequence,
            ) {
                onContentMutated()
            }

            override fun afterDelete(
                content: Content,
                startLine: Int,
                startColumn: Int,
                endLine: Int,
                endColumn: Int,
                deletedContent: CharSequence,
            ) {
                onContentMutated()
            }
        })
    }

    val length: Int get() = content.length
    val scrollOffsetY: Int get() = attachedEditor?.offsetY ?: 0
    val totalScrollOffsetY: Float get() = headerOffsetPx + scrollOffsetY
    val canCollapseHeader: Boolean
        get() = (attachedEditor?.scrollMaxY ?: 0) > 0 || headerOffsetPx > 0f

    fun updateHeaderOffset(offset: Float) {
        collapseHeaderOnMeasure = false
        headerOffsetPx = offset.coerceIn(0f, headerHeightPx)
        syncScrollMetrics()
        scrollCallback?.invoke(totalScrollOffsetY.roundToInt())
    }

    fun dragHeaderBy(delta: Float) {
        if (delta > 0f && !canCollapseHeader) return
        updateHeaderOffset(headerOffsetPx + delta)
    }

    fun expandHeader() {
        collapseHeaderOnMeasure = false
        headerOffsetPx = 0f
        syncScrollMetrics()
        scrollCallback?.invoke(totalScrollOffsetY.roundToInt())
    }

    fun prepareHeaderForScroll(atTop: Boolean) {
        if (atTop) {
            collapseHeaderOnMeasure = false
            headerOffsetPx = 0f
        } else if (headerHeightPx > 0f) {
            headerOffsetPx = headerHeightPx
            collapseHeaderOnMeasure = false
        } else {
            collapseHeaderOnMeasure = true
        }
        syncScrollMetrics()
    }

    fun snapshot(): String {
        if (cachedVersion != contentVersion) {
            cachedText = content.toString()
            cachedVersion = contentVersion
        }
        return cachedText
    }

    fun replaceRange(
        start: Int,
        end: Int,
        replacement: String,
        newSelection: TextRange? = null,
    ) {
        val editor = attachedEditor
        if (editor != null && isContentBound && editor.hasComposingText()) {
            editor.restartInput()
        }
        val from = minOf(start, end).coerceIn(0, content.length)
        val to = maxOf(start, end).coerceIn(0, content.length)
        content.replace(from, to, replacement)
        setSelection(newSelection ?: TextRange(from + replacement.length))
    }

    fun append(text: String) {
        replaceRange(content.length, content.length, text)
    }

    fun setSelection(range: TextRange) {
        val target = clampedRange(range, content.length)
        _selection.value = target
        val editor = attachedEditor
        if (editor != null && isContentBound) {
            applySelectionToEditor(editor, target)
            editor.notifyIMEExternalCursorChange()
        } else {
            applyCursorSelection(content, target)
        }
    }

    fun undo() {
        attachedEditor?.undo() ?: content.undo()
        syncFromContent()
    }

    fun redo() {
        attachedEditor?.redo() ?: content.redo()
        syncFromContent()
    }

    fun requestFocus() {
        val editor = attachedEditor ?: return
        editor.requestFocus()
        editor.context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    fun clearFocus() {
        val editor = attachedEditor ?: return
        if (editor.hasFocus()) {
            editor.clearFocus()
        }
    }

    fun attach(editor: CodeEditor, onScrollChanged: (offsetY: Int) -> Unit) {
        scrollCallback = onScrollChanged
        if (attachedEditor === editor) {
            bindContentIfReady(editor)
            refreshLayoutReady(editor)
            return
        }
        attachedEditor?.let { detach(it) }
        val subscriptions = listOf(
            editor.subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                _selection.value = TextRange(event.left.index, event.right.index)
            },
            editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                canUndo = content.canUndo()
                canRedo = content.canRedo()
                isEmpty = content.length == 0
            },
            editor.subscribeEvent(ScrollEvent::class.java) { event, _ ->
                val holdingScrollbar = editor.eventHandler.holdVerticalScrollBar()
                if (headerHeightPx > 0f) {
                    when {
                        holdingScrollbar -> {
                            headerOffsetPx = event.endY.toFloat().coerceIn(0f, headerHeightPx)
                        }
                        event.endY > 0 && headerOffsetPx < headerHeightPx -> {
                            headerOffsetPx = maxOf(headerOffsetPx, event.endY.toFloat()).coerceAtMost(headerHeightPx)
                        }
                    }
                }
                syncScrollMetrics(editor, event.endY)
                scrollCallback?.invoke((headerOffsetPx + event.endY).roundToInt())
            },
            editor.subscribeEvent(LayoutStateChangeEvent::class.java) { event, _ ->
                val current = (session as? EditorSession.Attached)?.takeIf { it.editor === editor }
                    ?: return@subscribeEvent
                if (event.isLayoutBusy) {
                    if (current.phase != LayoutPhase.Unbound) {
                        session = current.copy(phase = LayoutPhase.LayoutBusy)
                    }
                } else {
                    refreshLayoutReady(editor, fromLayoutComplete = true)
                }
            },
        )
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (editor.width > 0 && editor.height > 0) {
                bindContentIfReady(editor)
                refreshLayoutReady(editor)
                syncScrollMetrics(editor)
            }
        }
        editor.addOnLayoutChangeListener(listener)
        session = EditorSession.Attached(
            editor = editor,
            subscriptions = subscriptions,
            layoutChangeListener = listener,
            phase = LayoutPhase.Unbound,
        )
        if (editor.width > 0 && editor.height > 0) {
            bindContentIfReady(editor)
            refreshLayoutReady(editor)
        }
    }

    private fun bindContentIfReady(editor: CodeEditor) {
        val current = (session as? EditorSession.Attached)
            ?.takeIf { it.editor === editor && it.phase == LayoutPhase.Unbound }
            ?: return
        if (editor.width <= 0) return
        val needsWordwrapLayout = editor.text !== content && editor.isWordwrap
        session = current.copy(phase = LayoutPhase.LayoutBusy)
        if (editor.text !== content) {
            editor.setText(content)
        }
        applySelectionToEditor(editor, selection, makePositionVisible = false)
        if (!needsWordwrapLayout) {
            refreshLayoutReady(editor, fromLayoutComplete = true)
        }
    }

    private fun refreshLayoutReady(editor: CodeEditor, fromLayoutComplete: Boolean = false) {
        val current = (session as? EditorSession.Attached)?.takeIf { it.editor === editor } ?: return
        if (current.phase == LayoutPhase.Unbound) return
        if (current.phase == LayoutPhase.LayoutBusy && !fromLayoutComplete) return
        if (editor.width > 0 && editor.height > 0 && editor.layout != null && editor.layout.rowCount > 0) {
            session = current.copy(phase = LayoutPhase.Ready)
            syncScrollMetrics(editor)
        }
    }

    fun detach(editor: CodeEditor) {
        val current = (session as? EditorSession.Attached)?.takeIf { it.editor === editor } ?: return
        editor.removeOnLayoutChangeListener(current.layoutChangeListener)
        current.subscriptions.forEach { it.unsubscribe() }
        session = EditorSession.Detached
        scrollCallback = null
        scrollMetrics = EditorScrollMetrics()
    }

    fun captureScrollPosition(): EditScrollPosition? {
        val editor = attachedEditor ?: return null
        val layout = editor.layout ?: return null
        val offsetY = editor.offsetY.coerceAtLeast(0)
        val packed = layout.getCharPositionForLayoutOffset(0f, offsetY.toFloat())
        val line = IntPair.getFirst(packed).coerceIn(0, (content.lineCount - 1).coerceAtLeast(0))
        val col = IntPair.getSecond(packed).coerceIn(0, content.getColumnCount(line))
        val sourceOffset = content.indexer.getCharIndex(line, col)
        return EditScrollPosition(sourceOffset = sourceOffset, atTop = offsetY == 0 && headerOffsetPx == 0f)
    }

    suspend fun awaitLayoutReady(): CodeEditor =
        snapshotFlow {
            (session as? EditorSession.Attached)?.takeIf { it.phase == LayoutPhase.Ready }?.editor
        }.filterNotNull().first()

    fun scrollToY(y: Int) {
        val editor = attachedEditor ?: return
        val target = y.coerceIn(0, editor.scrollMaxY.coerceAtLeast(0))
        prepareHeaderForScroll(atTop = target == 0)
        editor.scroller.forceFinished(true)
        editor.scroller.startScroll(0, target, 0, 0, 0)
        editor.scroller.abortAnimation()
        editor.invalidate()
        syncScrollMetrics(editor, target)
        scrollCallback?.invoke((headerOffsetPx + target).roundToInt())
    }

    fun scrollToFraction(fraction: Float) {
        val editor = attachedEditor ?: return
        val maxY = editor.scrollMaxY.coerceAtLeast(0)
        val totalMax = (headerHeightPx + maxY).coerceAtLeast(0f)
        val targetTotal = fraction.coerceIn(0f, 1f) * totalMax
        collapseHeaderOnMeasure = false
        headerOffsetPx = targetTotal.coerceAtMost(headerHeightPx)
        val bodyTarget = (targetTotal - headerOffsetPx).roundToInt().coerceIn(0, maxY)
        editor.scroller.forceFinished(true)
        editor.scroller.startScroll(0, bodyTarget, 0, 0, 0)
        editor.scroller.abortAnimation()
        editor.invalidate()
        syncScrollMetrics(editor, bodyTarget)
        scrollCallback?.invoke((headerOffsetPx + bodyTarget).roundToInt())
    }

    private fun syncScrollMetrics(editor: CodeEditor? = attachedEditor, bodyY: Int? = null) {
        if (editor == null || editor.height <= 0 || editor.layout == null) {
            scrollMetrics = EditorScrollMetrics()
            return
        }
        val y = (bodyY ?: editor.offsetY).coerceAtLeast(0)
        val maxY = editor.scrollMaxY.coerceAtLeast(0)
        val totalOffset = (headerOffsetPx + y).coerceAtLeast(0f)
        val totalMax = (headerHeightPx + maxY).coerceAtLeast(0f)
        scrollMetrics = EditorScrollMetrics(
            offsetPx = totalOffset.coerceAtMost(totalMax),
            maxOffsetPx = totalMax,
            viewportPx = editor.height.toFloat(),
        )
    }

    fun verticalTopFor(offset: Int): Float {
        val editor = attachedEditor ?: return 0f
        val layout = editor.layout ?: return 0f
        val pos = content.indexer.getCharPosition(offset.coerceIn(0, content.length))
        val coords = layout.getCharLayoutOffset(pos.line, pos.column)
        return (coords[0] - editor.rowHeight).coerceAtLeast(0f)
    }

    fun verticalBottomFor(offset: Int): Float {
        val editor = attachedEditor ?: return 0f
        val layout = editor.layout ?: return 0f
        val pos = content.indexer.getCharPosition(offset.coerceIn(0, content.length))
        val coords = layout.getCharLayoutOffset(pos.line, pos.column)
        return coords[0]
    }

    private fun onContentMutated() {
        contentVersion++
        syncFromContent()
    }

    private fun syncFromContent() {
        isEmpty = content.length == 0
        canUndo = content.canUndo()
        canRedo = content.canRedo()
        _selection.value = readCursorRange(content)
    }

    private fun applySelectionToEditor(
        editor: CodeEditor,
        range: TextRange,
        makePositionVisible: Boolean = true,
    ) {
        val left = content.indexer.getCharPosition(minOf(range.start, range.end))
        val right = content.indexer.getCharPosition(maxOf(range.start, range.end))
        if (left.line == right.line && left.column == right.column) {
            editor.setSelection(left.line, left.column, makePositionVisible)
        } else {
            editor.setSelectionRegion(left.line, left.column, right.line, right.column, makePositionVisible)
        }
    }
}

private fun clampedRange(range: TextRange, length: Int): TextRange =
    TextRange(range.start.coerceIn(0, length), range.end.coerceIn(0, length))

private fun readCursorRange(content: Content): TextRange =
    TextRange(content.cursor.left, content.cursor.right)

private fun applyCursorSelection(content: Content, range: TextRange) {
    val from = minOf(range.start, range.end).coerceIn(0, content.length)
    val to = maxOf(range.start, range.end).coerceIn(0, content.length)
    val left = content.indexer.getCharPosition(from)
    val right = content.indexer.getCharPosition(to)
    content.cursor.setLeft(left.line, left.column)
    content.cursor.setRight(right.line, right.column)
}
