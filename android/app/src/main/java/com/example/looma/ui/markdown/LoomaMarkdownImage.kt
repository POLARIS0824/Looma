package com.example.looma.ui.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import com.example.looma.domain.markdown.splitImageSize
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
    val resolution = rememberImageSource(reference)
    ImageAltTooltip(alt) {
        when (resolution) {
            ImageResolution.Pending -> ImagePlaceholder(placeholderFrameModifier(size))
            ImageResolution.Missing -> Unit
            is ImageResolution.Found -> {
                val imageSize = resolution.source.size ?: size
                val sizeResolver = rememberConstraintsSizeResolver()
                val request = rememberImageRequest(resolution.source)
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
                        is AsyncImagePainter.State.Error -> Unit
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
private fun ImagePlaceholder(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
}
