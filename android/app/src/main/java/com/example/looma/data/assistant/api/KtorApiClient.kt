package com.example.looma.data.assistant.api

import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.GenerationError
import com.example.looma.data.assistant.runtime.LlmMessage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal class KtorApiClient(
    private val httpClient: HttpClient,
) {
    suspend fun listModels(connection: ApiConnection): ApiResult<List<String>> {
        val protocol = connection.provider.protocol()
        return execute(connection, protocol.buildListModelsRequest(connection)) { body ->
            ApiResult.Success(protocol.parseModelIds(body))
        }
    }

    suspend fun testConnection(connection: ApiConnection): ApiResult<Unit> =
        when (val result = listModels(connection)) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }

    suspend fun generate(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String? = null,
        maxOutputTokens: Int? = null,
        responseMimeType: String? = null,
    ): ApiResult<String> {
        val protocol = connection.provider.protocol()
        val request = protocol.buildGenerateRequest(
            connection = connection,
            modelId = modelId,
            messages = messages,
            systemInstruction = systemInstruction,
            maxOutputTokens = maxOutputTokens,
            responseMimeType = responseMimeType,
        )
        return execute(connection, request) { body ->
            val text = protocol.parseGeneratedText(body)
            if (text != null) {
                ApiResult.Success(text)
            } else {
                ApiResult.Failure(GenerationError.Failed("Empty response"))
            }
        }
    }

    private suspend fun <T> execute(
        connection: ApiConnection,
        request: ApiHttpRequest,
        onSuccess: (String) -> ApiResult<T>,
    ): ApiResult<T> {
        if (!isValidBaseUrl(connection.baseUrl)) {
            return ApiResult.Failure(GenerationError.Failed("Invalid base URL"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val response = if (request.body == null) {
                    httpClient.get(request.url) {
                        request.headers.forEach { (k, v) -> header(k, v) }
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    }
                } else {
                    httpClient.post(request.url) {
                        request.headers.forEach { (k, v) -> header(k, v) }
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        contentType(ContentType.Application.Json)
                        setBody(request.body)
                    }
                }
                val code = response.status.value
                val body = response.bodyAsText()
                if (code in 200..299) {
                    onSuccess(body)
                } else {
                    ApiResult.Failure(mapHttpError(code, body), httpStatus = code)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: UnknownHostException) {
                ApiResult.Failure(GenerationError.Offline)
            } catch (_: ConnectException) {
                ApiResult.Failure(GenerationError.Offline)
            } catch (_: NoRouteToHostException) {
                ApiResult.Failure(GenerationError.Offline)
            } catch (error: IOException) {
                ApiResult.Failure(GenerationError.Failed(error.message))
            } catch (error: Exception) {
                ApiResult.Failure(GenerationError.Failed(error.message))
            }
        }
    }

    private fun isValidBaseUrl(baseUrl: String): Boolean {
        val trimmed = baseUrl.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
        return try {
            !URI(trimmed).host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun mapHttpError(code: Int, body: String): GenerationError = when (code) {
        429 -> GenerationError.QuotaExceeded
        401, 403 -> GenerationError.Failed("Authentication failed (HTTP $code)")
        else -> GenerationError.Failed(extractErrorMessage(body) ?: "HTTP $code")
    }

    private fun extractErrorMessage(body: String): String? = try {
        val json = JSONObject(body)
        val nested = (json.optJSONObject("error")?.opt("message") as? String)?.trim()
        val top = (json.opt("message") as? String)?.trim()
        nested?.takeIf { it.isNotEmpty() } ?: top?.takeIf { it.isNotEmpty() }
    } catch (_: JSONException) {
        null
    }
}
