package com.example.looma.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.ui.theme.TABULAR_FIGURES

enum class FeatureStatusTone { Default, Error }

@Immutable
sealed interface FeatureProgress {
    data object Indeterminate : FeatureProgress
    data class Determinate(val fraction: Float) : FeatureProgress
}

@Immutable
data class FeatureStatus(
    val label: String,
    val title: String,
    val detail: String,
    val tone: FeatureStatusTone = FeatureStatusTone.Default,
    val progress: FeatureProgress? = null,
    val note: String? = null,
)

private sealed interface StatusSlot {
    data object Loading : StatusSlot
    data object Hidden : StatusSlot
    data class Shown(val status: FeatureStatus) : StatusSlot
}

@Composable
fun FeatureStatusCard(
    status: FeatureStatus?,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val motion = MaterialTheme.motionScheme
    val slot = when {
        loading -> StatusSlot.Loading
        status == null -> StatusSlot.Hidden
        else -> StatusSlot.Shown(status)
    }
    AnimatedContent(
        targetState = slot,
        modifier = modifier.fillMaxWidth(),
        contentKey = { target ->
            if (target is StatusSlot.Shown) Triple(target.status.label, target.status.title, target.status.tone) else target
        },
        transitionSpec = {
            (fadeIn(motion.defaultEffectsSpec()) +
                scaleIn(initialScale = 0.92f, animationSpec = motion.defaultSpatialSpec())) togetherWith
                fadeOut(motion.fastEffectsSpec())
        },
        label = "featureStatus",
    ) { target ->
        when (target) {
            StatusSlot.Loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                DelayedLoadingIndicator(loading = true)
            }
            StatusSlot.Hidden -> Unit
            is StatusSlot.Shown -> StatusCard(target.status)
        }
    }
}

@Composable
private fun StatusCard(status: FeatureStatus) {
    val colors = MaterialTheme.colorScheme
    val (labelContainer, labelContent) = when (status.tone) {
        FeatureStatusTone.Default -> colors.primary to colors.onPrimary
        FeatureStatusTone.Error -> colors.errorContainer to colors.onErrorContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = labelContainer, contentColor = labelContent, shape = CircleShape) {
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Text(
                text = status.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(color = colors.surface, contentColor = colors.onSurface, shape = CircleShape) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR_FIGURES),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            when (val progress = status.progress) {
                is FeatureProgress.Determinate -> LinearWavyProgressIndicator(
                    progress = { progress.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FeatureProgress.Indeterminate -> LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                null -> Unit
            }
            status.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
