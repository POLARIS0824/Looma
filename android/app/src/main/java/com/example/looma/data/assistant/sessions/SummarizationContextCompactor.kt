package com.example.looma.data.assistant.sessions

import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend

internal class SummarizationContextCompactor(
    private val thresholdRatio: Float = 0.75f,
    private val fallback: ContextCompactor? = TruncationContextCompactor(),
) : ContextCompactor {
    override suspend fun compactIfNeeded(
        sessionManager: LlmSessionManager,
        backend: LlmRuntimeBackend,
    ): CompactionResult {
        val messages = sessionManager.messages
        if (messages.size <= 2) return CompactionResult(compacted = false)
        val maxTokens = DefaultLlmSessionManager.effectiveMaxTokens(backend)
        if (maxTokens <= 0) return CompactionResult(compacted = false)
        val estimatedTokens = DefaultLlmSessionManager.estimateTokenCount(sessionManager.systemInstruction, messages)
        if (estimatedTokens < (maxTokens * thresholdRatio).toInt()) {
            return CompactionResult(compacted = false, tokensBefore = estimatedTokens, tokensAfter = estimatedTokens)
        }

        val transcript = buildString {
            messages.forEach { msg ->
                append(msg.role.name).append(": ").appendLine(msg.content)
            }
        }
        val summaryPrompt =
            "Summarize the conversation so far concisely, preserving key facts, decisions, and pending tasks:\n\n$transcript"
        val outcome = sessionManager.generateResponse(summaryPrompt, ephemeral = true)
        if (outcome is LlmResult.Success && outcome.value.isNotBlank()) {
            val summary = outcome.value.trim()
            val compactedHistory = listOf(
                LlmMessage(LlmRole.User, "Conversation summary:\n$summary"),
                LlmMessage(LlmRole.Model, "Understood. Continuing with that context."),
            )
            sessionManager.replaceHistory(compactedHistory)
            val tokensAfter = DefaultLlmSessionManager.estimateTokenCount(
                sessionManager.systemInstruction,
                compactedHistory,
            )
            return CompactionResult(
                compacted = true,
                strategy = CompactionStrategy.Summarization,
                tokensBefore = estimatedTokens,
                tokensAfter = tokensAfter,
            )
        }
        return fallback?.compactIfNeeded(sessionManager, backend)
            ?: CompactionResult(compacted = false, tokensBefore = estimatedTokens, tokensAfter = estimatedTokens)
    }
}
