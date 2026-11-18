package com.example.looma.embeddingbenchmark

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

internal class OrtEmbedder(
    modelFile: File,
    private val tokenizer: WordPieceTokenizer,
    private val model: Model,
    threads: Int,
) : Embedder {
    override val backend = "ONNX Runtime CPU"
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(threads)
        options.setInterOpNumThreads(1)
        environment.createSession(modelFile.absolutePath, options)
    }

    override fun embed(input: Input): FloatArray {
        val text = if (model == Model.BGE && input.role == Role.QUERY) BGE_QUERY_PREFIX + input.text else input.text
        val ids = tokenizer.ids(text)
        require(ids.size in 1..TOKEN_LIMIT) {
            "${input.id}: got ${ids.size} tokens outside the 1..$TOKEN_LIMIT benchmark window"
        }
        val shape = longArrayOf(1, ids.size.toLong())
        fun tensor(values: LongArray) = OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)

        val vector = tensor(ids).use { inputIds ->
            tensor(LongArray(ids.size) { 1 }).use { mask ->
                val inputs = mapOf("input_ids" to inputIds, "attention_mask" to mask)
                if (model == Model.BGE) {
                    tensor(LongArray(ids.size)).use { types ->
                        session.run(inputs + ("token_type_ids" to types)).use { output ->
                            @Suppress("UNCHECKED_CAST")
                            val tokens = output[0].value as Array<Array<FloatArray>>
                            tokens.single().first()
                        }
                    }
                } else {
                    session.run(inputs).use { output ->
                        @Suppress("UNCHECKED_CAST")
                        val vectors = output[0].value as Array<FloatArray>
                        vectors.single()
                    }
                }
            }
        }
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        check(norm.isFinite() && norm > 0.0) { "${input.id}: embedding has invalid norm $norm" }
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    override fun close() = session.close()

    private companion object {
        const val TOKEN_LIMIT = 512
        const val BGE_QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
    }
}
