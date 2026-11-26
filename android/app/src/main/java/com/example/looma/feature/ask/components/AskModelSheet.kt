package com.example.looma.feature.ask.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.GeneratorAvailability
import com.example.looma.data.assistant.InferenceRoute
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

private enum class AskSheetPage { Model, Effort }

private val WheelItemHeight = 56.dp
private const val WheelVisibleItems = 3
private val EffortTrackHeight = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AskModelSheet(
    models: List<AnswerModel>,
    choice: ModelChoice?,
    onSelect: (AnswerModel) -> Unit,
    onEffort: (ThinkingEffort) -> Unit,
    onRoute: (InferenceRoute) -> Unit,
    onPrepareModel: () -> Unit,
    onManageModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    var page by rememberSaveable { mutableStateOf(AskSheetPage.Model) }
    val isEffort = page == AskSheetPage.Effort
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LoomaTokens.colors.sheet,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !isEffort),
    ) {
        BackHandler(enabled = isEffort) { page = AskSheetPage.Model }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
        ) {
            FilledTonalIconButton(
                onClick = { if (isEffort) page = AskSheetPage.Model else onDismiss() },
                modifier = Modifier.align(Alignment.CenterStart),
                shapes = IconButtonDefaults.shapes(),
            ) {
                SwapIcon(
                    iconRes = if (isEffort) R.drawable.ic_arrow_back else R.drawable.ic_close,
                    contentDescription = stringResource(if (isEffort) R.string.nav_back else android.R.string.cancel),
                )
            }
            SwapText(
                text = stringResource(if (isEffort) R.string.ask_effort_title else R.string.ask_model_title),
                modifier = Modifier.align(Alignment.Center).semantics { heading() },
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
                    onRoute = onRoute,
                    onPrepareModel = onPrepareModel,
                    onOpenEffort = { page = AskSheetPage.Effort },
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
    onRoute: (InferenceRoute) -> Unit,
    onPrepareModel: () -> Unit,
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
            val routes = choice?.model?.supportedRoutes.orEmpty()
            if (routes.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    ) {
                        routes.forEachIndexed { index, route ->
                            FilledTonalToggleButton(
                                checked = choice?.route == route,
                                onCheckedChange = { if (it) onRoute(route) },
                                shapes = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    routes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                            ) {
                                Text(
                                    stringResource(route.labelRes()),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    AnimatedContent(
                        targetState = choice?.route,
                        transitionSpec = { fadeIn(motion.fastEffectsSpec()).togetherWith(fadeOut(motion.fastEffectsSpec())) },
                        label = "routeDescription",
                    ) { route ->
                        Text(
                            text = route?.let { stringResource(it.descriptionRes()) }.orEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            when (val availability = choice?.model?.availability) {
                is GeneratorAvailability.Downloading -> {
                    LinearWavyProgressIndicator(
                        progress = { availability.progress ?: Float.NaN },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is GeneratorAvailability.Downloadable -> {
                    FilledTonalButton(onClick = onPrepareModel, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.ask_model_download))
                    }
                }
                else -> Unit
            }
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
    val selected = models.indexOfFirst { it.id == choice?.model?.id }.coerceAtLeast(0)
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
                    val effort = choice?.effort?.takeIf { choice.model.id == model.id }
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
            .padding(start = 10.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
        LoomaIcon(
            iconRes = R.drawable.ic_chevron_right,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EffortPage(choice: ModelChoice?, onEffort: (ThinkingEffort) -> Unit) {
    val efforts = choice?.model?.efforts.orEmpty()
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
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
            style = MaterialTheme.typography.headlineSmall,
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
        val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        val fillColor = MaterialTheme.colorScheme.primary
        val onFillColor = MaterialTheme.colorScheme.onPrimary
        val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
        Slider(
            state = sliderState,
            onValueChange = { value ->
                sliderState.value = value
                efforts.getOrNull(value.roundToInt())?.let(onEffort)
            },
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = label },
            interactionSource = interaction,
            thumb = { Box(Modifier.size(1.dp)) },
            track = { state ->
                val count = efforts.size
                val fraction = (state.value / (count - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(EffortTrackHeight)
                        .clip(CircleShape),
                ) {
                    val radius = size.height / 2f
                    drawRoundRect(
                        color = trackColor,
                        cornerRadius = CornerRadius(radius, radius),
                    )
                    val fillWidth = (size.height + (size.width - size.height) * fraction).coerceIn(size.height, size.width)
                    drawRoundRect(
                        color = fillColor,
                        size = Size(fillWidth, size.height),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                    val barX = (fillWidth - 14.dp.toPx()).coerceIn(radius, size.width - 14.dp.toPx())
                    val barHalf = 11.dp.toPx()
                    drawLine(
                        color = onFillColor,
                        start = Offset(barX, size.height / 2f - barHalf),
                        end = Offset(barX, size.height / 2f + barHalf),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    val dotRadius = 2.5.dp.toPx()
                    val stepSpan = (size.width - size.height) / (count - 1).coerceAtLeast(1)
                    for (i in 0 until count) {
                        val cx = radius + stepSpan * i
                        if (abs(cx - barX) > 10.dp.toPx()) {
                            drawCircle(
                                color = if (cx < fillWidth) onFillColor else dotColor,
                                radius = dotRadius,
                                center = Offset(cx, size.height / 2f),
                            )
                        }
                    }
                }
            },
        )
        Text(
            stringResource(R.string.ask_effort_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
