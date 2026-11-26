package com.example.looma.data.assistant.sessions

import com.example.looma.data.assistant.runtime.LlmError
import com.example.looma.data.assistant.runtime.LlmGenerateRequest
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactorTest {

    private class FakeBackend(
        override val maxContextTokens: Int,
        private val onGenerate: suspend (LlmGenerateRequest) -> LlmResult<String> = {
            LlmResult.Success("Compact summary")
        },
    ) : LlmRuntimeBackend {
        override val modelId: String = "fake-model"
        override val contextBudgetChars: Int = maxContextTokens * 4
        val generateRequests = mutableListOf<LlmGenerateRequest>()
        var resetCalls = 0

        override suspend fun generate(request: LlmGenerateRequest): LlmResult<String> {
            generateRequests.add(request)
            return onGenerate(request)
        }

        override fun generateStream(request: LlmGenerateRequest): Flow<LlmResult<LlmStreamChunk>> =
            emptyFlow()

        override suspend fun resetSession() {
            resetCalls++
        }
    }

    private fun messageOfTokens(role: LlmRole, tokens: Int): LlmMessage {
        val contentLength = (tokens * DefaultLlmSessionManager.CHARS_PER_TOKEN - 16).coerceAtLeast(1)
        return LlmMessage(role, "x".repeat(contentLength))
    }

    @Test
    fun `SummarizationContextCompactor skips when below threshold`() = runBlocking {
        val backend = FakeBackend(maxContextTokens = 1000)
        val manager = DefaultLlmSessionManager()
        manager.ensureSession(backend)
        val original = listOf(
            messageOfTokens(LlmRole.User, 100),
            messageOfTokens(LlmRole.Model, 100),
            messageOfTokens(LlmRole.User, 100),
        )
        manager.replaceHistory(original)

        val result = SummarizationContextCompactor(thresholdRatio = 0.75f).compactIfNeeded(manager, backend)

        assertFalse(result.compacted)
        assertEquals(original, manager.messages)
        assertTrue(backend.generateRequests.isEmpty())
    }

    @Test
    fun `SummarizationContextCompactor skips when messages size is at most 2`() = runBlocking {
        val backend = FakeBackend(maxContextTokens = 100)
        val manager = DefaultLlmSessionManager()
        manager.ensureSession(backend)
        val original = listOf(
            messageOfTokens(LlmRole.User, 80),
            messageOfTokens(LlmRole.Model, 80),
        )
        manager.replaceHistory(original)

        val result = SummarizationContextCompactor(thresholdRatio = 0.75f).compactIfNeeded(manager, backend)

        assertFalse(result.compacted)
        assertEquals(original, manager.messages)
        assertTrue(backend.generateRequests.isEmpty())
    }

    @Test
    fun `SummarizationContextCompactor summarizes ephemerally and replaces history above threshold`() =
        runBlocking {
            val backend = FakeBackend(
                maxContextTokens = 100,
                onGenerate = { LlmResult.Success("Key facts preserved") },
            )
            val manager = DefaultLlmSessionManager()
            manager.ensureSession(backend)
            manager.replaceHistory(
                listOf(
                    messageOfTokens(LlmRole.User, 30),
                    messageOfTokens(LlmRole.Model, 30),
                    messageOfTokens(LlmRole.User, 30),
                ),
            )
            val resetsBefore = backend.resetCalls

            val result = SummarizationContextCompactor(thresholdRatio = 0.75f).compactIfNeeded(manager, backend)

            assertTrue(result.compacted)
            assertEquals(CompactionStrategy.Summarization, result.strategy)
            assertEquals(1, backend.generateRequests.size)
            assertTrue(backend.generateRequests.single().ephemeral)
            assertEquals(
                listOf(
                    LlmMessage(LlmRole.User, "Conversation summary:\nKey facts preserved"),
                    LlmMessage(LlmRole.Model, "Understood. Continuing with that context."),
                ),
                manager.messages,
            )
            assertEquals(resetsBefore + 1, backend.resetCalls)
        }

    @Test
    fun `SummarizationContextCompactor falls back to truncation when summarization fails`() = runBlocking {
        val backend = FakeBackend(
            maxContextTokens = 100,
            onGenerate = { LlmResult.Failure(LlmError(LlmError.FAILED, "unavailable")) },
        )
        val manager = DefaultLlmSessionManager()
        manager.ensureSession(backend)
        val msg1 = messageOfTokens(LlmRole.User, 30)
        val msg2 = messageOfTokens(LlmRole.Model, 30)
        val msg3 = messageOfTokens(LlmRole.User, 20)
        val msg4 = messageOfTokens(LlmRole.Model, 20)
        manager.replaceHistory(listOf(msg1, msg2, msg3, msg4))

        val compactor = SummarizationContextCompactor(
            thresholdRatio = 0.75f,
            fallback = TruncationContextCompactor(thresholdRatio = 0.90f, targetRatio = 0.50f),
        )
        val result = compactor.compactIfNeeded(manager, backend)

        assertTrue(result.compacted)
        assertEquals(CompactionStrategy.Truncation, result.strategy)
        assertEquals(listOf(msg3, msg4), manager.messages)
    }

    @Test
    fun `SummarizationContextCompactor falls back to truncation when summarization returns blank`() =
        runBlocking {
            val backend = FakeBackend(
                maxContextTokens = 100,
                onGenerate = { LlmResult.Success("   \n  ") },
            )
            val manager = DefaultLlmSessionManager()
            manager.ensureSession(backend)
            val msg1 = messageOfTokens(LlmRole.User, 30)
            val msg2 = messageOfTokens(LlmRole.Model, 30)
            val msg3 = messageOfTokens(LlmRole.User, 20)
            val msg4 = messageOfTokens(LlmRole.Model, 20)
            manager.replaceHistory(listOf(msg1, msg2, msg3, msg4))

            val compactor = SummarizationContextCompactor(
                thresholdRatio = 0.75f,
                fallback = TruncationContextCompactor(thresholdRatio = 0.90f, targetRatio = 0.50f),
            )
            val result = compactor.compactIfNeeded(manager, backend)

            assertTrue(result.compacted)
            assertEquals(CompactionStrategy.Truncation, result.strategy)
            assertEquals(listOf(msg3, msg4), manager.messages)
        }

    @Test
    fun `TruncationContextCompactor skips when below threshold`() = runBlocking {
        val backend = FakeBackend(maxContextTokens = 1000)
        val manager = DefaultLlmSessionManager()
        manager.ensureSession(backend)
        val original = listOf(
            messageOfTokens(LlmRole.User, 100),
            messageOfTokens(LlmRole.Model, 100),
            messageOfTokens(LlmRole.User, 100),
        )
        manager.replaceHistory(original)

        val result = TruncationContextCompactor(thresholdRatio = 0.90f).compactIfNeeded(manager, backend)

        assertFalse(result.compacted)
        assertEquals(original, manager.messages)
    }

    @Test
    fun `TruncationContextCompactor trims oldest messages and aligns kept tail on User message`() =
        runBlocking {
            val backend = FakeBackend(maxContextTokens = 100)
            val manager = DefaultLlmSessionManager()
            manager.ensureSession(backend)
            val m1 = messageOfTokens(LlmRole.User, 30)
            val m2 = messageOfTokens(LlmRole.Model, 30)
            val m3 = messageOfTokens(LlmRole.User, 15)
            val m4 = messageOfTokens(LlmRole.Model, 15)
            val m5 = messageOfTokens(LlmRole.User, 10)
            manager.replaceHistory(listOf(m1, m2, m3, m4, m5))
            val resetsBefore = backend.resetCalls

            val result = TruncationContextCompactor(
                thresholdRatio = 0.90f,
                targetRatio = 0.45f,
            ).compactIfNeeded(manager, backend)

            assertTrue(result.compacted)
            assertEquals(CompactionStrategy.Truncation, result.strategy)
            assertTrue(result.tokensAfter < result.tokensBefore)
            assertEquals(LlmRole.User, manager.messages.first().role)
            assertEquals(listOf(m3, m4, m5), manager.messages)
            assertEquals(resetsBefore + 1, backend.resetCalls)
        }
}
