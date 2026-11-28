package com.example.looma.data.assistant.runtime

import kotlinx.coroutines.flow.Flow

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
)

internal data class LlmStreamChunk(
    val text: String = "",
    val thinking: String = "",
    val done: Boolean = false,
)

internal data class LlmError(
    val code: String,
    val message: String? = null,
) {
    companion object {
        const val OFFLINE = "offline"
        const val QUOTA_EXCEEDED = "quota_exceeded"
        const val UNAVAILABLE = "unavailable"
        const val AUTH_FAILED = "auth_failed"
        const val APP_CHECK_FAILED = "app_check_failed"
        const val FAILED = "failed"
    }
}

internal data class LlmMetadata(
    val attributes: Map<String, String> = emptyMap(),
)

internal sealed interface LlmResult<out T> {
    data class Success<T>(val value: T, val metadata: LlmMetadata = LlmMetadata()) : LlmResult<T>
    data class Failure(val error: LlmError) : LlmResult<Nothing>
}

internal interface LlmRuntimeBackend {
    val modelId: String
    val sessionKey: String get() = modelId
    val contextBudgetChars: Int
    val maxContextTokens: Int
    val supportsStatefulSession: Boolean get() = false

    suspend fun generate(request: LlmGenerateRequest): LlmResult<String>
    fun generateStream(request: LlmGenerateRequest): Flow<LlmResult<LlmStreamChunk>>
    suspend fun resetSession() {}
    suspend fun stop() {}
}
