package com.example.looma.data.assistant.sessions

import com.example.looma.data.assistant.runtime.LlmError
import com.example.looma.data.assistant.runtime.LlmGenerateRequest
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultLlmSessionManagerTest {

    private class FakeBackend(
        override val modelId: String,
        override val sessionKey: String,
        override val contextBudgetChars: Int = 16_000,
        override val maxContextTokens: Int = 4_000,
        private val onGenerate: suspend (LlmGenerateRequest) -> LlmResult<String> = {
            LlmResult.Success("response")
        },
        private val onStream: (LlmGenerateRequest) -> Flow<LlmResult<LlmStreamChunk>> = {
            flow { emit(LlmResult.Success(LlmStreamChunk(text = "streamed", done = true))) }
        },
    ) : LlmRuntimeBackend {
        val generateRequests = mutableListOf<LlmGenerateRequest>()
        val streamRequests = mutableListOf<LlmGenerateRequest>()
        var resetCalls = 0

        override suspend fun generate(request: LlmGenerateRequest): LlmResult<String> {
            generateRequests.add(request)
            return onGenerate(request)
        }

        override fun generateStream(request: LlmGenerateRequest): Flow<LlmResult<LlmStreamChunk>> {
            streamRequests.add(request)
            return onStream(request)
        }

        override suspend fun resetSession() {
            resetCalls++
        }
    }

    @Test
    fun `ensureSession resets on sessionKey or systemInstruction change and skips reset when both match`() =
        runBlocking {
            val manager = DefaultLlmSessionManager()
            val conn1A = FakeBackend(modelId = "gpt-4o", sessionKey = "api:conn-1:gpt-4o")
            val conn1B = FakeBackend(modelId = "gpt-4o", sessionKey = "api:conn-1:gpt-4o")
            val conn2 = FakeBackend(modelId = "gpt-4o", sessionKey = "api:conn-2:gpt-4o")

            manager.ensureSession(conn1A, "system-1")
            assertEquals(1, conn1A.resetCalls)

            manager.ensureSession(conn1B, "system-1")
            assertEquals(1, conn1A.resetCalls)
            assertEquals(0, conn1B.resetCalls)
            assertTrue(manager.backend === conn1B)

            manager.ensureSession(conn1B, "system-2")
            assertEquals(1, conn1B.resetCalls)
            assertEquals("system-2", manager.systemInstruction)

            manager.ensureSession(conn2, "system-2")
            assertEquals(2, conn1B.resetCalls)
            assertEquals(1, conn2.resetCalls)
            assertTrue(manager.backend === conn2)
        }

    @Test
    fun `generateResponse appends history on success and skips history when ephemeral or failed`() =
        runBlocking {
            var shouldFail = false
            val backend = FakeBackend(
                modelId = "m1",
                sessionKey = "m1",
                onGenerate = { req ->
                    if (shouldFail) {
                        LlmResult.Failure(LlmError(LlmError.FAILED, "boom"))
                    } else {
                        LlmResult.Success("reply:${req.messages.last().content}")
                    }
                },
            )
            val manager = DefaultLlmSessionManager()
            manager.ensureSession(backend, "sys")

            val normal = manager.generateResponse("hello")
            assertEquals(LlmResult.Success("reply:hello"), normal)
            assertEquals(
                listOf(
                    LlmMessage(LlmRole.User, "hello"),
                    LlmMessage(LlmRole.Model, "reply:hello"),
                ),
                manager.messages,
            )

            val ephemeral = manager.generateResponse("summarize", ephemeral = true)
            assertEquals(LlmResult.Success("reply:summarize"), ephemeral)
            assertEquals(2, manager.messages.size)
            assertTrue(backend.generateRequests[1].ephemeral)
            assertEquals(listOf(LlmMessage(LlmRole.User, "summarize")), backend.generateRequests[1].messages)

            shouldFail = true
            val failed = manager.generateResponse("will fail")
            assertTrue(failed is LlmResult.Failure)
            assertEquals(2, manager.messages.size)
        }

    @Test
    fun `generateStream appends history on completion and skips history when ephemeral or failed`() =
        runBlocking {
            var shouldFail = false
            val backend = FakeBackend(
                modelId = "m1",
                sessionKey = "m1",
                onStream = {
                    flow {
                        if (shouldFail) {
                            emit(LlmResult.Failure(LlmError(LlmError.OFFLINE, "offline")))
                        } else {
                            emit(LlmResult.Success(LlmStreamChunk(text = "part1 ")))
                            emit(LlmResult.Success(LlmStreamChunk(text = "part2", done = true)))
                        }
                    }
                },
            )
            val manager = DefaultLlmSessionManager()
            manager.ensureSession(backend, "sys")

            val chunks = manager.generateStream("ask").toList()
            assertEquals(2, chunks.size)
            assertEquals(
                listOf(
                    LlmMessage(LlmRole.User, "ask"),
                    LlmMessage(LlmRole.Model, "part1 part2"),
                ),
                manager.messages,
            )

            manager.generateStream("ephemeral ask", ephemeral = true).toList()
            assertEquals(2, manager.messages.size)
            assertTrue(backend.streamRequests[1].ephemeral)
            assertEquals(listOf(LlmMessage(LlmRole.User, "ephemeral ask")), backend.streamRequests[1].messages)

            shouldFail = true
            manager.generateStream("fail ask").toList()
            assertEquals(2, manager.messages.size)
        }

    @Test
    fun `replaceHistory updates messages and resets backend session`() = runBlocking {
        val backend = FakeBackend(modelId = "m1", sessionKey = "m1")
        val manager = DefaultLlmSessionManager()
        manager.ensureSession(backend, "sys")
        manager.generateResponse("old question")
        assertEquals(1, backend.resetCalls)

        val replacement = listOf(
            LlmMessage(LlmRole.User, "summary"),
            LlmMessage(LlmRole.Model, "acknowledged"),
        )
        manager.replaceHistory(replacement)

        assertEquals(replacement, manager.messages)
        assertEquals(2, backend.resetCalls)
    }

    @Test
    fun `ensureSession clears history when systemInstruction changes`() = runBlocking {
        val backend = FakeBackend(modelId = "m1", sessionKey = "m1")
        val manager = DefaultLlmSessionManager()
        manager.ensureSession(backend, "vault-A")
        manager.generateResponse("secret from vault A")
        assertEquals(2, manager.messages.size)

        manager.ensureSession(backend, "vault-B")

        assertTrue("Expected history to be cleared on systemInstruction change", manager.messages.isEmpty())
    }

    @Test
    fun `ensureSession preserves history when attaching first systemInstruction after replaceHistory`() = runBlocking {
        val backend = FakeBackend(modelId = "m1", sessionKey = "m1")
        val manager = DefaultLlmSessionManager()
        val restored = listOf(
            LlmMessage(LlmRole.User, "restored question"),
            LlmMessage(LlmRole.Model, "restored answer"),
        )
        manager.replaceHistory(restored)

        manager.ensureSession(backend, "vault-A")

        assertEquals(restored, manager.messages)
    }
}
