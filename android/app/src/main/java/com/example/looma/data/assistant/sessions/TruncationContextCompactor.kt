package com.example.looma.data.assistant.sessions

import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend

internal class TruncationContextCompactor(
    private val thresholdRatio: Float = 0.90f,
    private val targetRatio: Float = 0.50f,
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

        val systemTokens = (sessionManager.systemInstruction?.length ?: 0) / DefaultLlmSessionManager.CHARS_PER_TOKEN
        val budgetTokens = ((maxTokens * targetRatio).toInt() - systemTokens).coerceAtLeast(1)

        var usedTokens = 0
        var startIndex = messages.size
        for (i in messages.indices.reversed()) {
            val msgTokens = (messages[i].content.length + 16) / DefaultLlmSessionManager.CHARS_PER_TOKEN
            if (startIndex < messages.size && usedTokens + msgTokens > budgetTokens) break
            usedTokens += msgTokens
            startIndex = i
        }
        while (startIndex < messages.size && messages[startIndex].role != LlmRole.User) {
            startIndex++
        }
        if (startIndex <= 0 || startIndex >= messages.size) {
            return CompactionResult(compacted = false, tokensBefore = estimatedTokens, tokensAfter = estimatedTokens)
        }

        val kept = messages.subList(startIndex, messages.size).toList()
        sessionManager.replaceHistory(kept)
        val tokensAfter = DefaultLlmSessionManager.estimateTokenCount(sessionManager.systemInstruction, kept)
        return CompactionResult(
            compacted = true,
            strategy = CompactionStrategy.Truncation,
            tokensBefore = estimatedTokens,
            tokensAfter = tokensAfter,
        )
    }
}
