package com.example.looma.data.search

import android.os.Build
import com.example.looma.embedding.EmbeddingBackend
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal data class EmbeddingModelSpec(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val url: String,
)

internal object EmbeddingModelCatalog {
    private const val REPO_BASE_URL =
        "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/" +
            "9be6e8b90982095dc05c2bd162e4b954ee4dbac7/"

    val CPU_MODEL = EmbeddingModelSpec(
        fileName = "embeddinggemma-2-text-270m.litertlm",
        bytes = 164_626_432L,
        sha256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
        url = "${REPO_BASE_URL}embeddinggemma-2-text-270m.litertlm",
    )

    const val VECTOR_SPACE_ID = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"

    private val NPU_MODELS_BY_SOC: Map<String, EmbeddingModelSpec> = mapOf(
        "tensor g5" to npuSpec(
            fileName = "embeddinggemma-2-text-270m_Google_Tensor_G5.litertlm",
            bytes = 166_007_264L,
            sha256 = "f066663433a31a8228b46f3271d2714a3b72a341ed4563cbc6d90f2e3dd0162d",
        ),
        "tensor g6" to npuSpec(
            fileName = "embeddinggemma-2-text-270m_Google_Tensor_G6.litertlm",
            bytes = 199_532_256L,
            sha256 = "4cd282395a7b611ec8dec95160cd31a1f471a7b1cde1fc71717aa539c2a29883",
        ),
        "mt6991" to npuSpec(
            fileName = "embeddinggemma-2-text-270m_MediaTek_MT6991.litertlm",
            bytes = 416_350_208L,
            sha256 = "7acc8e334d882216207c23b7a477179d5af530d67fd8e2cb50ab42d3457f90c0",
        ),
        "mt6993" to npuSpec(
            fileName = "embeddinggemma-2-text-270m_MediaTek_MT6993.litertlm",
            bytes = 385_925_120L,
            sha256 = "eff006451073498117f454c7ab27f274b5fe14a54af49be239e8f480ee49fa36",
        ),
        "qcs8275" to npuSpec(
            fileName = "embeddinggemma-2-text-270m_Qualcomm_QCS8275.litertlm",
            bytes = 208_011_264L,
            sha256 = "128c32151613a5bbf8b5695b2d9ae271057338febbf06e3aeebf29e1353cb51d",
        ),
        "sm8550" to npuSpec(
            fileName = "embeddinggemma-2-text-270m_Qualcomm_SM8550.litertlm",
            bytes = 207_896_576L,
            sha256 = "6a5043146940e738cfa9111aa445b1cdb3dc7bbef30caef367622f14ada1a895",
        ),
        "sm8650" to npuSpec(
            fileName = "embeddinggemma-2-text-270m_Qualcomm_SM8650.litertlm",
            bytes = 207_798_272L,
            sha256 = "7dabcb17eed982d6de4435aaf29cc56147a627f6ab20a1e37aadd7af5399457b",
        ),
        "sm8750" to npuSpec(
            fileName = "embeddinggemma-2-text-270m_Qualcomm_SM8750.litertlm",
            bytes = 187_596_800L,
            sha256 = "b5615709768db4ca4fb4f04514e7e41b0680ccb12dd5f90371193f7fd12e7c99",
        ),
        "sm8850" to npuSpec(
            fileName = "embeddinggemma-2-text-270m_Qualcomm_SM8850.litertlm",
            bytes = 202_293_248L,
            sha256 = "72992b649c812ae9e30ffa9ad88fc662e649f7b643500d28051fbd457ad0d22a",
        ),
    )

    fun currentSoc(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.trim().lowercase() else ""

    fun supportsNpu(soc: String = currentSoc()): Boolean =
        soc.trim().lowercase() in NPU_MODELS_BY_SOC

    fun effectiveBackend(backend: EmbeddingBackend, soc: String = currentSoc()): EmbeddingBackend =
        if (backend == EmbeddingBackend.NPU && !supportsNpu(soc)) EmbeddingBackend.CPU else backend

    fun resolve(backend: EmbeddingBackend, soc: String = currentSoc()): EmbeddingModelSpec =
        if (backend == EmbeddingBackend.NPU) NPU_MODELS_BY_SOC[soc.trim().lowercase()] ?: CPU_MODEL
        else CPU_MODEL

    fun allSpecs(): List<EmbeddingModelSpec> = listOf(CPU_MODEL) + NPU_MODELS_BY_SOC.values

    private fun npuSpec(fileName: String, bytes: Long, sha256: String) = EmbeddingModelSpec(
        fileName = fileName,
        bytes = bytes,
        sha256 = sha256,
        url = "$REPO_BASE_URL$fileName",
    )
}

internal class EmbeddingModelFile(
    private val directory: File,
    private val activeSpec: () -> EmbeddingModelSpec = { EmbeddingModelCatalog.CPU_MODEL },
) {
    private val mutex = Mutex()
    private var verifiedFileName: String? = null

    fun exists(spec: EmbeddingModelSpec = activeSpec()): Boolean {
        val file = targetFile(spec)
        return file.isFile && file.length() == spec.bytes
    }

    fun partialProgress(spec: EmbeddingModelSpec = activeSpec()): Int =
        pendingFile(spec).length().toProgress(spec.bytes)

    suspend fun open(spec: EmbeddingModelSpec = activeSpec()): File? =
        mutex.withLock { openPublishedModel(spec) }

    suspend fun delete() = mutex.withLock {
        verifiedFileName = null
        for (spec in EmbeddingModelCatalog.allSpecs()) {
            Files.deleteIfExists(targetFile(spec).toPath())
            Files.deleteIfExists(pendingFile(spec).toPath())
        }
    }

    private suspend fun openPublishedModel(spec: EmbeddingModelSpec): File? {
        val file = targetFile(spec)
        if (!exists(spec)) {
            if (verifiedFileName == spec.fileName) verifiedFileName = null
            return null
        }
        if (verifiedFileName != spec.fileName && !file.hasExpectedDigest(spec.sha256)) {
            Files.deleteIfExists(file.toPath())
            return null
        }
        verifiedFileName = spec.fileName
        return file
    }

    suspend fun download(
        onProgress: suspend (Int) -> Unit,
        spec: EmbeddingModelSpec = activeSpec(),
    ) = mutex.withLock {
        if (openPublishedModel(spec) != null) {
            onProgress(100)
            return@withLock
        }
        val file = targetFile(spec)
        val pending = pendingFile(spec)
        Files.createDirectories(checkNotNull(file.parentFile).toPath())
        if (pending.length() > spec.bytes) Files.deleteIfExists(pending.toPath())
        if (pending.length() == spec.bytes && publishIfValid(spec)) {
            onProgress(100)
            return@withLock
        }

        val offset = pending.length()
        onProgress(offset.toProgress(spec.bytes))
        HttpClient(OkHttp) {
            engine {
                config {
                    callTimeout(0, TimeUnit.MILLISECONDS)
                    connectTimeout(15_000, TimeUnit.MILLISECONDS)
                    readTimeout(30_000, TimeUnit.MILLISECONDS)
                }
            }
        }.use { client ->
            val published = receive(client, spec, pending, offset, onProgress) && publishIfValid(spec)
            if (!published && (offset == 0L || !receive(client, spec, pending, 0, onProgress) || !publishIfValid(spec))) {
                throw IOException("Invalid embedding model")
            }
            onProgress(100)
        }
    }

    private suspend fun receive(
        client: HttpClient,
        spec: EmbeddingModelSpec,
        pending: File,
        offset: Long,
        onProgress: suspend (Int) -> Unit,
    ): Boolean = client.prepareGet(spec.url) {
        header(HttpHeaders.AcceptEncoding, "identity")
        if (offset > 0) header(HttpHeaders.Range, "bytes=$offset-")
    }.execute { response ->
        val start = when (response.status) {
            HttpStatusCode.OK -> 0L
            HttpStatusCode.PartialContent -> {
                val expectedRange = "bytes $offset-${spec.bytes - 1}/${spec.bytes}"
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
            (length != null && length.toLongOrNull() != spec.bytes - start)
        ) {
            if (start > 0) return@execute false
            throw IOException("Unexpected model response")
        }

        val channel = response.bodyAsChannel()
        val context = coroutineContext
        var bytes = start
        var progress = pending.length().toProgress(spec.bytes)
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
                if (bytes > spec.bytes) {
                    output.channel.truncate(start)
                    throw IOException("Model response exceeds its expected size")
                }
                output.write(buffer, 0, count)
                val updated = bytes.toProgress(spec.bytes)
                if (updated != progress) {
                    progress = updated
                    onProgress(progress)
                }
            }
        }
        if (bytes != spec.bytes) throw EOFException("Model download ended at $bytes of ${spec.bytes} bytes")
        true
    }

    private suspend fun publishIfValid(spec: EmbeddingModelSpec): Boolean {
        val pending = pendingFile(spec)
        val file = targetFile(spec)
        if (!pending.hasExpectedDigest(spec.sha256)) {
            Files.deleteIfExists(pending.toPath())
            return false
        }
        FileOutputStream(pending, true).use { it.fd.sync() }
        coroutineContext.ensureActive()
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        verifiedFileName = spec.fileName
        return true
    }

    private suspend fun File.hasExpectedDigest(expectedSha256: String): Boolean {
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
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == expectedSha256
    }

    private fun targetFile(spec: EmbeddingModelSpec): File = directory.resolve(spec.fileName)

    private fun pendingFile(spec: EmbeddingModelSpec): File = directory.resolve("${spec.fileName}.partial")

    private fun Long.toProgress(totalBytes: Long): Int =
        (coerceIn(0, totalBytes) * 100 / totalBytes).toInt().coerceAtMost(99)

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
