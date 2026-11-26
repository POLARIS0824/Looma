package com.example.looma.data.assistant.sessions

import com.example.looma.data.assistant.GenerationOutcome
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend

internal class SummarizationContextCompactor(
    private val thresholdRatio: Float = 0.75f,
    private val fallback: ContextCompactor? = TruncationContextCompactor(),
) : ContextCompactor {
    override suspend fun compactIfNeeded(
        sessionManager: LlmSessionManager,
        backend: LlmRuntimeBackend,
    ): Boolean {
        val messages = sessionManager.messages
        if (messages.size <= 2) return false
        val maxTokens = effectiveMaxTokens(backend)
        if (maxTokens <= 0) return false
        val estimatedTokens = estimateTokens(sessionManager.systemInstruction, messages)
        if (estimatedTokens < (maxTokens * thresholdRatio).toInt()) return false

        val transcript = buildString {
            messages.forEach { msg ->
                append(msg.role.name).append(": ").appendLine(msg.content)
            }
        }
        val summaryPrompt =
            "Summarize the conversation so far concisely, preserving key facts, decisions, and pending tasks:\n\n$transcript"
        val outcome = sessionManager.generateResponse(summaryPrompt, ephemeral = true)
        if (outcome is GenerationOutcome.Success && outcome.value.isNotBlank()) {
            val summary = outcome.value.trim()
            sessionManager.resetSession()
            sessionManager.replaceHistory(
                listOf(
                    LlmMessage(LlmRole.User, "Conversation summary:\n$summary"),
                    LlmMessage(LlmRole.Model, "Understood. Continuing with that context."),
                )
            )
            return true
        }
        return fallback?.compactIfNeeded(sessionManager, backend) ?: false
    }

    private fun effectiveMaxTokens(backend: LlmRuntimeBackend): Int =
        if (backend.maxContextTokens > 0) backend.maxContextTokens
        else backend.contextBudgetChars / CHARS_PER_TOKEN

    private fun estimateTokens(systemInstruction: String?, messages: List<LlmMessage>): Int {
        val totalChars = (systemInstruction?.length ?: 0) + messages.sumOf { it.content.length + 16 }
        return totalChars / CHARS_PER_TOKEN
    }

    private companion object {
        const val CHARS_PER_TOKEN = 4
    }
}
