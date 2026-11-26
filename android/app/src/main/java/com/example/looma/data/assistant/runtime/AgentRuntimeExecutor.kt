package com.example.looma.data.assistant.runtime

import com.example.looma.data.assistant.sessions.CompactionResult
import com.example.looma.data.assistant.sessions.LlmSessionManager
import com.example.looma.data.assistant.tools.ToolsProvider
import kotlinx.coroutines.flow.Flow

internal interface AgentRuntimeExecutor {
    val sessionManager: LlmSessionManager

    suspend fun execute(
        backend: LlmRuntimeBackend,
        request: AgentRequest,
        tools: ToolsProvider? = null,
        onStep: (AgentStep) -> Unit = {},
    ): LlmResult<AgentResponse>

    fun executeStream(
        backend: LlmRuntimeBackend,
        request: AgentRequest,
        tools: ToolsProvider? = null,
    ): Flow<AgentEvent>

    suspend fun compactContext(): CompactionResult
    suspend fun interrupt()
    suspend fun resetSession()
    suspend fun cleanUp()
}
