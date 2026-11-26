package com.example.looma.data.assistant.runtime

import com.example.looma.data.assistant.AgentStep
import com.example.looma.data.assistant.GenerationError
import com.example.looma.data.assistant.GenerationMeta
import com.example.looma.data.assistant.InferenceOrigin
import com.example.looma.data.assistant.InferenceRoute

internal data class AgentRequest(
    val question: String,
    val systemInstruction: String? = null,
    val maxSteps: Int = 6,
    val route: InferenceRoute? = null,
    val responseMimeType: String? = null,
    val maxOutputTokens: Int? = null,
)

internal data class AgentResponse(
    val text: String,
    val steps: List<AgentStep> = emptyList(),
    val meta: GenerationMeta,
)

internal sealed interface AgentEvent {
    data object LoopInitiated : AgentEvent
    data class StepStarted(val label: String, val origin: InferenceOrigin) : AgentEvent
    data class StreamToken(val token: String, val thinking: String = "") : AgentEvent
    data class LoopTerminated(val response: AgentResponse) : AgentEvent
    data object LoopCancelled : AgentEvent
    data class Error(val error: GenerationError) : AgentEvent
}
