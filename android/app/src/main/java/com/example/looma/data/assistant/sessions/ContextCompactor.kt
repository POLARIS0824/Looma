package com.example.looma.data.assistant.sessions

import com.example.looma.data.assistant.runtime.LlmRuntimeBackend

internal interface ContextCompactor {
    suspend fun compactIfNeeded(
        sessionManager: LlmSessionManager,
        backend: LlmRuntimeBackend,
    ): CompactionResult
}
