package com.example.looma.embedding

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.BaseOptions.DelegateOptions.NpuOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import java.io.Closeable
import java.io.File

enum class EmbeddingBackend {
    CPU,
    GPU,
    NPU,
}

class EmbeddingGemma(
    context: Context,
    modelFile: File,
    backend: EmbeddingBackend = EmbeddingBackend.CPU,
) : Closeable {
    private val delegate = when (backend) {
        EmbeddingBackend.CPU -> Delegate.CPU
        EmbeddingBackend.GPU -> Delegate.GPU
        EmbeddingBackend.NPU -> Delegate.NPU
    }
    val backend = "MediaPipe UniversalEmbedder / LiteRT-LM ${delegate.name}"
    private val provider = UniversalEmbedder.createFromOptions(
        context,
        UniversalEmbedderOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .setDelegate(delegate)
                    .apply {
                        if (delegate == Delegate.NPU) {
                            val nativeLibDir = context.applicationInfo.nativeLibraryDir
                            setDelegateOptions(
                                NpuOptions.builder()
                                    .setDispatchLibraryDirectory(nativeLibDir)
                                    .setCompilerPluginLibraryDirectory(nativeLibDir)
                                    .build(),
                            )
                        }
                    }
                    .build(),
            )
            .setTextDelegate(delegate)
            .setCacheDir(context.cacheDir.absolutePath)
            .setL2Normalize(true)
            .build(),
    )

    fun document(text: String, title: String = "none"): FloatArray =
        embed("title: $title | text: ${text.trim()}")

    fun query(text: String): FloatArray =
        embed("task: search result | query: ${text.trim()}")

    private fun embed(text: String): FloatArray =
        provider.embedText(text).embeddings().single().floatEmbedding()

    override fun close() = provider.close()

    companion object {
        const val DIMENSIONS = 768
    }
}
