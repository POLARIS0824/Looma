package com.example.looma.data.assistant

import android.content.Context
import android.net.Uri
import com.example.looma.data.inference.LocalModelSession
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class LocalGenerator(private val context: Context, private val session: LocalModelSession) {
    private val file = File(context.noBackupFilesDir, "answer-model.litertlm")
    private var engine: Engine? = null

    private fun closeEngine() {
        val current = engine
        engine = null
        current?.close()
    }

    val installed get() = file.isFile

    suspend fun install(uri: Uri) = withContext(Dispatchers.IO) {
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
            session.use(LocalModelSession.Model.Generation, null, ::closeEngine) {
                closeEngine()
                engine(stage).use { it.initialize() }
                coroutineContext.ensureActive()
                Files.move(stage.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                Unit
            }
        } finally {
            stage.delete()
        }
    }

    suspend fun generate(instruction: String, payload: String): String = withContext(Dispatchers.IO) {
        session.use(LocalModelSession.Model.Generation, 30_000, ::closeEngine) {
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
        }
    }

    private fun engine(model: File) = Engine(EngineConfig(
        modelPath = model.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.absolutePath,
    ))
}
