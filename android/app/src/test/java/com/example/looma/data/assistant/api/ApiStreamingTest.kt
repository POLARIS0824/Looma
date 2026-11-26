package com.example.looma.data.assistant.api

import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.ApiProvider
import com.example.looma.data.assistant.ConnectionStatus
import com.example.looma.data.assistant.GenerationError
import com.example.looma.data.assistant.runtime.LlmError
import com.example.looma.data.assistant.runtime.LlmGenerateRequest
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiStreamingTest {

    private fun connection(
        provider: ApiProvider,
        baseUrl: String = provider.defaultBaseUrl,
        apiKey: String = "test-key",
    ) = ApiConnection(
        id = "conn-1",
        name = "Test",
        provider = provider,
        baseUrl = baseUrl,
        apiKey = apiKey,
        status = ConnectionStatus.Ok,
        models = emptyList(),
    )

    private fun sseHeaders() = headersOf(
        HttpHeaders.ContentType,
        ContentType.Text.EventStream.toString(),
    )

    private fun jsonHeaders() = headersOf(
        HttpHeaders.ContentType,
        ContentType.Application.Json.toString(),
    )

    private fun mockClient(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
            io.ktor.client.request.HttpRequestData,
        ) -> io.ktor.client.request.HttpResponseData,
    ): KtorApiClient = KtorApiClient(HttpClient(MockEngine(handler)))

    @Test
    fun openAiStreamsDeltasThinkingAndStopsAtDone() = runBlocking {
        var capturedUrl: String? = null
        var capturedAccept: String? = null
        var capturedAuth: String? = null
        var capturedBody: String? = null
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"think1\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"reasoning\":\"think2\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"world\"}}]}\n\n")
            append("data: [DONE]\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"ignored\"}}]}\n\n")
        }
        val client = mockClient { request ->
            capturedUrl = request.url.toString()
            capturedAccept = request.headers[HttpHeaders.Accept]
            capturedAuth = request.headers[HttpHeaders.Authorization]
            capturedBody = request.body.toByteArray().decodeToString()
            respond(content = sse, status = HttpStatusCode.OK, headers = sseHeaders())
        }

        val results = client.generateStream(
            connection = connection(ApiProvider.OpenAiCompatible),
            modelId = "gpt-4o",
            messages = listOf(LlmMessage(LlmRole.User, "hi")),
        ).toList()

        assertEquals("https://api.openai.com/v1/chat/completions", capturedUrl)
        assertEquals("text/event-stream", capturedAccept)
        assertEquals("Bearer test-key", capturedAuth)
        assertTrue(JSONObject(capturedBody!!).getBoolean("stream"))

        assertEquals(
            listOf(
                ApiResult.Success(LlmStreamChunk(text = "", thinking = "think1", done = false)),
                ApiResult.Success(LlmStreamChunk(text = "", thinking = "think2", done = false)),
                ApiResult.Success(LlmStreamChunk(text = "Hello ", thinking = "", done = false)),
                ApiResult.Success(LlmStreamChunk(text = "world", thinking = "", done = false)),
                ApiResult.Success(LlmStreamChunk(done = true)),
            ),
            results,
        )
    }

    @Test
    fun openAiSseErrorFrameEmitsFailureAndStops() = runBlocking {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")
            append("data: {\"error\":{\"message\":\"Bad request payload\",\"type\":\"invalid_request_error\"}}\n\n")
            append("data: [DONE]\n\n")
        }
        val client = mockClient {
            respond(content = sse, status = HttpStatusCode.OK, headers = sseHeaders())
        }

        val results = client.generateStream(
            connection = connection(ApiProvider.OpenAiCompatible),
            modelId = "gpt-4o",
            messages = listOf(LlmMessage(LlmRole.User, "hi")),
        ).toList()

        assertEquals(
            listOf(
                ApiResult.Success(LlmStreamChunk(text = "partial", done = false)),
                ApiResult.Failure(GenerationError.Failed("Bad request payload")),
            ),
            results,
        )
    }

    @Test
    fun anthropicStreamsThinkingTextAndStopsAtMessageStop() = runBlocking {
        var capturedUrl: String? = null
        var capturedApiKey: String? = null
        var capturedBody: String? = null
        val sse = buildString {
            append("event: message_start\ndata: {\"type\":\"message_start\"}\n\n")
            append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"ponder\"}}\n\n")
            append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Answer\"}}\n\n")
            append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
            append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"after\"}}\n\n")
        }
        val client = mockClient { request ->
            capturedUrl = request.url.toString()
            capturedApiKey = request.headers["x-api-key"]
            capturedBody = request.body.toByteArray().decodeToString()
            respond(content = sse, status = HttpStatusCode.OK, headers = sseHeaders())
        }

        val results = client.generateStream(
            connection = connection(ApiProvider.Anthropic),
            modelId = "claude-sonnet-4-5",
            messages = listOf(LlmMessage(LlmRole.User, "hi")),
        ).toList()

        assertEquals("https://api.anthropic.com/v1/messages", capturedUrl)
        assertEquals("test-key", capturedApiKey)
        assertTrue(JSONObject(capturedBody!!).getBoolean("stream"))

        assertEquals(
            listOf(
                ApiResult.Success(LlmStreamChunk(thinking = "ponder", done = false)),
                ApiResult.Success(LlmStreamChunk(text = "Answer", done = false)),
                ApiResult.Success(LlmStreamChunk(done = true)),
            ),
            results,
        )
    }

    @Test
    fun anthropicErrorEventEmitsFailure() = runBlocking {
        val sse = buildString {
            append("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n")
        }
        val client = mockClient {
            respond(content = sse, status = HttpStatusCode.OK, headers = sseHeaders())
        }

        val results = client.generateStream(
            connection = connection(ApiProvider.Anthropic),
            modelId = "claude-sonnet-4-5",
            messages = listOf(LlmMessage(LlmRole.User, "hi")),
        ).toList()

        assertEquals(
            listOf(ApiResult.Failure(GenerationError.Failed("Overloaded"))),
            results,
        )
    }

    @Test
    fun geminiStreamsThoughtAndTextWithStreamGenerateContentUrl() = runBlocking {
        var capturedUrl: String? = null
        val sse = buildString {
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"step1\",\"thought\":true}]}}]}\n\n")
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"step2\",\"thought\":true},{\"text\":\"final\"}]}}]}\n\n")
        }
        val client = mockClient { request ->
            capturedUrl = request.url.toString()
            respond(content = sse, status = HttpStatusCode.OK, headers = sseHeaders())
        }

        val results = client.generateStream(
            connection = connection(ApiProvider.Gemini),
            modelId = "models/gemini-2.5-flash",
            messages = listOf(LlmMessage(LlmRole.User, "hi")),
        ).toList()

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            capturedUrl,
        )
        assertEquals(
            listOf(
                ApiResult.Success(LlmStreamChunk(thinking = "step1", done = false)),
                ApiResult.Success(LlmStreamChunk(text = "final", thinking = "step2", done = false)),
                ApiResult.Success(LlmStreamChunk(done = true)),
            ),
            results,
        )
    }

    @Test
    fun geminiErrorFrameEmitsFailure() = runBlocking {
        val sse = "data: {\"error\":{\"code\":500,\"status\":\"INTERNAL\",\"message\":\"Backend crash\"}}\n\n"
        val client = mockClient {
            respond(content = sse, status = HttpStatusCode.OK, headers = sseHeaders())
        }

        val results = client.generateStream(
            connection = connection(ApiProvider.Gemini),
            modelId = "gemini-2.5-flash",
            messages = listOf(LlmMessage(LlmRole.User, "hi")),
        ).toList()

        assertEquals(
            listOf(ApiResult.Failure(GenerationError.Failed("Backend crash"))),
            results,
        )
    }

    @Test
    fun byteLevelChunkSplittingHandlesMidLineUtf8CrLfAndComments() = runBlocking {
        val frame1 = ": keepalive\r\ndata: {\"choices\":[{\"delta\":{\"content\":\"你好世界\"}}]}\r\n\r\n"
        val frame2 = "data: [DONE]\r\n\r\n"
        val allBytes = (frame1 + frame2).toByteArray(Charsets.UTF_8)
        val cjkByteIndex = allBytes.indices.first { (allBytes[it].toInt() and 0xFF) >= 0x80 } + 1

        val channel = ByteChannel(autoFlush = true)
        val writerJob = launch {
            channel.writeFully(allBytes, 0, cjkByteIndex)
            channel.flush()
            channel.writeFully(allBytes, cjkByteIndex, allBytes.size)
            channel.close()
        }

        val client = mockClient {
            respond(content = channel as ByteReadChannel, status = HttpStatusCode.OK, headers = sseHeaders())
        }

        val results = client.generateStream(
            connection = connection(ApiProvider.OpenAiCompatible),
            modelId = "gpt-4o",
            messages = listOf(LlmMessage(LlmRole.User, "hi")),
        ).toList()
        writerJob.join()

        assertEquals(
            listOf(
                ApiResult.Success(LlmStreamChunk(text = "你好世界", done = false)),
                ApiResult.Success(LlmStreamChunk(done = true)),
            ),
            results,
        )
    }

    @Test
    fun backendStopCancelsMidStreamPromptly() = runBlocking {
        val firstChunkRead = CompletableDeferred<Unit>()
        val holdStreamOpen = CompletableDeferred<Unit>()
        val channel = ByteChannel(autoFlush = true)

        val client = mockClient {
            launch {
                val firstFrame = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
                channel.writeFully(firstFrame.toByteArray(Charsets.UTF_8))
                channel.flush()
                holdStreamOpen.await()
            }
            respond(content = channel as ByteReadChannel, status = HttpStatusCode.OK, headers = sseHeaders())
        }

        val backend = ApiRuntimeBackend(
            connection = connection(ApiProvider.OpenAiCompatible),
            modelId = "gpt-4o",
            sessionKey = "api:conn-1:gpt-4o",
            client = client,
        )

        val collected = mutableListOf<LlmResult<LlmStreamChunk>>()
        val collectJob = launch {
            try {
                backend.generateStream(
                    LlmGenerateRequest(
                        modelId = "gpt-4o",
                        messages = listOf(LlmMessage(LlmRole.User, "hi")),
                    ),
                ).collect { chunk ->
                    collected.add(chunk)
                    firstChunkRead.complete(Unit)
                }
            } finally {
                holdStreamOpen.complete(Unit)
                channel.close()
            }
        }

        withTimeout(3_000) {
            firstChunkRead.await()
            backend.stop()
            collectJob.join()
        }

        assertEquals(1, collected.size)
        val first = collected.first() as LlmResult.Success
        assertEquals("hello", first.value.text)
    }

    @Test
    fun http401MapsToAuthFailed() = runBlocking {
        val client = mockClient {
            respond(
                content = """{"error":{"message":"Invalid API key"}}""",
                status = HttpStatusCode.Unauthorized,
                headers = jsonHeaders(),
            )
        }
        val backend = ApiRuntimeBackend(
            connection = connection(ApiProvider.OpenAiCompatible),
            modelId = "gpt-4o",
            sessionKey = "api:conn-1:gpt-4o",
            client = client,
        )

        val results = backend.generateStream(
            LlmGenerateRequest(modelId = "gpt-4o", messages = listOf(LlmMessage(LlmRole.User, "hi"))),
        ).toList()

        assertEquals(1, results.size)
        val failure = results.first() as LlmResult.Failure
        assertEquals(LlmError.AUTH_FAILED, failure.error.code)
    }

    @Test
    fun http403MapsToAuthFailed() = runBlocking {
        val client = mockClient {
            respond(
                content = """{"error":{"message":"Forbidden"}}""",
                status = HttpStatusCode.Forbidden,
                headers = jsonHeaders(),
            )
        }
        val backend = ApiRuntimeBackend(
            connection = connection(ApiProvider.OpenAiCompatible),
            modelId = "gpt-4o",
            sessionKey = "api:conn-1:gpt-4o",
            client = client,
        )

        val results = backend.generateStream(
            LlmGenerateRequest(modelId = "gpt-4o", messages = listOf(LlmMessage(LlmRole.User, "hi"))),
        ).toList()

        assertEquals(1, results.size)
        val failure = results.first() as LlmResult.Failure
        assertEquals(LlmError.AUTH_FAILED, failure.error.code)
    }

    @Test
    fun http429MapsToQuotaExceeded() = runBlocking {
        val client = mockClient {
            respond(
                content = """{"error":{"message":"Rate limit"}}""",
                status = HttpStatusCode.TooManyRequests,
                headers = jsonHeaders(),
            )
        }
        val backend = ApiRuntimeBackend(
            connection = connection(ApiProvider.OpenAiCompatible),
            modelId = "gpt-4o",
            sessionKey = "api:conn-1:gpt-4o",
            client = client,
        )

        val results = backend.generateStream(
            LlmGenerateRequest(modelId = "gpt-4o", messages = listOf(LlmMessage(LlmRole.User, "hi"))),
        ).toList()

        assertEquals(1, results.size)
        val failure = results.first() as LlmResult.Failure
        assertEquals(LlmError.QUOTA_EXCEEDED, failure.error.code)
    }

    @Test
    fun http500MapsToFailedWithExtractedErrorMessage() = runBlocking {
        val client = mockClient {
            respond(
                content = """{"error":{"message":"Upstream server error"}}""",
                status = HttpStatusCode.InternalServerError,
                headers = jsonHeaders(),
            )
        }
        val backend = ApiRuntimeBackend(
            connection = connection(ApiProvider.OpenAiCompatible),
            modelId = "gpt-4o",
            sessionKey = "api:conn-1:gpt-4o",
            client = client,
        )

        val results = backend.generateStream(
            LlmGenerateRequest(modelId = "gpt-4o", messages = listOf(LlmMessage(LlmRole.User, "hi"))),
        ).toList()

        assertEquals(1, results.size)
        val failure = results.first() as LlmResult.Failure
        assertEquals(LlmError.FAILED, failure.error.code)
        assertEquals("Upstream server error", failure.error.message)
    }
}
