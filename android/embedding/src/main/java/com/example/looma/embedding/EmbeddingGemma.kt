package com.example.looma.embedding

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import java.io.Closeable
import java.io.File

class EmbeddingGemma(context: Context, modelFile: File, gpu: Boolean = false) : Closeable {
    private val delegate = if (gpu) Delegate.GPU else Delegate.CPU
    val backend = "MediaPipe UniversalEmbedder / LiteRT-LM ${delegate.name}"
    private val provider = UniversalEmbedder.createFromOptions(
        context,
        UniversalEmbedderOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .setDelegate(delegate)
                    .build(),
            )
            .setTextDelegate(delegate)
            .setCacheDir(context.cacheDir.absolutePath)
            .setL2Normalize(true)
            .build(),
    )

    fun document(text: String, title: String = "none"): FloatArray =
        embed(documentInput(text, title))

    fun query(text: String): FloatArray =
        embed("task: search result | query: ${text.trim()}")

    private fun embed(text: String): FloatArray =
        provider.embedText(text).embeddings().single().floatEmbedding()

    override fun close() = provider.close()

    companion object {
        fun documentInput(text: String, title: String) = "title: $title | text: ${text.trim()}"
        const val DIMENSIONS = 768
    }
}
