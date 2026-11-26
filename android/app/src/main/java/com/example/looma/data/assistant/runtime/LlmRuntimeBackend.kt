package com.example.looma.data.assistant.runtime

import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.GenerationOutcome
import com.example.looma.data.assistant.InferenceOrigin
import com.example.looma.data.assistant.InferenceRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

internal enum class LlmRole { System, User, Model, Tool }

internal data class LlmMessage(
    val role: LlmRole,
    val content: String,
)

internal data class LlmGenerateRequest(
    val modelId: String,
    val messages: List<LlmMessage>,
    val systemInstruction: String? = null,
    val maxOutputTokens: Int? = null,
    val responseMimeType: String? = null,
    val ephemeral: Boolean = false,
    val route: InferenceRoute? = null,
)

internal data class LlmStreamChunk(
    val text: String = "",
    val thinking: String = "",
    val done: Boolean = false,
)

internal interface LlmRuntimeBackend {
    val modelId: String
    val origin: InferenceOrigin
    val contextBudgetChars: Int
    val maxContextTokens: Int
    val supportsStatefulSession: Boolean get() = false

    suspend fun generate(request: LlmGenerateRequest): GenerationOutcome<String>
    fun generateStream(request: LlmGenerateRequest): Flow<GenerationOutcome<LlmStreamChunk>>
    suspend fun resetSession() {}
    suspend fun stop() {}
}

internal interface LlmBackendProvider {
    val models: StateFlow<List<AnswerModel>>
    fun refresh() {}
    fun backend(model: AnswerModel): LlmRuntimeBackend?
    suspend fun prepare(model: AnswerModel): Result<Unit> = Result.success(Unit)
}
