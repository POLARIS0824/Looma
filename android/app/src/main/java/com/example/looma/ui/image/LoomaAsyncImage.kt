package com.example.looma.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.example.looma.domain.markdown.ImageSource
import java.io.File

@Composable
internal fun LoomaAsyncImage(
    source: ImageSource,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    contentDescription: String? = null,
) {
    AsyncImage(
        model = rememberImageRequest(source),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
internal fun rememberImageRequest(source: ImageSource, reload: Boolean = false): ImageRequest {
    val context = LocalPlatformContext.current
    return remember(context, source, reload) {
        ImageRequest.Builder(context)
            .data(source.coilModel())
            .apply {
                if (reload) {
                    memoryCachePolicy(CachePolicy.WRITE_ONLY)
                    diskCachePolicy(CachePolicy.WRITE_ONLY)
                }
            }
            .build()
    }
}

private fun ImageSource.coilModel(): Any = when (this) {
    is ImageSource.Local -> File(path)
    is ImageSource.Remote -> url
}
