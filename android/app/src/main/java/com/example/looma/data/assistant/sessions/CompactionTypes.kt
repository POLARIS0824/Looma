package com.example.looma.data.assistant.sessions

internal enum class CompactionStrategy {
    Summarization,
    Truncation,
}

internal data class CompactionConfig(
    val summarizationThresholdRatio: Float = 0.75f,
    val truncationThresholdRatio: Float = 0.90f,
    val truncationTargetRatio: Float = 0.50f,
)

internal data class CompactionResult(
    val compacted: Boolean,
    val strategy: CompactionStrategy? = null,
    val tokensBefore: Int = 0,
    val tokensAfter: Int = 0,
)

internal data class ContextUsage(
    val estimatedTokens: Int,
    val maxTokens: Int,
    val ratio: Float,
)
