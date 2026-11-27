package com.example.looma.feature.note.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.looma.feature.note.NoteEditorState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

private val ThumbWidthIdle = 4.dp
private val ThumbWidthDragging = 8.dp
private val MinThumbHeight = 48.dp
private val TouchTargetWidth = 48.dp
private val ThumbEndPadding = 4.dp
private val TrackVerticalPadding = 8.dp
private const val HideDelayMillis = 2000L
private const val ThumbGrabSlopPx = 12f

private data class ScrollbarSnapshot(
    val thumbSizeFraction: Float,
    val offsetFraction: Float,
    val canScroll: Boolean,
) {
    companion object {
        val Empty = ScrollbarSnapshot(0f, 0f, false)
    }
}

private data class LazyListScrollLayout(
    val totalItems: Int,
    val visibleItemsExact: Float,
    val avgStepPx: Float,
    val scrolledItems: Float,
)

private fun measureLazyListLayout(state: LazyListState): LazyListScrollLayout? {
    val layoutInfo = state.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems <= 0 || visibleItems.isEmpty()) return null
    val viewportStart = layoutInfo.viewportStartOffset
    val viewportEnd = layoutInfo.viewportEndOffset
    val spacing = layoutInfo.mainAxisItemSpacing
    val first = visibleItems.first()
    val firstStep = (first.size + spacing).coerceAtLeast(1)
    val scrolledAbove = (viewportStart - first.offset).coerceAtLeast(0)
    val scrolledItems = first.index + (scrolledAbove.toFloat() / firstStep).coerceIn(0f, 1f)

    var visibleSum = 0.0
    var stepSum = 0
    for (item in visibleItems) {
        val step = (item.size + spacing).coerceAtLeast(1)
        stepSum += step
        val visStart = maxOf(item.offset, viewportStart)
        val visEnd = minOf(item.offset + item.size, viewportEnd)
        val visPx = (visEnd - visStart).coerceAtLeast(0)
        visibleSum += visPx.toDouble() / step
    }
    val visibleItemsExact = visibleSum.toFloat().coerceAtMost(totalItems.toFloat())
    val avgStepPx = (stepSum.toFloat() / visibleItems.size).coerceAtLeast(1f)
    return LazyListScrollLayout(
        totalItems = totalItems,
        visibleItemsExact = visibleItemsExact,
        avgStepPx = avgStepPx,
        scrolledItems = scrolledItems,
    )
}

private fun readLazyListSnapshot(state: LazyListState): ScrollbarSnapshot {
    val canScroll = state.canScrollForward || state.canScrollBackward
    if (!canScroll) return ScrollbarSnapshot.Empty
    val measured = measureLazyListLayout(state) ?: return ScrollbarSnapshot.Empty
    val thumbSizeFraction = (measured.visibleItemsExact / measured.totalItems).coerceIn(0f, 1f)
    val maxScrollable = (measured.totalItems - measured.visibleItemsExact).coerceAtLeast(0f)
    val offsetFraction = when {
        !state.canScrollBackward -> 0f
        !state.canScrollForward -> 1f
        maxScrollable <= 0f -> 0f
        else -> (measured.scrolledItems / maxScrollable).coerceIn(0f, 1f)
    }
    return ScrollbarSnapshot(
        thumbSizeFraction = thumbSizeFraction,
        offsetFraction = offsetFraction,
        canScroll = true,
    )
}

private suspend fun scrollLazyListToFraction(state: LazyListState, fraction: Float) {
    val measured = measureLazyListLayout(state) ?: return
    val totalItems = measured.totalItems
    when {
        fraction <= 0f -> state.scrollToItem(0, 0)
        fraction >= 1f -> {
            val lastIndex = totalItems - 1
            state.scrollToItem(lastIndex, 0)
            val lastSize = state.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: 0
            if (lastSize > 0) state.scrollToItem(lastIndex, lastSize)
        }
        else -> {
            val maxScrollable = (totalItems - measured.visibleItemsExact).coerceAtLeast(0f)
            val exactPos = fraction * maxScrollable
            val targetIndex = exactPos.toInt().coerceIn(0, totalItems - 1)
            val itemFraction = exactPos - targetIndex
            val targetHeight = state.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == targetIndex }?.size?.toFloat()
                ?: measured.avgStepPx
            state.scrollToItem(targetIndex, (itemFraction * targetHeight).roundToInt().coerceAtLeast(0))
        }
    }
}

private fun readEditorSnapshot(editorState: NoteEditorState): ScrollbarSnapshot {
    val metrics = editorState.scrollMetrics
    val canScroll = metrics.maxOffsetPx > 0f && metrics.viewportPx > 0f
    if (!canScroll) return ScrollbarSnapshot.Empty
    val contentHeight = metrics.viewportPx + metrics.maxOffsetPx
    val thumbSizeFraction = if (contentHeight > 0f) {
        (metrics.viewportPx / contentHeight).coerceIn(0f, 1f)
    } else {
        0f
    }
    val offsetFraction = (metrics.offsetPx / metrics.maxOffsetPx).coerceIn(0f, 1f)
    return ScrollbarSnapshot(
        thumbSizeFraction = thumbSizeFraction,
        offsetFraction = offsetFraction,
        canScroll = true,
    )
}

private fun computeThumbHeightPx(
    trackHeightPx: Float,
    thumbSizeFraction: Float,
    minThumbHeightPx: Float,
): Float {
    if (trackHeightPx <= 0f || thumbSizeFraction <= 0f) return 0f
    val minHeight = minThumbHeightPx.coerceAtMost(trackHeightPx)
    return (trackHeightPx * thumbSizeFraction.coerceIn(0f, 1f)).coerceIn(minHeight, trackHeightPx)
}

@Composable
internal fun NoteReadScrollbar(
    state: LazyListState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    VerticalScrollbar(
        readSnapshot = { readLazyListSnapshot(state) },
        isScrolling = { state.isScrollInProgress },
        scrollSignal = {
            (state.firstVisibleItemIndex.toLong() shl 32) xor
                (state.firstVisibleItemScrollOffset.toLong() and 0xffffffffL)
        },
        enabled = enabled,
        onScrollToFraction = { fraction -> scrollLazyListToFraction(state, fraction) },
        modifier = modifier,
    )
}

@Composable
internal fun NoteEditorScrollbar(
    editorState: NoteEditorState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    VerticalScrollbar(
        readSnapshot = { readEditorSnapshot(editorState) },
        isScrolling = { false },
        scrollSignal = { editorState.scrollMetrics.offsetPx.toBits().toLong() },
        enabled = enabled,
        onScrollToFraction = { editorState.scrollToFraction(it) },
        modifier = modifier,
    )
}

@Composable
private fun VerticalScrollbar(
    readSnapshot: () -> ScrollbarSnapshot,
    isScrolling: () -> Boolean,
    scrollSignal: () -> Long,
    enabled: Boolean,
    onScrollToFraction: suspend (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val minThumbHeightPx = with(density) { MinThumbHeight.toPx() }
    val trackPaddingPx = with(density) { TrackVerticalPadding.toPx() }

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var dragThumbHeightPx by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    val lastActivityTimeMs = remember { longArrayOf(0L) }

    val latestReadSnapshot by rememberUpdatedState(readSnapshot)
    val latestIsScrolling by rememberUpdatedState(isScrolling)
    val latestScrollSignal by rememberUpdatedState(scrollSignal)
    val latestOnScrollToFraction by rememberUpdatedState(onScrollToFraction)

    LaunchedEffect(enabled) {
        if (!enabled) {
            visible = false
            isDragging = false
            return@LaunchedEffect
        }
        var initialized = false
        var lastSignal = 0L
        snapshotFlow {
            Triple(
                latestReadSnapshot().canScroll,
                latestIsScrolling() || isDragging,
                latestScrollSignal(),
            )
        }.collect { (canScroll, active, signal) ->
            if (!initialized) {
                initialized = true
                lastSignal = signal
                if (!canScroll) {
                    visible = false
                    isDragging = false
                }
                return@collect
            }
            if (!canScroll) {
                visible = false
                isDragging = false
                lastSignal = signal
                return@collect
            }
            if (active || signal != lastSignal) {
                lastSignal = signal
                lastActivityTimeMs[0] = SystemClock.uptimeMillis()
                visible = true
            }
        }
    }

    LaunchedEffect(visible, isDragging, enabled) {
        if (!enabled || !visible || isDragging) return@LaunchedEffect
        while (true) {
            if (latestIsScrolling()) {
                lastActivityTimeMs[0] = SystemClock.uptimeMillis()
                delay(100L)
                continue
            }
            val remaining = HideDelayMillis - (SystemClock.uptimeMillis() - lastActivityTimeMs[0])
            if (remaining <= 0L) {
                visible = false
                break
            }
            delay(remaining)
        }
    }

    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        snapshotFlow { dragFraction }.collectLatest { fraction ->
            latestOnScrollToFraction(fraction)
        }
    }

    val alphaState = animateFloatAsState(
        targetValue = if (visible && enabled) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "scrollbar-alpha",
    )
    val thumbWidthState = animateDpAsState(
        targetValue = if (isDragging) ThumbWidthDragging else ThumbWidthIdle,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "scrollbar-width",
    )
    val thumbColorState = animateColorAsState(
        targetValue = if (isDragging) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "scrollbar-color",
    )

    val latestVisible by rememberUpdatedState(visible)

    Box(
        modifier = modifier
            .width(TouchTargetWidth)
            .onSizeChanged { containerHeightPx = it.height.toFloat() }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!latestVisible) return@awaitEachGesture
                    val snap = latestReadSnapshot()
                    if (!snap.canScroll) return@awaitEachGesture
                    val trackHeightPx = (containerHeightPx - trackPaddingPx * 2f).coerceAtLeast(0f)
                    val thumbHeightPx = computeThumbHeightPx(trackHeightPx, snap.thumbSizeFraction, minThumbHeightPx)
                    if (trackHeightPx <= 0f || thumbHeightPx <= 0f) return@awaitEachGesture
                    dragThumbHeightPx = thumbHeightPx
                    val usableTrack = (trackHeightPx - dragThumbHeightPx).coerceAtLeast(1f)
                    val thumbTopPx = snap.offsetFraction * usableTrack
                    val pressY = down.position.y - trackPaddingPx
                    val grabOffsetPx = if (pressY in (thumbTopPx - ThumbGrabSlopPx)..(thumbTopPx + dragThumbHeightPx + ThumbGrabSlopPx)) {
                        dragFraction = snap.offsetFraction
                        pressY - thumbTopPx
                    } else {
                        val centered = dragThumbHeightPx / 2f
                        dragFraction = ((pressY - centered) / usableTrack).coerceIn(0f, 1f)
                        centered
                    }
                    isDragging = true
                    down.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            dragFraction = ((change.position.y - trackPaddingPx - grabOffsetPx) / usableTrack).coerceIn(0f, 1f)
                            change.consume()
                        }
                    } finally {
                        lastActivityTimeMs[0] = SystemClock.uptimeMillis()
                        isDragging = false
                    }
                }
            }
            .drawBehind {
                val currentAlpha = alphaState.value
                if (currentAlpha <= 0f || !enabled) return@drawBehind
                val snap = latestReadSnapshot()
                if (!snap.canScroll) return@drawBehind
                val trackHeightPx = (size.height - trackPaddingPx * 2f).coerceAtLeast(0f)
                if (trackHeightPx <= 0f) return@drawBehind
                val thumbHeightPx = if (isDragging) {
                    dragThumbHeightPx
                } else {
                    computeThumbHeightPx(trackHeightPx, snap.thumbSizeFraction, minThumbHeightPx)
                }
                if (thumbHeightPx <= 0f) return@drawBehind
                val usableTrack = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                val activeOffset = if (isDragging) dragFraction else snap.offsetFraction
                val widthPx = thumbWidthState.value.toPx()
                val endPadPx = ThumbEndPadding.toPx()
                val radius = widthPx / 2f
                drawRoundRect(
                    color = thumbColorState.value,
                    topLeft = Offset(
                        x = size.width - endPadPx - widthPx,
                        y = trackPaddingPx + activeOffset * usableTrack,
                    ),
                    size = Size(width = widthPx, height = thumbHeightPx),
                    cornerRadius = CornerRadius(radius, radius),
                    alpha = currentAlpha,
                )
            },
    )
}
