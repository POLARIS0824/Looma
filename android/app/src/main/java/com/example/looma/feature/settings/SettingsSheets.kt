@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.example.looma.feature.settings

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.looma.R
import com.example.looma.data.settings.S3ImageBedConfig
import com.example.looma.domain.markdown.ToolbarItem
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun ImageBedSheet(
    config: S3ImageBedConfig,
    onSave: suspend (S3ImageBedConfig) -> Unit,
    credentialsUnavailable: Boolean,
    onDismiss: () -> Unit,
) {
    val endpoint = rememberTextFieldState(config.endpoint)
    val region = rememberTextFieldState(config.region)
    val bucket = rememberTextFieldState(config.bucket)
    val accessKey = rememberTextFieldState(config.accessKey)
    val secretKey = rememberTextFieldState(config.secretKey)
    val pathPrefix = rememberTextFieldState(config.pathPrefix)
    val publicBaseUrl = rememberTextFieldState(config.publicBaseUrl)
    var pathStyle by remember { mutableStateOf(config.pathStyle) }
    var publicRead by remember { mutableStateOf(config.publicRead) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val sheetState = rememberExpandedSheetState()
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        containerColor = LoomaTokens.colors.sheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_image_bed_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (credentialsUnavailable) {
                Text(stringResource(R.string.settings_image_bed_credentials_unavailable))
            }
            Text(
                text = stringResource(R.string.settings_image_bed_credentials_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ImageBedField(state = endpoint, label = stringResource(R.string.settings_image_bed_endpoint))
            ImageBedField(state = region, label = stringResource(R.string.settings_image_bed_region))
            ImageBedField(state = bucket, label = stringResource(R.string.settings_image_bed_bucket))
            ImageBedField(state = accessKey, label = stringResource(R.string.settings_image_bed_access_key))
            OutlinedSecureTextField(
                state = secretKey,
                label = { Text(stringResource(R.string.settings_image_bed_secret_key)) },
                labelPosition = TextFieldLabelPosition.Inside(),
                shape = OutlinedTextFieldDefaults.roundedShape,
                modifier = Modifier.fillMaxWidth(),
            )
            ImageBedField(state = pathPrefix, label = stringResource(R.string.settings_image_bed_path_prefix))
            ImageBedField(state = publicBaseUrl, label = stringResource(R.string.settings_image_bed_public_url))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SettingsSwitchRow(
                    index = 0,
                    count = 2,
                    title = stringResource(R.string.settings_image_bed_path_style),
                    description = stringResource(R.string.settings_image_bed_path_style_desc),
                    checked = pathStyle,
                    enabled = true,
                    onCheckedChange = { pathStyle = it },
                )
                SettingsSwitchRow(
                    index = 1,
                    count = 2,
                    title = stringResource(R.string.settings_image_bed_public_read),
                    description = stringResource(R.string.settings_image_bed_public_read_desc),
                    checked = publicRead,
                    enabled = true,
                    onCheckedChange = { publicRead = it },
                )
            }
            if (saveFailed) {
                Text(
                    text = stringResource(R.string.settings_image_bed_save_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss, enabled = !saving, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.dialog_cancel))
                }
                TextButton(
                    enabled = !saving,
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        val updatedConfig = S3ImageBedConfig(
                            endpoint = endpoint.text.toString().trim(),
                            region = region.text.toString().trim(),
                            bucket = bucket.text.toString().trim(),
                            accessKey = accessKey.text.toString().trim(),
                            secretKey = secretKey.text.toString(),
                            pathPrefix = pathPrefix.text.toString().trim(),
                            publicBaseUrl = publicBaseUrl.text.toString().trim(),
                            pathStyle = pathStyle,
                            publicRead = publicRead,
                        )
                        saving = true
                        saveFailed = false
                        scope.launch {
                            try {
                                onSave(updatedConfig)
                            } catch (_: IOException) {
                                saveFailed = true
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.dialog_save)) }
            }
        }
    }
}

@Composable
private fun ImageBedField(state: TextFieldState, label: String) {
    OutlinedTextField(
        state = state,
        label = { Text(label) },
        labelPosition = TextFieldLabelPosition.Inside(),
        shape = OutlinedTextFieldDefaults.roundedShape,
        lineLimits = TextFieldLineLimits.SingleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ToolbarOrderSheet(
    current: List<ToolbarItem>,
    onSave: (List<ToolbarItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var order by remember { mutableStateOf(current.filter { it.isAvailableInToolbar() }) }
    var draggedItem by remember { mutableStateOf<ToolbarItem?>(null) }
    var viewportY by remember { mutableFloatStateOf(0f) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val handleZonePx = with(density) { 64.dp.toPx() }
    val edgeZonePx = with(density) { 48.dp.toPx() }
    val maxAutoScrollPx = with(density) { 14.dp.toPx() }
    val rowTops = remember { FloatArray(ToolbarItem.entries.size) }
    val rowHeights = remember { FloatArray(ToolbarItem.entries.size) }

    fun syncDragPosition() {
        val activeItem = draggedItem ?: return
        val currentIndex = order.indexOf(activeItem)
        if (currentIndex == -1) return
        val contentY = viewportY + scrollState.value
        val desiredTop = contentY - grabOffset
        val dragCenterY = desiredTop + rowHeights[currentIndex] / 2f
        val otherMiddles = order.indices.mapNotNull { idx ->
            if (idx == currentIndex) null else rowTops[idx] + rowHeights[idx] / 2f
        }
        val targetIndex = toolbarDropIndex(dragCenterY, otherMiddles)
        if (targetIndex != currentIndex) {
            order = order.toMutableList().apply {
                add(targetIndex, removeAt(currentIndex))
            }
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }

    LaunchedEffect(draggedItem) {
        while (draggedItem != null) {
            withFrameNanos { }
            val speed = toolbarEdgeScrollSpeed(
                y = viewportY,
                viewportHeight = viewportHeight.toFloat(),
                edge = edgeZonePx,
                maxSpeed = maxAutoScrollPx,
            )
            if (speed != 0f) {
                val consumed = scrollState.dispatchRawDelta(speed)
                if (consumed != 0f) {
                    syncDragPosition()
                }
            }
        }
    }

    val sheetState = rememberExpandedSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LoomaTokens.colors.sheet,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_toolbar_order),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        viewportWidth = size.width
                        viewportHeight = size.height
                    }
                    .pointerInput(layoutDirection, handleZonePx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (order.isEmpty()) return@awaitEachGesture
                            val inHandleZone = if (layoutDirection == LayoutDirection.Rtl) {
                                down.position.x <= handleZonePx
                            } else {
                                down.position.x >= viewportWidth - handleZonePx
                            }
                            val slop = viewConfiguration.touchSlop
                            var currentPos = down.position
                            var readyToDrag = false

                            if (inHandleZone) {
                                var travel = Offset.Zero
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                    if (!change.pressed || change.isConsumed) return@awaitEachGesture
                                    currentPos = change.position
                                    travel += change.position - change.previousPosition
                                    if (abs(travel.y) > slop || travel.getDistance() > slop) {
                                        change.consume()
                                        readyToDrag = true
                                        break
                                    }
                                }
                            } else {
                                var releasedOrMoved = false
                                val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    var travel = Offset.Zero
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed || change.isConsumed) {
                                            releasedOrMoved = true
                                            break
                                        }
                                        currentPos = change.position
                                        travel += change.position - change.previousPosition
                                        if (travel.getDistance() > slop) {
                                            releasedOrMoved = true
                                            break
                                        }
                                    }
                                } == null
                                if (timedOut && !releasedOrMoved) {
                                    readyToDrag = true
                                }
                            }

                            if (!readyToDrag) return@awaitEachGesture

                            val contentDownY = down.position.y + scrollState.value
                            val grabbedIndex = order.indices.minByOrNull { idx ->
                                val middle = rowTops[idx] + rowHeights[idx] / 2f
                                abs(middle - contentDownY)
                            } ?: 0
                            draggedItem = order[grabbedIndex]
                            viewportY = currentPos.y
                            grabOffset = (down.position.y + scrollState.value) - rowTops[grabbedIndex]
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            syncDragPosition()

                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (!change.pressed) break
                                    viewportY = change.position.y
                                    syncDragPosition()
                                }
                            } finally {
                                if (draggedItem != null) {
                                    draggedItem = null
                                    viewportY = 0f
                                    grabOffset = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                }
                            }
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    order.forEachIndexed { index, item ->
                        key(item) {
                            val label = stringResource(item.labelRes())
                            val upLabel = stringResource(R.string.toolbar_move_up, label)
                            val downLabel = stringResource(R.string.toolbar_move_down, label)
                            val isDragged = draggedItem == item
                            val listColors = if (isDragged) {
                                ListItemDefaults.segmentedColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    trailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            } else {
                                ListItemDefaults.segmentedColors(
                                    containerColor = LoomaTokens.colors.card,
                                    trailingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SegmentedListItem(
                                shapes = ListItemDefaults.segmentedShapes(index = index, count = order.size),
                                colors = listColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .zIndex(if (isDragged) 1f else 0f)
                                    .onPlaced { coordinates ->
                                        rowTops[index] = coordinates.positionInParent().y
                                        rowHeights[index] = coordinates.size.height.toFloat()
                                    }
                                    .graphicsLayer {
                                        translationY = if (draggedItem == item) {
                                            (viewportY + scrollState.value - grabOffset) - rowTops[index]
                                        } else {
                                            0f
                                        }
                                        shadowElevation = if (draggedItem == item) 6.dp.toPx() else 0f
                                    }
                                    .semantics {
                                        customActions = buildList {
                                            if (index > 0) {
                                                add(
                                                    CustomAccessibilityAction(upLabel) {
                                                        order = order.toMutableList().apply {
                                                            add(index - 1, removeAt(index))
                                                        }
                                                        true
                                                    },
                                                )
                                            }
                                            if (index < order.lastIndex) {
                                                add(
                                                    CustomAccessibilityAction(downLabel) {
                                                        order = order.toMutableList().apply {
                                                            add(index + 1, removeAt(index))
                                                        }
                                                        true
                                                    },
                                                )
                                            }
                                        }
                                    },
                                leadingContent = {
                                    LoomaIcon(iconRes = item.iconRes(), contentDescription = null)
                                },
                                trailingContent = {
                                    LoomaIcon(iconRes = R.drawable.ic_drag_handle, contentDescription = null)
                                },
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }
            TextButton(onClick = {
                draggedItem = null
                order = ToolbarItem.DEFAULT_ORDER.filter { it.isAvailableInToolbar() }
            }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.toolbar_reset)) }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.dialog_cancel)) }
                Button(onClick = {
                    onSave(order + current.filterNot { it.isAvailableInToolbar() })
                }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.dialog_save)) }
            }
        }
    }
}

internal fun toolbarDropIndex(y: Float, middles: List<Float>): Int =
    middles.count { it < y }.coerceIn(0, middles.size)

internal fun toolbarEdgeScrollSpeed(
    y: Float,
    viewportHeight: Float,
    edge: Float,
    maxSpeed: Float,
): Float {
    if (viewportHeight <= 0f || edge <= 0f) return 0f
    return when {
        y < edge -> -maxSpeed * ((edge - y) / edge).coerceIn(0f, 1f)
        y > viewportHeight - edge -> maxSpeed * ((y - (viewportHeight - edge)) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
}

private fun ToolbarItem.isAvailableInToolbar(): Boolean =
    this != ToolbarItem.InsertFile

private fun ToolbarItem.iconRes(): Int = when (this) {
    ToolbarItem.Undo -> R.drawable.ic_editor_undo
    ToolbarItem.Redo -> R.drawable.ic_editor_redo
    ToolbarItem.WikiLink -> R.drawable.ic_editor_wikilink
    ToolbarItem.Bold -> R.drawable.ic_editor_bold
    ToolbarItem.Italic -> R.drawable.ic_editor_italic
    ToolbarItem.Strikethrough -> R.drawable.ic_editor_strikethrough
    ToolbarItem.Highlight -> R.drawable.ic_editor_highlight
    ToolbarItem.Link -> R.drawable.ic_editor_link
    ToolbarItem.InlineCode -> R.drawable.ic_editor_code
    ToolbarItem.Heading1 -> R.drawable.ic_editor_h1
    ToolbarItem.Heading2 -> R.drawable.ic_editor_h2
    ToolbarItem.Heading3 -> R.drawable.ic_editor_h3
    ToolbarItem.BulletList -> R.drawable.ic_editor_bullet_list
    ToolbarItem.NumberedList -> R.drawable.ic_editor_numbered_list
    ToolbarItem.Checklist -> R.drawable.ic_editor_checklist
    ToolbarItem.Quote -> R.drawable.ic_editor_quote
    ToolbarItem.CodeBlock -> R.drawable.ic_editor_code_block
    ToolbarItem.Table -> R.drawable.ic_editor_table
    ToolbarItem.Divider -> R.drawable.ic_editor_divider
    ToolbarItem.InsertImage -> R.drawable.ic_editor_image
    ToolbarItem.InsertFile -> R.drawable.folder_open_24px
}

private fun ToolbarItem.labelRes(): Int = when (this) {
    ToolbarItem.Undo -> R.string.editor_undo
    ToolbarItem.Redo -> R.string.editor_redo
    ToolbarItem.WikiLink -> R.string.editor_wikilink
    ToolbarItem.Bold -> R.string.editor_bold
    ToolbarItem.Italic -> R.string.editor_italic
    ToolbarItem.Strikethrough -> R.string.editor_strikethrough
    ToolbarItem.Highlight -> R.string.editor_highlight
    ToolbarItem.Link -> R.string.editor_link
    ToolbarItem.InlineCode -> R.string.editor_inline_code
    ToolbarItem.Heading1 -> R.string.editor_h1
    ToolbarItem.Heading2 -> R.string.editor_h2
    ToolbarItem.Heading3 -> R.string.editor_h3
    ToolbarItem.BulletList -> R.string.editor_bullet_list
    ToolbarItem.NumberedList -> R.string.editor_numbered_list
    ToolbarItem.Checklist -> R.string.editor_checklist
    ToolbarItem.Quote -> R.string.editor_quote
    ToolbarItem.CodeBlock -> R.string.editor_code_block
    ToolbarItem.Table -> R.string.editor_table
    ToolbarItem.Divider -> R.string.editor_divider
    ToolbarItem.InsertImage -> R.string.editor_insert_image
    ToolbarItem.InsertFile -> error("Unavailable toolbar item")
}
