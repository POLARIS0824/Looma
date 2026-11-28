package com.example.looma.data.assistant.sessions

import com.example.looma.data.assistant.runtime.LlmError
import com.example.looma.data.assistant.runtime.LlmGenerateRequest
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DefaultLlmSessionManager : LlmSessionManager {
    private val sessionMutex = Mutex()
    private val historyLock = Any()
    private val history = mutableListOf<LlmMessage>()

    @Volatile
    private var currentBackend: LlmRuntimeBackend? = null

    @Volatile
    private var currentSystemInstruction: String? = null

    override val backend: LlmRuntimeBackend?
        get() = currentBackend

    override val systemInstruction: String?
        get() = currentSystemInstruction

    override val messages: List<LlmMessage>
        get() = synchronized(historyLock) { history.toList() }

    override suspend fun createSession(backend: LlmRuntimeBackend, systemInstruction: String?) {
        sessionMutex.withLock {
            val previous = currentBackend
            currentBackend = backend
            currentSystemInstruction = systemInstruction
            synchronized(historyLock) {
                history.clear()
            }
            if (previous != null && previous !== backend) {
                previous.resetSession()
            }
            backend.resetSession()
        }
    }

    override suspend fun ensureSession(backend: LlmRuntimeBackend, systemInstruction: String?) {
        sessionMutex.withLock {
            val previous = currentBackend
            val sameBackend = previous != null && previous.sessionKey == backend.sessionKey
            val sameInstruction = currentSystemInstruction == systemInstruction
            if (sameBackend && sameInstruction) {
                currentBackend = backend
                return@withLock
            }
            if (currentSystemInstruction != null && currentSystemInstruction != systemInstruction) {
                synchronized(historyLock) {
                    history.clear()
                }
            }
            currentBackend = backend
            currentSystemInstruction = systemInstruction
            if (previous != null && previous !== backend) {
                previous.resetSession()
            }
            backend.resetSession()
        }
    }

    override suspend fun resetSession() {
        sessionMutex.withLock {
            synchronized(historyLock) {
                history.clear()
            }
            currentBackend?.resetSession()
        }
    }

    override suspend fun replaceHistory(messages: List<LlmMessage>) {
        sessionMutex.withLock {
            synchronized(historyLock) {
                history.clear()
                history.addAll(messages)
            }
            currentBackend?.resetSession()
        }
    }

    override fun estimateTokens(): Int =
        estimateTokenCount(currentSystemInstruction, messages)

    override fun contextUsage(): ContextUsage? {
        val active = currentBackend ?: return null
        val maxTokens = effectiveMaxTokens(active)
        if (maxTokens <= 0) return null
        val estimated = estimateTokens()
        return ContextUsage(
            estimatedTokens = estimated,
            maxTokens = maxTokens,
            ratio = (estimated.toFloat() / maxTokens.toFloat()).coerceAtLeast(0f),
        )
    }

    override suspend fun compactIfNeeded(compactor: ContextCompactor): CompactionResult {
        val active = currentBackend ?: return CompactionResult(compacted = false)
        return compactor.compactIfNeeded(this, active)
    }

    override suspend fun generateResponse(
        prompt: String,
        ephemeral: Boolean,
        responseMimeType: String?,
        maxOutputTokens: Int?,
    ): LlmResult<String> = sessionMutex.withLock {
        val activeBackend = currentBackend
            ?: return@withLock LlmResult.Failure(LlmError(LlmError.FAILED, "No active LLM session"))
        val userMessage = LlmMessage(LlmRole.User, prompt)
        val requestMessages = if (ephemeral) {
            listOf(userMessage)
        } else {
            synchronized(historyLock) { history + userMessage }
        }
        val request = LlmGenerateRequest(
            modelId = activeBackend.modelId,
            messages = requestMessages,
            systemInstruction = currentSystemInstruction,
            maxOutputTokens = maxOutputTokens,
            responseMimeType = responseMimeType,
            ephemeral = ephemeral,
        )
        val outcome = activeBackend.generate(request)
        if (!ephemeral && outcome is LlmResult.Success) {
            synchronized(historyLock) {
                history.add(userMessage)
                history.add(LlmMessage(LlmRole.Model, outcome.value))
            }
        }
        outcome
    }

    override fun generateStream(
        prompt: String,
        ephemeral: Boolean,
        responseMimeType: String?,
        maxOutputTokens: Int?,
    ): Flow<LlmResult<LlmStreamChunk>> = flow {
        sessionMutex.withLock {
            val activeBackend = currentBackend ?: run {
                emit(LlmResult.Failure(LlmError(LlmError.FAILED, "No active LLM session")))
                return@withLock
            }
            val userMessage = LlmMessage(LlmRole.User, prompt)
            val requestMessages = if (ephemeral) {
                listOf(userMessage)
            } else {
                synchronized(historyLock) { history + userMessage }
            }
            val request = LlmGenerateRequest(
                modelId = activeBackend.modelId,
                messages = requestMessages,
                systemInstruction = currentSystemInstruction,
                maxOutputTokens = maxOutputTokens,
                responseMimeType = responseMimeType,
                ephemeral = ephemeral,
            )
            val accumulated = StringBuilder()
            var failed = false
            activeBackend.generateStream(request).collect { outcome ->
                when (outcome) {
                    is LlmResult.Success -> accumulated.append(outcome.value.text)
                    is LlmResult.Failure -> failed = true
                }
                emit(outcome)
            }
            if (!ephemeral && !failed) {
                synchronized(historyLock) {
                    history.add(userMessage)
                    history.add(LlmMessage(LlmRole.Model, accumulated.toString()))
                }
            }
        }
    }

    override suspend fun stopResponse() {
        currentBackend?.stop()
    }

    internal companion object {
        const val CHARS_PER_TOKEN = 4

        fun effectiveMaxTokens(backend: LlmRuntimeBackend): Int =
            if (backend.maxContextTokens > 0) backend.maxContextTokens
            else backend.contextBudgetChars / CHARS_PER_TOKEN

        fun estimateTokenCount(systemInstruction: String?, messages: List<LlmMessage>): Int {
            val totalChars = (systemInstruction?.length ?: 0) + messages.sumOf { it.content.length + 16 }
            return totalChars / CHARS_PER_TOKEN
        }
    }
}
