package com.example.looma.ui.markdown

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.example.looma.domain.markdown.ImageSize
import com.example.looma.domain.markdown.ImageSource
import com.example.looma.ui.image.LoomaAsyncImage
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalImageSourceResolver = staticCompositionLocalOf<(String) -> ImageSource?> { { null } }

@Composable
internal fun rememberImageSource(link: String, readAspectRatio: Boolean = true): ImageResolution {
    val resolver = LocalImageSourceResolver.current
    var resolved by remember(link, resolver, readAspectRatio) {
        mutableStateOf<ImageResolution>(ImageResolution.Pending)
    }
    LaunchedEffect(link, resolver, readAspectRatio) {
        resolved = withContext(Dispatchers.IO) {
            val source = resolver(link) ?: return@withContext ImageResolution.Missing
            ImageResolution.Found(source, if (readAspectRatio) source.aspectRatio() else null)
        }
    }
    return resolved
}

private fun ImageSource.aspectRatio(): Float? = when (this) {
    is ImageSource.Local -> runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val rotation = when (bounds.outMimeType) {
            "image/jpeg", "image/webp", "image/heic", "image/heif" ->
                runCatching { ExifInterface(path).rotationDegrees }.getOrDefault(0)
            else -> 0
        }
        if (rotation % 180 == 0) bounds.outWidth.toFloat() / bounds.outHeight
        else bounds.outHeight.toFloat() / bounds.outWidth
    }.getOrNull()
    is ImageSource.Remote -> null
}

internal sealed interface ImageResolution {
    data object Pending : ImageResolution
    data object Missing : ImageResolution
    data class Found(val source: ImageSource, val aspectRatio: Float?) : ImageResolution
}

internal object InlineMarkdownImageTransformer : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? = null

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((String, Size) -> Unit)?,
    ): PlaceholderConfig = PlaceholderConfig(Size(20f, 20f))
}

@Composable
internal fun InlineMarkdownImage(link: String) {
    val source = (rememberImageSource(link, readAspectRatio = false) as? ImageResolution.Found)?.source ?: return
    LoomaAsyncImage(
        source = source,
        modifier = Modifier.fillMaxSize(),
        contentDescription = "Image",
    )
}

internal fun imageFrameModifier(size: ImageSize?, aspectRatio: Float?): Modifier = Modifier.layout { measurable, constraints ->
    val limits = Constraints.fitPrioritizingWidth(
        minWidth = constraints.minWidth,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else MaxImageDimension.roundToPx(),
        minHeight = constraints.minHeight,
        maxHeight = MaxImageDimension.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight),
    )
    val width = size?.width?.dp?.roundToPx()?.let(limits::constrainWidth)
    val height = size?.height?.dp?.roundToPx()?.let(limits::constrainHeight)
    val frame = when {
        width != null && height != null -> Constraints.fixed(width, height)
        aspectRatio != null -> {
            val ratio = aspectRatio.toDouble()
            val preferredWidth = width?.toDouble() ?: height?.times(ratio) ?: limits.maxWidth.toDouble()
            val fittedWidth = minOf(preferredWidth, limits.maxWidth.toDouble(), limits.maxHeight * ratio)
            Constraints.fixed(
                limits.constrainWidth(fittedWidth.roundToInt().coerceAtLeast(1)),
                limits.constrainHeight((fittedWidth / ratio).roundToInt().coerceAtLeast(1)),
            )
        }
        else -> limits.copy(
            minWidth = width ?: if (height == null) limits.maxWidth else limits.minWidth,
            maxWidth = width ?: limits.maxWidth,
            minHeight = height ?: limits.minHeight,
            maxHeight = height ?: limits.maxHeight,
        )
    }
    val placeable = measurable.measure(frame)
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

internal fun placeholderFrameModifier(size: ImageSize?): Modifier = imageFrameModifier(size, 4f / 3f)

private val MaxImageDimension = 2048.dp

internal fun ImageSize?.imageContentScale(): ContentScale =
    if (this?.width != null && height != null) ContentScale.FillBounds else ContentScale.Fit
