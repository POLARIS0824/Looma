package com.example.looma.data.assistant.runtime

import com.example.looma.data.assistant.tools.AgentTool
import com.example.looma.data.assistant.tools.DefaultToolsProvider
import com.example.looma.data.assistant.tools.ToolResult
import com.example.looma.data.assistant.tools.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAgentRuntimeExecutorTest {

    private class FakeBackend(
        override val modelId: String = "fake-model",
        override val sessionKey: String = "fake:fake-model",
        override val contextBudgetChars: Int = 16_000,
        override val maxContextTokens: Int = 4_000,
        private val onGenerate: suspend (LlmGenerateRequest) -> LlmResult<String> = {
            LlmResult.Success("default")
        },
        private val onStream: (LlmGenerateRequest) -> Flow<LlmResult<LlmStreamChunk>> = {
            flow { emit(LlmResult.Success(LlmStreamChunk(text = "default", done = true))) }
        },
    ) : LlmRuntimeBackend {
        val generateRequests = mutableListOf<LlmGenerateRequest>()
        val streamRequests = mutableListOf<LlmGenerateRequest>()
        var resetCalls = 0
        var stopCalls = 0

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

        override suspend fun stop() {
            stopCalls++
        }
    }

    private fun echoTool(
        name: String = "echo_tool",
        onExecute: (JSONObject) -> ToolResult = { args ->
            ToolResult("echo:${args.optString("q")}")
        },
    ): AgentTool = object : AgentTool {
        override val spec = ToolSpec(
            name = name,
            description = "Echoes input",
            parametersSchemaJson = """{"type":"object","properties":{"q":{"type":"string"}}}""",
        )

        override suspend fun execute(args: JSONObject): ToolResult = onExecute(args)
    }

    @Test
    fun `execute returns single-step response and invokes onStep`() = runBlocking {
        val backend = FakeBackend(
            onGenerate = {
                LlmResult.Success(
                    value = "Hello from model",
                    metadata = LlmMetadata(mapOf("tokens" to "12")),
                )
            },
        )
        val executor = DefaultAgentRuntimeExecutor(contextCompactor = null)
        val steps = mutableListOf<AgentStep>()

        val result = executor.execute(
            backend = backend,
            request = AgentRequest(question = "Hi", systemInstruction = "Be concise"),
            onStep = { steps.add(it) },
        )

        assertTrue(result is LlmResult.Success)
        val response = (result as LlmResult.Success).value
        assertEquals("Hello from model", response.text)
        assertEquals(listOf(AgentStep("generate")), response.steps)
        assertEquals(listOf(AgentStep("generate")), steps)
        assertEquals("12", response.metadata.attributes["tokens"])
        assertEquals(1, backend.generateRequests.size)
        assertEquals("Be concise", backend.generateRequests.single().systemInstruction)
    }

    @Test
    fun `executeStream emits LoopInitiated StepStarted StreamToken and LoopTerminated for single-step response`() =
        runBlocking {
            val backend = FakeBackend(
                onStream = {
                    flow {
                        emit(LlmResult.Success(LlmStreamChunk(thinking = "plan")))
                        emit(LlmResult.Success(LlmStreamChunk(text = "Hello ")))
                        emit(LlmResult.Success(LlmStreamChunk(text = "world", done = true)))
                    }
                },
            )
            val executor = DefaultAgentRuntimeExecutor(contextCompactor = null)

            val events = executor.executeStream(
                backend = backend,
                request = AgentRequest(question = "Hi"),
            ).toList()

            assertEquals(AgentEvent.LoopInitiated, events[0])
            assertEquals(AgentEvent.StepStarted("generate"), events[1])
            assertEquals(AgentEvent.StreamToken(token = "", thinking = "plan"), events[2])
            assertEquals(AgentEvent.StreamToken(token = "Hello "), events[3])
            assertEquals(AgentEvent.StreamToken(token = "world"), events[4])
            val terminated = events[5] as AgentEvent.LoopTerminated
            assertEquals("Hello world", terminated.response.text)
            assertEquals(listOf(AgentStep("generate")), terminated.response.steps)
            assertEquals(6, events.size)
        }

    @Test
    fun `execute runs multi-step tool loop and returns final answer`() = runBlocking {
        var callCount = 0
        val backend = FakeBackend(
            onGenerate = {
                callCount++
                if (callCount == 1) {
                    LlmResult.Success("""{"tool":"echo_tool","arguments":{"q":"ping"}}""")
                } else {
                    LlmResult.Success("Final answer after tool")
                }
            },
        )
        val receivedArgs = mutableListOf<String>()
        val tools = DefaultToolsProvider(
            listOf(
                echoTool { args ->
                    receivedArgs.add(args.toString())
                    ToolResult("pong")
                },
            ),
        )
        val executor = DefaultAgentRuntimeExecutor(contextCompactor = null)
        val observedSteps = mutableListOf<AgentStep>()

        val result = executor.execute(
            backend = backend,
            request = AgentRequest(question = "Run tool", maxSteps = 4),
            tools = tools,
            onStep = { observedSteps.add(it) },
        )

        assertTrue(result is LlmResult.Success)
        val response = (result as LlmResult.Success).value
        assertEquals("Final answer after tool", response.text)
        assertEquals(listOf("generate", "echo_tool"), response.steps.map { it.label })
        assertEquals(listOf("generate", "echo_tool"), observedSteps.map { it.label })
        assertEquals(1, receivedArgs.size)
        assertEquals(2, backend.generateRequests.size)
        assertEquals(
            "Tool 'echo_tool' result:\npong",
            backend.generateRequests[1].messages.last().content,
        )
    }

    @Test
    fun `executeStream emits ToolCallStarted and ToolCallFinished across multi-step tool loop`() =
        runBlocking {
            var callCount = 0
            val backend = FakeBackend(
                onStream = {
                    callCount++
                    if (callCount == 1) {
                        flow {
                            emit(LlmResult.Success(LlmStreamChunk(text = """{"tool":"echo_tool",""")))
                            emit(LlmResult.Success(LlmStreamChunk(text = """"arguments":{"q":"ping"}}""", done = true)))
                        }
                    } else {
                        flow {
                            emit(LlmResult.Success(LlmStreamChunk(text = "Done: ", done = false)))
                            emit(LlmResult.Success(LlmStreamChunk(text = "pong", done = true)))
                        }
                    }
                },
            )
            val tools = DefaultToolsProvider(listOf(echoTool()))
            val executor = DefaultAgentRuntimeExecutor(contextCompactor = null)

            val events = executor.executeStream(
                backend = backend,
                request = AgentRequest(question = "Run tool", maxSteps = 4),
                tools = tools,
            ).toList()

            val toolStarted = events.filterIsInstance<AgentEvent.ToolCallStarted>()
            val toolFinished = events.filterIsInstance<AgentEvent.ToolCallFinished>()
            assertEquals(1, toolStarted.size)
            assertEquals("echo_tool", toolStarted.single().toolName)
            assertEquals("""{"q":"ping"}""", toolStarted.single().argumentsJson)

            assertEquals(1, toolFinished.size)
            assertEquals(
                AgentEvent.ToolCallFinished(
                    toolName = "echo_tool",
                    output = "echo:ping",
                    isError = false,
                ),
                toolFinished.single(),
            )

            val terminated = events.filterIsInstance<AgentEvent.LoopTerminated>().single()
            assertEquals("Done: pong", terminated.response.text)
            assertEquals(listOf("generate", "echo_tool"), terminated.response.steps.map { it.label })
        }

    @Test
    fun `execute returns Failure when model keeps returning tool calls for all maxSteps`() = runBlocking {
        val backend = FakeBackend(
            onGenerate = {
                LlmResult.Success("""{"tool":"echo_tool","arguments":{"q":"again"}}""")
            },
        )
        val tools = DefaultToolsProvider(listOf(echoTool()))
        val executor = DefaultAgentRuntimeExecutor(contextCompactor = null)

        val result = executor.execute(
            backend = backend,
            request = AgentRequest(question = "Loop forever", maxSteps = 2),
            tools = tools,
        )

        assertTrue("Expected Failure when maxSteps=2 is exhausted by tool calls, got $result", result is LlmResult.Failure)
    }

    @Test
    fun `executeStream emits Error and not LoopTerminated when model keeps returning tool calls for all maxSteps`() =
        runBlocking {
            val backend = FakeBackend(
                onStream = {
                    flow {
                        emit(
                            LlmResult.Success(
                                LlmStreamChunk(
                                    text = """{"tool":"echo_tool","arguments":{"q":"again"}}""",
                                    done = true,
                                ),
                            ),
                        )
                    }
                },
            )
            val tools = DefaultToolsProvider(listOf(echoTool()))
            val executor = DefaultAgentRuntimeExecutor(contextCompactor = null)

            val events = executor.executeStream(
                backend = backend,
                request = AgentRequest(question = "Loop forever", maxSteps = 2),
                tools = tools,
            ).toList()

            assertTrue(
                "Expected AgentEvent.Error when maxSteps=2 is exhausted, got $events",
                events.any { it is AgentEvent.Error },
            )
            assertFalse(
                "Expected no AgentEvent.LoopTerminated when maxSteps=2 is exhausted, got $events",
                events.any { it is AgentEvent.LoopTerminated },
            )
        }

    @Test
    fun `interrupt during executeStream emits LoopCancelled and invokes backend stop`() = runBlocking {
        val streamStarted = CompletableDeferred<Unit>()
        val backend = FakeBackend(
            onStream = {
                flow {
                    emit(LlmResult.Success(LlmStreamChunk(text = "partial")))
                    streamStarted.complete(Unit)
                    delay(10_000)
                }
            },
        )
        val executor = DefaultAgentRuntimeExecutor(contextCompactor = null)
        val events = mutableListOf<AgentEvent>()

        val collectJob = launch {
            executor.executeStream(
                backend = backend,
                request = AgentRequest(question = "Stream"),
            ).toList(events)
        }

        withTimeout(5_000) {
            streamStarted.await()
            executor.interrupt()
            collectJob.join()
        }

        assertTrue("Expected backend.stop() to be called", backend.stopCalls >= 1)
        assertTrue("Expected LoopCancelled in $events", events.contains(AgentEvent.LoopCancelled))
        assertFalse("Expected no LoopTerminated in $events", events.any { it is AgentEvent.LoopTerminated })
    }

    @Test
    fun `execute returns Failure when backend fails`() = runBlocking {
        val expectedError = LlmError(LlmError.OFFLINE, "network down")
        val backend = FakeBackend(
            onGenerate = { LlmResult.Failure(expectedError) },
        )
        val executor = DefaultAgentRuntimeExecutor(contextCompactor = null)

        val result = executor.execute(
            backend = backend,
            request = AgentRequest(question = "Hi"),
        )

        assertEquals(LlmResult.Failure(expectedError), result)
    }

    @Test
    fun `executeStream emits Error and not LoopTerminated when backend fails`() = runBlocking {
        val expectedError = LlmError(LlmError.QUOTA_EXCEEDED, "rate limited")
        val backend = FakeBackend(
            onStream = {
                flow {
                    emit(LlmResult.Failure(expectedError))
                }
            },
        )
        val executor = DefaultAgentRuntimeExecutor(contextCompactor = null)

        val events = executor.executeStream(
            backend = backend,
            request = AgentRequest(question = "Hi"),
        ).toList()

        assertEquals(
            listOf(
                AgentEvent.LoopInitiated,
                AgentEvent.StepStarted("generate"),
                AgentEvent.Error(expectedError),
            ),
            events,
        )
    }

    @Test
    fun `executeStream buffers tool-call JSON and does not emit it as StreamToken`() = runBlocking {
        var callCount = 0
        val backend = FakeBackend(
            onStream = {
                callCount++
                if (callCount == 1) {
                    flow {
                        emit(LlmResult.Success(LlmStreamChunk(thinking = "checking")))
                        emit(LlmResult.Success(LlmStreamChunk(text = """{"tool":"echo_tool",""")))
                        emit(LlmResult.Success(LlmStreamChunk(text = """"arguments":{"q":"ping"}}""", done = true)))
                    }
                } else {
                    flow {
                        emit(LlmResult.Success(LlmStreamChunk(text = "Result: ", done = false)))
                        emit(LlmResult.Success(LlmStreamChunk(text = "pong", done = true)))
                    }
                }
            },
        )
        val tools = DefaultToolsProvider(listOf(echoTool()))
        val executor = DefaultAgentRuntimeExecutor(contextCompactor = null)

        val events = executor.executeStream(
            backend = backend,
            request = AgentRequest(question = "Run tool", maxSteps = 4),
            tools = tools,
        ).toList()

        val streamTokens = events.filterIsInstance<AgentEvent.StreamToken>()
        assertEquals(
            listOf(
                AgentEvent.StreamToken(token = "", thinking = "checking"),
                AgentEvent.StreamToken(token = "Result: "),
                AgentEvent.StreamToken(token = "pong"),
            ),
            streamTokens,
        )
    }

    @Test
    fun `executeStream flushes buffered brace-prefixed non-tool text as single StreamToken`() = runBlocking {
        val backend = FakeBackend(
            onStream = {
                flow {
                    emit(LlmResult.Success(LlmStreamChunk(text = """{"name": "demo-app",""")))
                    emit(LlmResult.Success(LlmStreamChunk(text = """ "version": "1.0"}""", done = true)))
                }
            },
        )
        val tools = DefaultToolsProvider(listOf(echoTool()))
        val executor = DefaultAgentRuntimeExecutor(contextCompactor = null)

        val events = executor.executeStream(
            backend = backend,
            request = AgentRequest(question = "Show json", maxSteps = 4),
            tools = tools,
        ).toList()

        val streamTokens = events.filterIsInstance<AgentEvent.StreamToken>()
        assertEquals(
            listOf(AgentEvent.StreamToken(token = """{"name": "demo-app", "version": "1.0"}""")),
            streamTokens,
        )
        val terminated = events.filterIsInstance<AgentEvent.LoopTerminated>().single()
        assertEquals("""{"name": "demo-app", "version": "1.0"}""", terminated.response.text)
    }
}
