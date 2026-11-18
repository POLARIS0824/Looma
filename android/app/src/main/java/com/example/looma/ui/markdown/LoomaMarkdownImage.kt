package com.example.looma.ui.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import com.example.looma.R
import com.example.looma.domain.markdown.splitImageSize
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.image.rememberImageRequest
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.ImageAltTooltip
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink
import org.intellij.markdown.ast.getTextInNode

@Composable
internal fun LoomaMarkdownImage(model: MarkdownComponentModel, modifier: Modifier = Modifier) {
    val link = model.node.resolveImageLink(model.content, LocalReferenceLinkHandler.current) ?: return
    val (target, linkSize) = splitImageSize(link)
    val (label, altSize) = splitImageSize(model.node.resolveImageAlt(model.content).orEmpty())
    val embed = model.node.getTextInNode(model.content)
    val wikiSize = if (embed.startsWith("![[") && embed.endsWith("]]")) {
        splitImageSize(embed.substring(3, embed.length - 2)).second
    } else {
        null
    }
    val size = altSize ?: wikiSize ?: linkSize
    val reference = if (size != null) "$target|${size.suffix}" else target
    val alt = label.takeIf { it.isNotBlank() }
    var attempt by remember(reference, LocalImageSourceResolver.current) { mutableStateOf(0) }
    val resolution = rememberImageSource(reference, attempt)
    ImageAltTooltip(alt) {
        when (resolution) {
            ImageResolution.Pending -> ImagePlaceholder(placeholderFrameModifier(size))
            ImageResolution.Missing -> ImageFailure(placeholderFrameModifier(size)) { attempt++ }
            is ImageResolution.Found -> key(attempt) {
                val imageSize = resolution.source.size ?: size
                val sizeResolver = rememberConstraintsSizeResolver()
                val request = rememberImageRequest(resolution.source, reload = attempt > 0)
                val constrainedRequest = remember(request, sizeResolver) {
                    request.newBuilder().size(sizeResolver).build()
                }
                val scale = imageSize.imageContentScale()
                val painter = rememberAsyncImagePainter(constrainedRequest, contentScale = scale)
                val state by painter.state.collectAsState()
                val aspect = resolution.aspectRatio
                    ?: (state as? AsyncImagePainter.State.Success)?.result?.image?.let { image ->
                        if (image.width > 0 && image.height > 0) image.width.toFloat() / image.height else null
                    }
                Box(
                    modifier = imageFrameModifier(imageSize, aspect).then(sizeResolver),
                    propagateMinConstraints = true,
                ) {
                    when (state) {
                        is AsyncImagePainter.State.Success -> Image(
                            painter = painter,
                            contentDescription = alt,
                            contentScale = scale,
                            modifier = modifier,
                        )
                        is AsyncImagePainter.State.Error -> ImageFailure(
                            if (aspect == null) placeholderFrameModifier(imageSize) else Modifier.fillMaxSize(),
                        ) { attempt++ }
                        else -> ImagePlaceholder(
                            if (aspect == null) placeholderFrameModifier(imageSize) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImageFailure(modifier: Modifier, onRetry: () -> Unit) {
    val retry = stringResource(R.string.markdown_image_retry)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onRetry),
        contentAlignment = Alignment.Center,
    ) {
        if (maxWidth >= 64.dp && maxHeight >= 32.dp) {
            Text(retry, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        } else {
            LoomaIcon(R.drawable.ic_retry, contentDescription = retry, tint = color)
        }
    }
}

@Composable
internal fun ImagePlaceholder(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
}
