package com.example.looma.data.assistant.api

import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.GenerationError
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

    fun generateStream(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String? = null,
        maxOutputTokens: Int? = null,
        responseMimeType: String? = null,
    ): Flow<ApiResult<LlmStreamChunk>> = flow {
        if (!isValidBaseUrl(connection.baseUrl)) {
            emit(ApiResult.Failure(GenerationError.Failed("Invalid base URL")))
            return@flow
        }
        val protocol = connection.provider.protocol()
        val request = protocol.buildStreamRequest(
            connection = connection,
            modelId = modelId,
            messages = messages,
            systemInstruction = systemInstruction,
            maxOutputTokens = maxOutputTokens,
            responseMimeType = responseMimeType,
        )
        try {
            httpClient.preparePost(request.url) {
                request.headers.forEach { (k, v) -> header(k, v) }
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(request.body ?: "")
            }.execute { response ->
                val code = response.status.value
                if (code !in 200..299) {
                    val body = response.bodyAsText()
                    emit(ApiResult.Failure(mapHttpError(code, body), httpStatus = code))
                    return@execute
                }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(8192)
                val lineBuffer = ByteArrayOutputStream()
                var eventType: String? = null
                val dataLines = mutableListOf<String>()
                var finished = false

                suspend fun dispatchFrame(): Boolean {
                    if (dataLines.isEmpty()) {
                        eventType = null
                        return false
                    }
                    val data = dataLines.joinToString("\n")
                    val currentEvent = eventType
                    eventType = null
                    dataLines.clear()
                    return when (val event = protocol.parseStreamEvent(currentEvent, data)) {
                        is ApiStreamEvent.Delta -> {
                            if (event.text.isNotEmpty() || event.thinking.isNotEmpty()) {
                                emit(
                                    ApiResult.Success(
                                        LlmStreamChunk(
                                            text = event.text,
                                            thinking = event.thinking,
                                            done = false,
                                        ),
                                    ),
                                )
                            }
                            false
                        }
                        ApiStreamEvent.Done -> {
                            emit(ApiResult.Success(LlmStreamChunk(done = true)))
                            true
                        }
                        is ApiStreamEvent.Error -> {
                            emit(ApiResult.Failure(event.error))
                            true
                        }
                        ApiStreamEvent.Ignore -> false
                    }
                }

                suspend fun processLine(line: String): Boolean {
                    if (line.isEmpty()) return dispatchFrame()
                    if (line.startsWith(":")) return false
                    if (line.startsWith("event:")) {
                        eventType = line.removePrefix("event:").trim()
                        return false
                    }
                    if (line.startsWith("data:")) {
                        val raw = line.removePrefix("data:")
                        dataLines.add(if (raw.startsWith(" ")) raw.substring(1) else raw)
                        return false
                    }
                    return false
                }

                fun decodeLineBytes(): String {
                    val bytes = lineBuffer.toByteArray()
                    lineBuffer.reset()
                    val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                        bytes.size - 1
                    } else {
                        bytes.size
                    }
                    return String(bytes, 0, length, Charsets.UTF_8)
                }

                while (!finished) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    for (i in 0 until read) {
                        val b = buffer[i]
                        if (b == '\n'.code.toByte()) {
                            if (processLine(decodeLineBytes())) {
                                finished = true
                                break
                            }
                        } else {
                            lineBuffer.write(b.toInt())
                        }
                    }
                }
                if (!finished && lineBuffer.size() > 0) {
                    if (processLine(decodeLineBytes())) {
                        finished = true
                    }
                }
                if (!finished && dispatchFrame()) {
                    finished = true
                }
                if (!finished) {
                    emit(ApiResult.Success(LlmStreamChunk(done = true)))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: UnknownHostException) {
            emit(ApiResult.Failure(GenerationError.Offline))
        } catch (_: ConnectException) {
            emit(ApiResult.Failure(GenerationError.Offline))
        } catch (_: NoRouteToHostException) {
            emit(ApiResult.Failure(GenerationError.Offline))
        } catch (error: IOException) {
            emit(ApiResult.Failure(GenerationError.Failed(error.message)))
        } catch (error: Exception) {
            emit(ApiResult.Failure(GenerationError.Failed(error.message)))
        }
    }.flowOn(Dispatchers.IO)

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
