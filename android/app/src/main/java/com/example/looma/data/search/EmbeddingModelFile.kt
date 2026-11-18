package com.example.looma.data.search

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class EmbeddingModelFile(directory: File) {
    private val file = directory.resolve("embeddinggemma-2-text-270m.litertlm")
    private var verified = false

    fun exists(): Boolean = file.isFile && file.length() == MODEL_BYTES

    suspend fun open(): File? {
        if (!exists()) return null
        if (!verified) {
            val digest = MessageDigest.getInstance("SHA-256")
            val context = coroutineContext
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (digest.hex() != MODEL_SHA256) {
                Files.deleteIfExists(file.toPath())
                return null
            }
            verified = true
        }
        return file
    }

    suspend fun download(onProgress: (Int) -> Unit) {
        if (open() != null) return
        val directory = checkNotNull(file.parentFile)
        Files.createDirectories(directory.toPath())
        val pending = directory.resolve("${file.name}.partial")
        val connection = URI(MODEL_URL).toURL().openConnection() as HttpURLConnection
        val digest = MessageDigest.getInstance("SHA-256")
        val context = coroutineContext
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Model download failed")
            if (connection.contentLengthLong >= 0 && connection.contentLengthLong != MODEL_BYTES) {
                throw IOException("Unexpected model length")
            }
            var bytes = 0L
            var progress = 0
            connection.inputStream.buffered().use { input ->
                pending.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        if (bytes > MODEL_BYTES) throw IOException("Unexpected model length")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val nextProgress = (bytes * 100 / MODEL_BYTES).toInt()
                        if (nextProgress != progress) {
                            progress = nextProgress
                            onProgress(progress)
                        }
                    }
                }
            }
            context.ensureActive()
            if (bytes != MODEL_BYTES || digest.hex() != MODEL_SHA256) throw IOException("Invalid embedding model")
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            verified = true
        } finally {
            connection.disconnect()
            pending.delete()
        }
    }

    companion object {
        const val MODEL_BYTES = 164_626_432L
        const val MODEL_SHA256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"
        private const val MODEL_URL = "https://huggingface.co/litert-community/" +
            "embeddinggemma-2-text-270m-litert-lm/resolve/9be6e8b90982095dc05c2bd162e4b954ee4dbac7/" +
            "embeddinggemma-2-text-270m.litertlm"
        private const val BUFFER_SIZE = 64 * 1024
    }
}

private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
