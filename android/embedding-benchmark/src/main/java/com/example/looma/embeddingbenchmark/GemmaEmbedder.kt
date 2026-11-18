package com.example.looma.embeddingbenchmark

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import java.io.File

internal class GemmaEmbedder(context: Context, modelFile: File, model: Model) : Embedder {
    private val delegate = when (model) {
        Model.GEMMA_CPU -> Delegate.CPU
        Model.GEMMA_GPU -> Delegate.GPU
        else -> error("Unsupported Gemma model: ${model.id}")
    }
    override val backend = "MediaPipe UniversalEmbedder / LiteRT-LM ${delegate.name}"
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

    override fun embed(input: Input): FloatArray {
        val text = input.text.trim()
        val prompted = when (input.role) {
            Role.DOCUMENT -> "title: none | text: $text"
            Role.QUERY -> "task: search result | query: $text"
        }
        return provider.embedText(prompted).embeddings().single().floatEmbedding()
    }

    override fun close() = provider.close()
}
