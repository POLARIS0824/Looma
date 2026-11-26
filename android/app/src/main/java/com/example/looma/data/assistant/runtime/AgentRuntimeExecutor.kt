package com.example.looma.data.assistant.runtime

import com.example.looma.data.assistant.AgentStep
import com.example.looma.data.assistant.GenerationOutcome
import com.example.looma.data.assistant.tools.ToolsProvider
import kotlinx.coroutines.flow.Flow

internal interface AgentRuntimeExecutor {
    suspend fun execute(
        backend: LlmRuntimeBackend,
        request: AgentRequest,
        tools: ToolsProvider? = null,
        onStep: (AgentStep) -> Unit = {},
    ): GenerationOutcome<AgentResponse>

    fun executeStream(
        backend: LlmRuntimeBackend,
        request: AgentRequest,
        tools: ToolsProvider? = null,
    ): Flow<AgentEvent>

    suspend fun interrupt()
    suspend fun resetSession()
    suspend fun cleanUp()
}
