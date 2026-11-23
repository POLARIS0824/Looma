package com.example.looma.embeddingbenchmark

import android.content.Context
import com.example.looma.embedding.EmbeddingBackend
import com.example.looma.embedding.EmbeddingGemma
import java.io.File

internal class GemmaEmbedder(context: Context, modelFile: File, model: Model) : Embedder {
    private val provider = EmbeddingGemma(
        context,
        modelFile,
        backend = if (model == Model.GEMMA_GPU) EmbeddingBackend.GPU else EmbeddingBackend.CPU,
    )
    override val backend = provider.backend

    override fun embed(input: Input): FloatArray = when (input.role) {
        Role.DOCUMENT -> provider.document(input.text)
        Role.QUERY -> provider.query(input.text)
    }

    override fun close() = provider.close()
}
