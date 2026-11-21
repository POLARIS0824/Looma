package com.example.looma.data.assistant

import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class LocalGenerator(private val context: Context) {
    private val file = File(context.noBackupFilesDir, "answer-model.litertlm")
    private val mutex = Mutex()
    private val lifetime = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var engine: Engine? = null
    private var idle: kotlinx.coroutines.Job? = null

    private fun closeEngine() {
        val current = engine
        engine = null
        current?.close()
    }

    suspend fun prepareRetrieval() {
        if (context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true) {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    idle?.cancel()
                    closeEngine()
                }
            }
        }
    }

    val installed get() = file.isFile

    suspend fun install(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stage = File.createTempFile("answer-import-", ".litertlm", file.parentFile)
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException()
                input.use { source ->
                    stage.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                closeEngine()
                engine(stage).use { it.initialize() }
                coroutineContext.ensureActive()
                Files.move(stage.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                Unit
            } finally {
                stage.delete()
            }
        }
    }

    suspend fun generate(instruction: String, payload: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            idle?.cancel()
            try {
                val active = engine ?: engine(file).also {
                    engine = it
                    it.initialize()
                }
                coroutineContext.ensureActive()
                active.createConversation(ConversationConfig(systemInstruction = Contents.of(instruction))).use { conversation ->
                    val text = StringBuilder()
                    try {
                        conversation.sendMessageAsync(payload).collect { part ->
                            coroutineContext.ensureActive()
                            text.append(part.toString())
                            check(text.length <= 12000)
                        }
                    } catch (error: Exception) {
                        runCatching { conversation.cancelProcess() }.exceptionOrNull()?.let(error::addSuppressed)
                        throw error
                    }
                    text.toString().trim()
                }
            } catch (error: Throwable) {
                closeEngine()
                throw error
            } finally {
                idle = lifetime.launch {
                    kotlinx.coroutines.delay(30_000)
                    mutex.withLock { closeEngine() }
                }
            }
        }
    }

    private fun engine(model: File) = Engine(EngineConfig(
        modelPath = model.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.absolutePath,
    ))
}
