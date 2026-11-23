package com.example.looma.data.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class EmbeddingModelFile(directory: File) {
    private val file = directory.resolve("embeddinggemma-2-text-270m.litertlm")
    private val pending = directory.resolve("${file.name}.partial")
    private val mutex = Mutex()
    private var verified = false

    fun exists(): Boolean = file.isFile && file.length() == MODEL_BYTES

    fun partialProgress(): Int = pending.length().toProgress()

    suspend fun open(): File? = mutex.withLock { openPublishedModel() }

    suspend fun delete() = mutex.withLock {
        verified = false
        Files.deleteIfExists(file.toPath())
        Files.deleteIfExists(pending.toPath())
    }

    private suspend fun openPublishedModel(): File? {
        if (!exists()) {
            verified = false
            return null
        }
        if (!verified && !file.hasExpectedDigest()) {
            Files.deleteIfExists(file.toPath())
            return null
        }
        verified = true
        return file
    }

    suspend fun download(onProgress: suspend (Int) -> Unit) = mutex.withLock {
        if (openPublishedModel() != null) {
            onProgress(100)
            return@withLock
        }
        Files.createDirectories(checkNotNull(file.parentFile).toPath())
        if (pending.length() > MODEL_BYTES) Files.deleteIfExists(pending.toPath())
        if (pending.length() == MODEL_BYTES && publishIfValid()) {
            onProgress(100)
            return@withLock
        }

        val offset = pending.length()
        onProgress(offset.toProgress())
        HttpClient(CIO) {
            engine {
                requestTimeout = 0
                endpoint.connectTimeout = 15_000
                endpoint.socketTimeout = 30_000
            }
        }.use { client ->
            val published = receive(client, offset, onProgress) && publishIfValid()
            if (!published && (offset == 0L || !receive(client, 0, onProgress) || !publishIfValid())) {
                throw IOException("Invalid embedding model")
            }
            onProgress(100)
        }
    }

    private suspend fun receive(
        client: HttpClient,
        offset: Long,
        onProgress: suspend (Int) -> Unit,
    ): Boolean = client.prepareGet(MODEL_URL) {
        header(HttpHeaders.AcceptEncoding, "identity")
        if (offset > 0) header(HttpHeaders.Range, "bytes=$offset-")
    }.execute { response ->
        val start = when (response.status) {
            HttpStatusCode.OK -> 0L
            HttpStatusCode.PartialContent -> {
                val expectedRange = "bytes $offset-${MODEL_BYTES - 1}/$MODEL_BYTES"
                if (offset == 0L) throw IOException("Unexpected model range")
                if (response.headers[HttpHeaders.ContentRange] != expectedRange) return@execute false
                offset
            }
            HttpStatusCode.RequestedRangeNotSatisfiable -> {
                if (offset > 0) return@execute false
                throw IOException("Unexpected model range")
            }
            else -> throw IOException("Model download failed (HTTP ${response.status.value})")
        }
        val encoding = response.headers[HttpHeaders.ContentEncoding]
        val length = response.headers[HttpHeaders.ContentLength]
        if ((encoding != null && !encoding.equals("identity", ignoreCase = true)) ||
            (length != null && length.toLongOrNull() != MODEL_BYTES - start)
        ) {
            if (start > 0) return@execute false
            throw IOException("Unexpected model response")
        }

        val channel = response.bodyAsChannel()
        val context = coroutineContext
        var bytes = start
        var progress = partialProgress()
        FileOutputStream(pending, start > 0).use { output ->
            if (start == 0L && progress > 0) {
                progress = 0
                onProgress(progress)
            }
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                context.ensureActive()
                val count = channel.readAvailable(buffer)
                if (count < 0) break
                bytes += count
                if (bytes > MODEL_BYTES) {
                    output.channel.truncate(start)
                    throw IOException("Model response exceeds its expected size")
                }
                output.write(buffer, 0, count)
                val updated = bytes.toProgress()
                if (updated != progress) {
                    progress = updated
                    onProgress(progress)
                }
            }
        }
        if (bytes != MODEL_BYTES) throw EOFException("Model download ended at $bytes of $MODEL_BYTES bytes")
        true
    }

    private suspend fun publishIfValid(): Boolean {
        if (!pending.hasExpectedDigest()) {
            Files.deleteIfExists(pending.toPath())
            return false
        }
        FileOutputStream(pending, true).use { it.fd.sync() }
        coroutineContext.ensureActive()
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        verified = true
        return true
    }

    private suspend fun File.hasExpectedDigest(): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val context = coroutineContext
        inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == MODEL_SHA256
    }

    private fun Long.toProgress(): Int = (coerceIn(0, MODEL_BYTES) * 100 / MODEL_BYTES).toInt().coerceAtMost(99)

    companion object {
        const val MODEL_BYTES = 164_626_432L
        const val MODEL_SHA256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"
        private const val MODEL_URL = "https://huggingface.co/litert-community/" +
            "embeddinggemma-2-text-270m-litert-lm/resolve/9be6e8b90982095dc05c2bd162e4b954ee4dbac7/" +
            "embeddinggemma-2-text-270m.litertlm"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
