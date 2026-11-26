package com.example.looma.data.assistant.sessions

import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import kotlinx.coroutines.flow.Flow

internal interface LlmSessionManager {
    val backend: LlmRuntimeBackend?
    val systemInstruction: String?
    val messages: List<LlmMessage>

    suspend fun createSession(backend: LlmRuntimeBackend, systemInstruction: String? = null)
    suspend fun ensureSession(backend: LlmRuntimeBackend, systemInstruction: String? = null)
    suspend fun resetSession()
    suspend fun replaceHistory(messages: List<LlmMessage>)

    fun estimateTokens(): Int
    fun contextUsage(): ContextUsage?
    suspend fun compactIfNeeded(compactor: ContextCompactor): CompactionResult

    suspend fun generateResponse(
        prompt: String,
        ephemeral: Boolean = false,
        responseMimeType: String? = null,
        maxOutputTokens: Int? = null,
    ): LlmResult<String>

    fun generateStream(
        prompt: String,
        ephemeral: Boolean = false,
        responseMimeType: String? = null,
        maxOutputTokens: Int? = null,
    ): Flow<LlmResult<LlmStreamChunk>>

    suspend fun stopResponse()
}
