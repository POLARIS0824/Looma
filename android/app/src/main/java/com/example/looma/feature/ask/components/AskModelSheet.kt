package com.example.looma.feature.ask.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.ModelChoice
import com.example.looma.data.assistant.ThinkingEffort
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal enum class AskSheetPage { Model, Effort }

private val WheelItemHeight = 56.dp
private const val WheelVisibleItems = 3
private val EffortTrackHeight = 40.dp
private val EffortTrackCorner = 12.dp
private val EffortThumbSize = DpSize(4.dp, 52.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AskModelSheet(
    start: AskSheetPage,
    models: List<AnswerModel>,
    choice: ModelChoice?,
    onPage: (AskSheetPage) -> Unit,
    onSelect: (AnswerModel) -> Unit,
    onEffort: (ThinkingEffort) -> Unit,
    onManageModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val entry = rememberSaveable { start }
    var page by rememberSaveable { mutableStateOf(start) }
    val showPage = { target: AskSheetPage ->
        page = target
        onPage(target)
    }
    val back = page == AskSheetPage.Effort && entry == AskSheetPage.Model
    val titleStart by animateDpAsState(if (back) 0.dp else 12.dp, motion.fastSpatialSpec(), label = "sheetTitleStart")
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LoomaTokens.colors.sheet,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !back),
    ) {
        BackHandler(enabled = back) { showPage(AskSheetPage.Model) }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = back,
                enter = expandHorizontally(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
                exit = shrinkHorizontally(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
            ) {
                Row {
                    IconButton(onClick = { showPage(AskSheetPage.Model) }, shapes = IconButtonDefaults.shapes()) {
                        LoomaIcon(R.drawable.ic_arrow_back, stringResource(R.string.nav_back))
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }
            SwapText(
                text = stringResource(if (page == AskSheetPage.Model) R.string.ask_model_title else R.string.ask_effort_title),
                modifier = Modifier.padding(start = titleStart).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val direction = if (targetState == AskSheetPage.Effort) 1 else -1
                (slideInHorizontally(motion.defaultSpatialSpec()) { direction * it / 5 } + fadeIn(motion.fastEffectsSpec()))
                    .togetherWith(slideOutHorizontally(motion.defaultSpatialSpec()) { -direction * it / 5 } + fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
            },
            label = "askSheetPage",
        ) { current ->
            when (current) {
                AskSheetPage.Model -> ModelPage(
                    models = models,
                    choice = choice,
                    onSelect = onSelect,
                    onOpenEffort = { showPage(AskSheetPage.Effort) },
                    onManageModels = onManageModels,
                )
                AskSheetPage.Effort -> EffortPage(choice = choice, onEffort = onEffort)
            }
        }
    }
}

@Composable
private fun ModelPage(
    models: List<AnswerModel>,
    choice: ModelChoice?,
    onSelect: (AnswerModel) -> Unit,
    onOpenEffort: () -> Unit,
    onManageModels: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (models.isEmpty()) {
            Text(
                stringResource(R.string.ask_models_empty),
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ModelWheel(models = models, choice = choice, onSelect = onSelect, onOpenEffort = onOpenEffort)
            AnimatedContent(
                targetState = choice?.model,
                transitionSpec = { fadeIn(motion.fastEffectsSpec()).togetherWith(fadeOut(motion.fastEffectsSpec())) },
                label = "modelDescription",
            ) { model ->
                Text(
                    text = model?.displayDescription().orEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilledTonalButton(onClick = onManageModels, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.models_manage))
        }
    }
}

@Composable
private fun ModelWheel(
    models: List<AnswerModel>,
    choice: ModelChoice?,
    onSelect: (AnswerModel) -> Unit,
    onOpenEffort: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val selected = models.indexOf(choice?.model).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val centered by remember(listState) { derivedStateOf { listState.centeredIndex() } }
    val select by rememberUpdatedState(onSelect)
    val currentModels by rememberUpdatedState(models)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.centeredIndex() }
            .filter { !it.first }
            .map { it.second }
            .distinctUntilChanged()
            .collect { index -> currentModels.getOrNull(index)?.let(select) }
    }
    LaunchedEffect(selected) {
        if (!listState.isScrollInProgress && listState.centeredIndex() != selected) listState.animateScrollToItem(selected)
    }
    Box(Modifier.fillMaxWidth().height(WheelItemHeight * WheelVisibleItems), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth().height(WheelItemHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = WheelItemHeight * (WheelVisibleItems / 2)),
            flingBehavior = rememberSnapFlingBehavior(listState),
        ) {
            itemsIndexed(models, key = { _, model -> model.id }) { index, model ->
                val isCentered = index == centered
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(WheelItemHeight)
                        .graphicsLayer {
                            val info = listState.layoutInfo
                            val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@graphicsLayer
                            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val distance = ((item.offset + item.size / 2f - center) / item.size).coerceIn(-2f, 2f)
                            val amount = abs(distance)
                            scaleX = 1f - 0.1f * amount
                            scaleY = 1f - 0.1f * amount
                            alpha = 1f - 0.4f * amount
                            rotationX = -18f * distance
                        }
                        .clip(CircleShape)
                        .clickable(enabled = !isCentered) { scope.launch { listState.animateScrollToItem(index) } }
                        .padding(start = 20.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        model.displayLabel(),
                        modifier = Modifier.weight(1f, fill = false),
                        style = if (isCentered) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium,
                        color = if (isCentered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(model.sourceRes()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    val effort = choice?.effort?.takeIf { choice.model == model }
                    if (isCentered && effort != null && model.efforts.size > 1) {
                        EffortMeterButton(efforts = model.efforts, current = effort, onClick = onOpenEffort)
                    }
                }
            }
        }
    }
}

private fun LazyListState.centeredIndex(): Int {
    val info = layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: firstVisibleItemIndex
}

@Composable
private fun EffortMeterButton(efforts: List<ThinkingEffort>, current: ThinkingEffort, onClick: () -> Unit) {
    val motion = MaterialTheme.motionScheme
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = stringResource(R.string.ask_effort_title), onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            efforts.forEach { effort ->
                val active = effort == current
                val height by animateDpAsState(if (active) 12.dp else 4.dp, motion.fastSpatialSpec(), label = "effortDot")
                Box(
                    Modifier.size(width = 4.dp, height = height).background(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
                )
            }
        }
        SwapText(
            text = stringResource(current.labelRes()),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun EffortPage(choice: ModelChoice?, onEffort: (ThinkingEffort) -> Unit) {
    val efforts = choice?.model?.efforts.orEmpty()
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (choice == null || efforts.size < 2) {
            Text(
                stringResource(R.string.ask_models_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val label = stringResource(choice.effort.labelRes())
        SwapText(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        val sliderState = remember(efforts) {
            SliderState(
                value = efforts.indexOf(choice.effort).toFloat(),
                steps = efforts.size - 2,
                trackRange = 0f..(efforts.size - 1).toFloat(),
            )
        }
        LaunchedEffect(choice.effort) {
            val index = efforts.indexOf(choice.effort).toFloat()
            if (sliderState.value != index) sliderState.value = index
        }
        val interaction = remember { MutableInteractionSource() }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(
                state = sliderState,
                onValueChange = { value ->
                    sliderState.value = value
                    efforts.getOrNull(value.roundToInt())?.let(onEffort)
                },
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = label },
                interactionSource = interaction,
                thumb = {
                    SliderDefaults.Thumb(interactionSource = interaction, isVertical = false, thumbSize = EffortThumbSize)
                },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        trackCornerSize = EffortTrackCorner,
                        modifier = Modifier.height(EffortTrackHeight),
                    )
                },
            )
            EffortStops(efforts = efforts)
        }
        Text(
            stringResource(R.string.ask_effort_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EffortStops(efforts: List<ThinkingEffort>) {
    Layout(
        content = {
            efforts.forEach { effort ->
                Text(
                    stringResource(effort.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val inset = EffortTrackCorner.roundToPx()
        val span = (width - inset * 2).coerceAtLeast(0)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val stop = inset + span * index / (placeables.size - 1).coerceAtLeast(1)
                val x = (stop - placeable.width / 2).coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                placeable.placeRelative(x, 0)
            }
        }
    }
}
