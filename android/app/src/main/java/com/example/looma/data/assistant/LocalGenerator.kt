package com.example.looma.data.assistant

import android.content.Context
import android.net.Uri
import com.example.looma.data.assistant.runtime.LlmError
import com.example.looma.data.assistant.runtime.LlmGenerateRequest
import com.example.looma.data.assistant.runtime.LlmMetadata
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import com.example.looma.data.inference.LocalModelSession
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal class LocalGenerator(
    private val context: Context,
    private val session: LocalModelSession,
) : LlmRuntimeBackend, LlmBackendProvider {
    private val file = File(context.noBackupFilesDir, "answer-model.litertlm")
    private var engine: Engine? = null
    private var cachedConversation: Conversation? = null
    private var cachedSystemInstruction: String? = null
    private var cachedMessageCount: Int = 0

    @Volatile
    private var activeConversation: Conversation? = null

    private val mutableModels = MutableStateFlow(currentModels())
    override val models: StateFlow<List<AnswerModel>> = mutableModels.asStateFlow()

    override val modelId: String = AnswerModels.LocalModel.id
    override val contextBudgetChars: Int = 3600
    override val maxContextTokens: Int = 4096
    override val supportsStatefulSession: Boolean = true

    private fun closeConversation() {
        val current = cachedConversation
        cachedConversation = null
        cachedSystemInstruction = null
        cachedMessageCount = 0
        runCatching { current?.close() }
    }

    private fun closeEngine() {
        closeConversation()
        val current = engine
        engine = null
        current?.close()
    }

    val installed get() = file.isFile

    val installedBytes: Long get() = if (file.isFile) file.length() else 0L

    private fun currentModels(): List<AnswerModel> =
        if (installed) listOf(AnswerModels.LocalModel) else emptyList()

    override fun refresh() {
        mutableModels.value = currentModels()
    }

    override fun backend(model: AnswerModel, route: InferenceRoute?): LlmRuntimeBackend? =
        if (model.id == AnswerModels.LocalModel.id && installed) this else null

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
            refresh()
        } finally {
            stage.delete()
        }
    }

    override suspend fun generate(request: LlmGenerateRequest): LlmResult<String> {
        val text = StringBuilder()
        var lastMeta = localMetadata()
        var failure: LlmResult.Failure? = null
        generateStream(request).collect { outcome ->
            when (outcome) {
                is LlmResult.Success -> {
                    text.append(outcome.value.text)
                    lastMeta = outcome.metadata
                }
                is LlmResult.Failure -> failure = outcome
            }
        }
        return failure ?: LlmResult.Success(text.toString().trim(), lastMeta)
    }

    override fun generateStream(request: LlmGenerateRequest): Flow<LlmResult<LlmStreamChunk>> = flow {
        if (request.messages.isEmpty()) {
            emit(LlmResult.Failure(LlmError(LlmError.FAILED, "Empty messages")))
            return@flow
        }
        session.use(LocalModelSession.Model.Generation, IDLE_TIMEOUT_MS, ::closeEngine) {
            val active = ensureEngine()
            coroutineContext.ensureActive()
            val (conversation, payload) = acquireConversation(active, request)
            val total = StringBuilder()
            activeConversation = conversation
            try {
                conversation.sendMessageAsync(payload).collect { part ->
                    coroutineContext.ensureActive()
                    val chunk = filterControlTokens(part.toString())
                    if (chunk.isNotEmpty()) {
                        total.append(chunk)
                        check(total.length <= MAX_OUTPUT_CHARS)
                        emit(LlmResult.Success(LlmStreamChunk(text = chunk), localMetadata()))
                    }
                }
                if (!request.ephemeral) {
                    cachedMessageCount = request.messages.size + 1
                }
                emit(LlmResult.Success(LlmStreamChunk(done = true), localMetadata()))
            } catch (error: Exception) {
                runCatching { conversation.cancelProcess() }.exceptionOrNull()?.let(error::addSuppressed)
                if (!request.ephemeral) closeConversation()
                throw error
            } finally {
                activeConversation = null
                if (request.ephemeral) {
                    runCatching { conversation.close() }
                }
            }
        }
    }.catch { error ->
        when (error) {
            is CancellationException -> throw error
            is Exception, is LinkageError -> emit(LlmResult.Failure(LlmError(LlmError.FAILED, error.message)))
            else -> throw error
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun resetSession() {
        withContext(Dispatchers.IO) {
            session.use(LocalModelSession.Model.Generation, IDLE_TIMEOUT_MS, ::closeEngine) {
                closeConversation()
            }
        }
    }

    override suspend fun stop() {
        activeConversation?.let { runCatching { it.cancelProcess() } }
    }

    private fun ensureEngine(): Engine =
        engine ?: engine(file).also {
            engine = it
            it.initialize()
        }

    private fun acquireConversation(active: Engine, request: LlmGenerateRequest): Pair<Conversation, String> {
        if (request.ephemeral) {
            return createConversation(active, request.systemInstruction) to formatMessages(request.messages)
        }
        val existing = cachedConversation
        if (
            existing != null &&
            cachedSystemInstruction == request.systemInstruction &&
            cachedMessageCount > 0 &&
            request.messages.size > cachedMessageCount
        ) {
            return existing to formatMessages(request.messages.drop(cachedMessageCount))
        }
        closeConversation()
        val created = createConversation(active, request.systemInstruction)
        cachedConversation = created
        cachedSystemInstruction = request.systemInstruction
        cachedMessageCount = 0
        return created to formatMessages(request.messages)
    }

    private fun createConversation(active: Engine, systemInstruction: String?): Conversation {
        val instruction = systemInstruction?.takeIf { it.isNotBlank() }
        val config = if (instruction != null) {
            ConversationConfig(systemInstruction = Contents.of(instruction))
        } else {
            ConversationConfig()
        }
        return active.createConversation(config)
    }

    private fun formatMessages(messages: List<LlmMessage>): String {
        if (messages.size == 1 && messages[0].role == LlmRole.User) {
            return messages[0].content
        }
        return messages.joinToString("\n\n") { message ->
            val role = when (message.role) {
                LlmRole.System -> "System"
                LlmRole.User -> "User"
                LlmRole.Model -> "Assistant"
                LlmRole.Tool -> "Tool"
            }
            "$role: ${message.content}"
        }
    }

    private fun engine(model: File) = Engine(EngineConfig(
        modelPath = model.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.absolutePath,
    ))

    private fun localMetadata(): LlmMetadata = GenerationMeta(InferenceOrigin.LocalFile).toLlmMetadata()

    private fun filterControlTokens(text: String): String = text.replace(CTRL_TOKEN_REGEX, "")

    private companion object {
        const val IDLE_TIMEOUT_MS = 30_000L
        const val MAX_OUTPUT_CHARS = 12_000
        val CTRL_TOKEN_REGEX = Regex("<ctrl[^>]*>?")
    }
}
