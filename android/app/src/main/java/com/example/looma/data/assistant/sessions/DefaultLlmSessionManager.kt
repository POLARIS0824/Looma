package com.example.looma.data.assistant.sessions

import com.example.looma.data.assistant.GenerationError
import com.example.looma.data.assistant.GenerationOutcome
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.runtime.LlmGenerateRequest
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class DefaultLlmSessionManager : LlmSessionManager {
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

    override fun createSession(backend: LlmRuntimeBackend, systemInstruction: String?) {
        currentBackend = backend
        currentSystemInstruction = systemInstruction
        synchronized(historyLock) {
            history.clear()
        }
    }

    override suspend fun resetSession() {
        synchronized(historyLock) {
            history.clear()
        }
        currentBackend?.resetSession()
    }

    override fun replaceHistory(messages: List<LlmMessage>) {
        synchronized(historyLock) {
            history.clear()
            history.addAll(messages)
        }
    }

    override suspend fun generateResponse(
        prompt: String,
        ephemeral: Boolean,
        route: InferenceRoute?,
        responseMimeType: String?,
        maxOutputTokens: Int?,
    ): GenerationOutcome<String> {
        val activeBackend = currentBackend
            ?: return GenerationOutcome.Failure(GenerationError.Failed("No active LLM session"))
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
            route = route,
        )
        val outcome = activeBackend.generate(request)
        if (!ephemeral && outcome is GenerationOutcome.Success) {
            synchronized(historyLock) {
                history.add(userMessage)
                history.add(LlmMessage(LlmRole.Model, outcome.value))
            }
        }
        return outcome
    }

    override fun generateStream(
        prompt: String,
        ephemeral: Boolean,
        route: InferenceRoute?,
        responseMimeType: String?,
        maxOutputTokens: Int?,
    ): Flow<GenerationOutcome<LlmStreamChunk>> = flow {
        val activeBackend = currentBackend ?: run {
            emit(GenerationOutcome.Failure(GenerationError.Failed("No active LLM session")))
            return@flow
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
            route = route,
        )
        val accumulated = StringBuilder()
        var failed = false
        activeBackend.generateStream(request).collect { outcome ->
            when (outcome) {
                is GenerationOutcome.Success -> accumulated.append(outcome.value.text)
                is GenerationOutcome.Failure -> failed = true
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

    override suspend fun stopResponse() {
        currentBackend?.stop()
    }
}
