package com.example.looma.data.assistant.sessions

import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend

internal class TruncationContextCompactor(
    private val thresholdRatio: Float = 0.90f,
    private val targetRatio: Float = 0.50f,
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

        val systemTokens = (sessionManager.systemInstruction?.length ?: 0) / CHARS_PER_TOKEN
        val budgetTokens = ((maxTokens * targetRatio).toInt() - systemTokens).coerceAtLeast(1)

        var usedTokens = 0
        var startIndex = messages.size
        for (i in messages.indices.reversed()) {
            val msgTokens = (messages[i].content.length + 16) / CHARS_PER_TOKEN
            if (startIndex < messages.size && usedTokens + msgTokens > budgetTokens) break
            usedTokens += msgTokens
            startIndex = i
        }
        while (startIndex < messages.size && messages[startIndex].role != LlmRole.User) {
            startIndex++
        }
        if (startIndex <= 0 || startIndex >= messages.size) return false

        val kept = messages.subList(startIndex, messages.size).toList()
        sessionManager.resetSession()
        sessionManager.replaceHistory(kept)
        return true
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
