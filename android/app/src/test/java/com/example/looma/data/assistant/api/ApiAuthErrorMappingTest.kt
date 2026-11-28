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
import com.example.looma.data.assistant.toGenerationError
import com.example.looma.data.assistant.toLlmError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiAuthErrorMappingTest {

    private fun connection() = ApiConnection(
        id = "conn-1",
        name = "Test",
        provider = ApiProvider.OpenAiCompatible,
        baseUrl = ApiProvider.OpenAiCompatible.defaultBaseUrl,
        apiKey = "bad-key",
        status = ConnectionStatus.Ok,
        models = emptyList(),
    )

    private fun backendWithStatus(status: HttpStatusCode, body: String): ApiRuntimeBackend {
        val client = KtorApiClient(
            HttpClient(
                MockEngine {
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf(
                            HttpHeaders.ContentType,
                            ContentType.Application.Json.toString(),
                        ),
                    )
                },
            ),
        )
        return ApiRuntimeBackend(
            connection = connection(),
            modelId = "gpt-4o",
            sessionKey = "api:conn-1:gpt-4o",
            client = client,
        )
    }

    private fun request() = LlmGenerateRequest(
        modelId = "api:conn-1:gpt-4o",
        messages = listOf(LlmMessage(LlmRole.User, "hi")),
    )

    @Test
    fun http401MapsToAuthFailedButNotAppCheckInGenerateAndStream() = runBlocking {
        val backend = backendWithStatus(
            HttpStatusCode.Unauthorized,
            """{"error":{"message":"Invalid API key"}}""",
        )

        val genResult = backend.generate(request())
        assertTrue(genResult is LlmResult.Failure)
        val genError = (genResult as LlmResult.Failure).error
        assertEquals(LlmError.AUTH_FAILED, genError.code)
        assertTrue(genError.toGenerationError() is GenerationError.Failed)

        val streamResults = backend.generateStream(request()).toList()
        assertEquals(1, streamResults.size)
        val streamError = (streamResults.single() as LlmResult.Failure).error
        assertEquals(LlmError.AUTH_FAILED, streamError.code)
        assertTrue(streamError.toGenerationError() is GenerationError.Failed)
    }

    @Test
    fun http403MapsToAuthFailedButNotAppCheckInGenerateAndStream() = runBlocking {
        val backend = backendWithStatus(
            HttpStatusCode.Forbidden,
            """{"error":{"message":"Forbidden"}}""",
        )

        val genResult = backend.generate(request())
        assertTrue(genResult is LlmResult.Failure)
        val genError = (genResult as LlmResult.Failure).error
        assertEquals(LlmError.AUTH_FAILED, genError.code)
        assertTrue(genError.toGenerationError() is GenerationError.Failed)

        val streamResults = backend.generateStream(request()).toList()
        assertEquals(1, streamResults.size)
        val streamError = (streamResults.single() as LlmResult.Failure).error
        assertEquals(LlmError.AUTH_FAILED, streamError.code)
        assertTrue(streamError.toGenerationError() is GenerationError.Failed)
    }

    @Test
    fun appCheckFailedRoundTripsThroughAppCheckCode() {
        assertEquals(
            GenerationError.AppCheckFailed,
            LlmError(LlmError.APP_CHECK_FAILED, "app check failed").toGenerationError(),
        )
        assertEquals(
            LlmError.APP_CHECK_FAILED,
            GenerationError.AppCheckFailed.toLlmError().code,
        )
    }
}
