package com.example.looma.data.assistant.sessions

import com.example.looma.data.assistant.GenerationOutcome
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import kotlinx.coroutines.flow.Flow

internal interface LlmSessionManager {
    val backend: LlmRuntimeBackend?
    val systemInstruction: String?
    val messages: List<LlmMessage>

    fun createSession(backend: LlmRuntimeBackend, systemInstruction: String? = null)
    suspend fun resetSession()
    fun replaceHistory(messages: List<LlmMessage>)

    suspend fun generateResponse(
        prompt: String,
        ephemeral: Boolean = false,
        route: InferenceRoute? = null,
        responseMimeType: String? = null,
        maxOutputTokens: Int? = null,
    ): GenerationOutcome<String>

    fun generateStream(
        prompt: String,
        ephemeral: Boolean = false,
        route: InferenceRoute? = null,
        responseMimeType: String? = null,
        maxOutputTokens: Int? = null,
    ): Flow<GenerationOutcome<LlmStreamChunk>>

    suspend fun stopResponse()
}
