package com.example.looma.feature.ask.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.looma.R
import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.GenerationMeta
import com.example.looma.data.assistant.InferenceOrigin
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.ModelSource
import com.example.looma.data.assistant.ThinkingEffort
import com.example.looma.ui.components.LoomaIcon

@StringRes
internal fun ThinkingEffort.labelRes(): Int = when (this) {
    ThinkingEffort.Off -> R.string.ask_effort_off
    ThinkingEffort.Low -> R.string.ask_effort_low
    ThinkingEffort.Medium -> R.string.ask_effort_medium
    ThinkingEffort.High -> R.string.ask_effort_high
}

@StringRes
internal fun AnswerModel.nameRes(): Int = when (source) {
    ModelSource.Hybrid -> R.string.ask_model_hybrid
    ModelSource.Local -> R.string.ask_model_local
    ModelSource.Api -> R.string.ask_model_api
}

@StringRes
internal fun AnswerModel.descriptionRes(): Int = when (source) {
    ModelSource.Hybrid -> R.string.ask_model_hybrid_description
    ModelSource.Local -> R.string.ask_model_local_description
    ModelSource.Api -> R.string.ask_model_api_description
}

@StringRes
internal fun AnswerModel.sourceRes(): Int = when (source) {
    ModelSource.Hybrid -> R.string.ask_source_hybrid
    ModelSource.Local -> R.string.ask_source_local
    ModelSource.Api -> R.string.ask_source_api
}

@StringRes
internal fun InferenceRoute.labelRes(): Int = when (this) {
    InferenceRoute.PreferOnDevice -> R.string.ask_route_prefer_on_device
    InferenceRoute.OnDeviceOnly -> R.string.ask_route_on_device_only
    InferenceRoute.PreferCloud -> R.string.ask_route_prefer_cloud
    InferenceRoute.CloudOnly -> R.string.ask_route_cloud_only
}

@StringRes
internal fun InferenceRoute.descriptionRes(): Int = when (this) {
    InferenceRoute.PreferOnDevice -> R.string.ask_route_prefer_on_device_desc
    InferenceRoute.OnDeviceOnly -> R.string.ask_route_on_device_only_desc
    InferenceRoute.PreferCloud -> R.string.ask_route_prefer_cloud_desc
    InferenceRoute.CloudOnly -> R.string.ask_route_cloud_only_desc
}

@StringRes
internal fun GenerationMeta.originLabelRes(): Int = when (origin) {
    InferenceOrigin.OnDevice -> R.string.ask_origin_on_device
    InferenceOrigin.Cloud -> R.string.ask_origin_cloud
    InferenceOrigin.LocalFile -> R.string.ask_origin_local_file
}

@Composable
internal fun AnswerModel.displayLabel(): String = displayName?.takeIf { it.isNotBlank() } ?: stringResource(nameRes())

@Composable
internal fun AnswerModel.displayDescription(): String = description?.takeIf { it.isNotBlank() } ?: stringResource(descriptionRes())

@Composable
internal fun SwapText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    textAlign: TextAlign? = null,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        contentAlignment = Alignment.Center,
        transitionSpec = {
            (slideInVertically(motion.fastSpatialSpec()) { it / 2 } + fadeIn(motion.fastEffectsSpec()))
                .togetherWith(slideOutVertically(motion.fastSpatialSpec()) { -it / 2 } + fadeOut(motion.fastEffectsSpec()))
                .using(SizeTransform(clip = false) { _, _ -> motion.fastSpatialSpec() })
        },
        label = "swapText",
    ) { value ->
        Text(value, style = style, textAlign = textAlign, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SwapIcon(iconRes: Int, contentDescription: String) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = iconRes to contentDescription,
        transitionSpec = { fadeIn(motion.fastEffectsSpec()).togetherWith(fadeOut(motion.fastEffectsSpec())) },
        label = "swapIcon",
    ) { (icon, description) -> LoomaIcon(icon, description) }
}
