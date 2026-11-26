package com.example.looma.data.assistant.runtime

import com.example.looma.data.assistant.sessions.CompactionResult

internal data class AgentStep(
    val label: String,
    val metadata: LlmMetadata = LlmMetadata(),
)

internal data class AgentRequest(
    val question: String,
    val systemInstruction: String? = null,
    val maxSteps: Int = 6,
    val responseMimeType: String? = null,
    val maxOutputTokens: Int? = null,
)

internal data class AgentResponse(
    val text: String,
    val steps: List<AgentStep> = emptyList(),
    val metadata: LlmMetadata = LlmMetadata(),
)

internal sealed interface AgentEvent {
    data object LoopInitiated : AgentEvent
    data class StepStarted(val label: String) : AgentEvent
    data class StreamToken(val token: String, val thinking: String = "") : AgentEvent
    data class ToolCallStarted(val toolName: String, val argumentsJson: String) : AgentEvent
    data class ToolCallFinished(val toolName: String, val output: String, val isError: Boolean) : AgentEvent
    data class ContextCompacted(val result: CompactionResult) : AgentEvent
    data class LoopTerminated(val response: AgentResponse) : AgentEvent
    data object LoopCancelled : AgentEvent
    data class Error(val error: LlmError) : AgentEvent
}
