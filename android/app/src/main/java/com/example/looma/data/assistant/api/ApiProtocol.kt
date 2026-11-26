package com.example.looma.data.assistant.api

import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.ApiProvider
import com.example.looma.data.assistant.GenerationError
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmRole
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class ApiHttpRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

internal sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(
        val error: GenerationError,
        val httpStatus: Int? = null,
    ) : ApiResult<Nothing>
}

internal sealed interface ApiStreamEvent {
    data class Delta(val text: String = "", val thinking: String = "") : ApiStreamEvent
    data object Done : ApiStreamEvent
    data class Error(val error: GenerationError) : ApiStreamEvent
    data object Ignore : ApiStreamEvent
}

internal interface ApiProtocol {
    fun buildListModelsRequest(connection: ApiConnection): ApiHttpRequest
    fun parseModelIds(body: String): List<String>
    fun buildGenerateRequest(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String? = null,
        maxOutputTokens: Int? = null,
        responseMimeType: String? = null,
    ): ApiHttpRequest
    fun parseGeneratedText(body: String): String?
    fun buildStreamRequest(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String? = null,
        maxOutputTokens: Int? = null,
        responseMimeType: String? = null,
    ): ApiHttpRequest
    fun parseStreamEvent(eventType: String?, data: String): ApiStreamEvent
}

internal fun ApiProvider.protocol(): ApiProtocol = when (this) {
    ApiProvider.OpenAiCompatible, ApiProvider.Custom -> OpenAiCompatibleProtocol
    ApiProvider.Anthropic -> AnthropicProtocol
    ApiProvider.Gemini -> GeminiApiProtocol
}

internal object OpenAiCompatibleProtocol : ApiProtocol {
    override fun buildListModelsRequest(connection: ApiConnection): ApiHttpRequest = ApiHttpRequest(
        url = "${normalizedBase(connection)}/models",
        headers = authHeaders(connection),
    )

    override fun parseModelIds(body: String): List<String> = parseDataModelIds(body)

    override fun buildGenerateRequest(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String?,
        maxOutputTokens: Int?,
        responseMimeType: String?,
    ): ApiHttpRequest = buildChatRequest(
        connection = connection,
        modelId = modelId,
        messages = messages,
        systemInstruction = systemInstruction,
        maxOutputTokens = maxOutputTokens,
        responseMimeType = responseMimeType,
        stream = false,
    )

    override fun buildStreamRequest(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String?,
        maxOutputTokens: Int?,
        responseMimeType: String?,
    ): ApiHttpRequest = buildChatRequest(
        connection = connection,
        modelId = modelId,
        messages = messages,
        systemInstruction = systemInstruction,
        maxOutputTokens = maxOutputTokens,
        responseMimeType = responseMimeType,
        stream = true,
    )

    private fun buildChatRequest(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String?,
        maxOutputTokens: Int?,
        responseMimeType: String?,
        stream: Boolean,
    ): ApiHttpRequest {
        val jsonMessages = JSONArray()
        systemInstruction?.takeIf { it.isNotBlank() }?.let {
            jsonMessages.put(JSONObject().put("role", "system").put("content", it))
        }
        messages.forEach { message ->
            val role = when (message.role) {
                LlmRole.System -> "system"
                LlmRole.Model -> "assistant"
                LlmRole.User, LlmRole.Tool -> "user"
            }
            jsonMessages.put(JSONObject().put("role", role).put("content", message.content))
        }
        val body = JSONObject()
            .put("model", modelId)
            .put("temperature", 0.0)
            .put("messages", jsonMessages)
        if (stream) {
            body.put("stream", true)
        }
        maxOutputTokens?.let { body.put("max_tokens", it) }
        if (responseMimeType == "application/json") {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        return ApiHttpRequest(
            url = "${normalizedBase(connection)}/chat/completions",
            headers = authHeaders(connection),
            body = body.toString(),
        )
    }

    override fun parseGeneratedText(body: String): String? {
        val root = parseJsonObject(body) ?: return null
        val content = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.opt("content")
            ?: return null
        return when (content) {
            is String -> content
            is JSONArray -> joinTypedTextParts(content)
            else -> null
        }
    }

    override fun parseStreamEvent(eventType: String?, data: String): ApiStreamEvent {
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return ApiStreamEvent.Ignore
        if (trimmed == "[DONE]") return ApiStreamEvent.Done
        val root = parseJsonObject(trimmed) ?: return ApiStreamEvent.Ignore
        if (root.has("error") && !root.isNull("error")) {
            val errObj = root.optJSONObject("error")
            val error = if (errObj != null) {
                val code = errObj.opt("code")?.toString()?.lowercase()
                val type = errObj.optString("type").lowercase()
                val message = (errObj.opt("message") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                if (code == "429" || code == "rate_limit_exceeded" || type == "rate_limit_error" || type == "insufficient_quota") {
                    GenerationError.QuotaExceeded
                } else {
                    GenerationError.Failed(message)
                }
            } else {
                val message = (root.opt("error") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                GenerationError.Failed(message)
            }
            return ApiStreamEvent.Error(error)
        }
        val delta = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("delta")
            ?: return ApiStreamEvent.Ignore
        val text = (delta.opt("content") as? String).orEmpty()
        val thinking = ((delta.opt("reasoning_content") as? String)
            ?: (delta.opt("reasoning") as? String)).orEmpty()
        return if (text.isEmpty() && thinking.isEmpty()) {
            ApiStreamEvent.Ignore
        } else {
            ApiStreamEvent.Delta(text = text, thinking = thinking)
        }
    }

    private fun authHeaders(connection: ApiConnection): Map<String, String> =
        connection.apiKey.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { mapOf("Authorization" to "Bearer $it") }
            ?: emptyMap()
}

internal object AnthropicProtocol : ApiProtocol {
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val MAX_TOKENS = 2048

    override fun buildListModelsRequest(connection: ApiConnection): ApiHttpRequest = ApiHttpRequest(
        url = "${v1Base(connection)}/models",
        headers = authHeaders(connection),
    )

    override fun parseModelIds(body: String): List<String> = parseDataModelIds(body)

    override fun buildGenerateRequest(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String?,
        maxOutputTokens: Int?,
        responseMimeType: String?,
    ): ApiHttpRequest = buildMessagesRequest(
        connection = connection,
        modelId = modelId,
        messages = messages,
        systemInstruction = systemInstruction,
        maxOutputTokens = maxOutputTokens,
        stream = false,
    )

    override fun buildStreamRequest(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String?,
        maxOutputTokens: Int?,
        responseMimeType: String?,
    ): ApiHttpRequest = buildMessagesRequest(
        connection = connection,
        modelId = modelId,
        messages = messages,
        systemInstruction = systemInstruction,
        maxOutputTokens = maxOutputTokens,
        stream = true,
    )

    private fun buildMessagesRequest(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String?,
        maxOutputTokens: Int?,
        stream: Boolean,
    ): ApiHttpRequest {
        val systemParts = buildList {
            systemInstruction?.takeIf { it.isNotBlank() }?.let(::add)
            messages.filter { it.role == LlmRole.System && it.content.isNotBlank() }
                .forEach { add(it.content) }
        }
        val jsonMessages = JSONArray()
        messages.filter { it.role != LlmRole.System }.forEach { message ->
            val role = if (message.role == LlmRole.Model) "assistant" else "user"
            jsonMessages.put(JSONObject().put("role", role).put("content", message.content))
        }
        val body = JSONObject()
            .put("model", modelId)
            .put("max_tokens", maxOutputTokens ?: MAX_TOKENS)
            .put("temperature", 0.0)
            .put("messages", jsonMessages)
        if (stream) {
            body.put("stream", true)
        }
        if (systemParts.isNotEmpty()) {
            body.put("system", systemParts.joinToString("\n\n"))
        }
        return ApiHttpRequest(
            url = "${v1Base(connection)}/messages",
            headers = authHeaders(connection),
            body = body.toString(),
        )
    }

    override fun parseGeneratedText(body: String): String? {
        val parts = parseJsonObject(body)?.optJSONArray("content") ?: return null
        return joinTypedTextParts(parts)
    }

    override fun parseStreamEvent(eventType: String?, data: String): ApiStreamEvent {
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return ApiStreamEvent.Ignore
        val root = parseJsonObject(trimmed)
        val type = root?.optString("type")?.takeIf { it.isNotEmpty() } ?: eventType
        if (eventType == "message_stop" || type == "message_stop") {
            return ApiStreamEvent.Done
        }
        if (eventType == "error" || type == "error") {
            val errObj = root?.optJSONObject("error")
            val errType = errObj?.optString("type")?.lowercase()
            val message = (errObj?.opt("message") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: (root?.opt("message") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val error = if (errType == "rate_limit_error") {
                GenerationError.QuotaExceeded
            } else {
                GenerationError.Failed(message)
            }
            return ApiStreamEvent.Error(error)
        }
        if (type == "content_block_delta") {
            val delta = root?.optJSONObject("delta") ?: return ApiStreamEvent.Ignore
            return when (delta.optString("type")) {
                "text_delta" -> (delta.opt("text") as? String)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ApiStreamEvent.Delta(text = it) }
                    ?: ApiStreamEvent.Ignore
                "thinking_delta" -> (delta.opt("thinking") as? String)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ApiStreamEvent.Delta(thinking = it) }
                    ?: ApiStreamEvent.Ignore
                else -> ApiStreamEvent.Ignore
            }
        }
        return ApiStreamEvent.Ignore
    }

    private fun v1Base(connection: ApiConnection): String {
        val base = normalizedBase(connection)
        return if (base.endsWith("/v1")) base else "$base/v1"
    }

    private fun authHeaders(connection: ApiConnection): Map<String, String> = buildMap {
        put("anthropic-version", ANTHROPIC_VERSION)
        val key = connection.apiKey.trim()
        if (key.isNotEmpty()) put("x-api-key", key)
    }
}

internal object GeminiApiProtocol : ApiProtocol {
    override fun buildListModelsRequest(connection: ApiConnection): ApiHttpRequest = ApiHttpRequest(
        url = "${v1BetaBase(connection)}/models",
        headers = authHeaders(connection),
    )

    override fun parseModelIds(body: String): List<String> {
        val models = parseJsonObject(body)?.optJSONArray("models") ?: return emptyList()
        return buildList {
            for (index in 0 until models.length()) {
                val entry = models.optJSONObject(index) ?: continue
                val methods = entry.optJSONArray("supportedGenerationMethods")
                if (methods != null && !containsString(methods, "generateContent")) continue
                val rawName = (entry.opt("name") as? String)?.trim().orEmpty()
                val id = rawName.removePrefix("models/").trim()
                if (id.isNotEmpty()) add(id)
            }
        }.distinct()
    }

    override fun buildGenerateRequest(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String?,
        maxOutputTokens: Int?,
        responseMimeType: String?,
    ): ApiHttpRequest = buildContentRequest(
        connection = connection,
        modelId = modelId,
        messages = messages,
        systemInstruction = systemInstruction,
        maxOutputTokens = maxOutputTokens,
        responseMimeType = responseMimeType,
        action = "generateContent",
    )

    override fun buildStreamRequest(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String?,
        maxOutputTokens: Int?,
        responseMimeType: String?,
    ): ApiHttpRequest = buildContentRequest(
        connection = connection,
        modelId = modelId,
        messages = messages,
        systemInstruction = systemInstruction,
        maxOutputTokens = maxOutputTokens,
        responseMimeType = responseMimeType,
        action = "streamGenerateContent?alt=sse",
    )

    private fun buildContentRequest(
        connection: ApiConnection,
        modelId: String,
        messages: List<LlmMessage>,
        systemInstruction: String?,
        maxOutputTokens: Int?,
        responseMimeType: String?,
        action: String,
    ): ApiHttpRequest {
        val systemParts = buildList {
            systemInstruction?.takeIf { it.isNotBlank() }?.let(::add)
            messages.filter { it.role == LlmRole.System && it.content.isNotBlank() }
                .forEach { add(it.content) }
        }
        val contents = JSONArray()
        messages.filter { it.role != LlmRole.System }.forEach { message ->
            val role = if (message.role == LlmRole.Model) "model" else "user"
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", message.content))),
            )
        }
        val generationConfig = JSONObject().put("temperature", 0.0)
        maxOutputTokens?.let { generationConfig.put("maxOutputTokens", it) }
        responseMimeType?.takeIf { it.isNotBlank() }?.let { generationConfig.put("responseMimeType", it) }

        val body = JSONObject()
            .put("contents", contents)
            .put("generationConfig", generationConfig)
        if (systemParts.isNotEmpty()) {
            body.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemParts.joinToString("\n\n"))),
                ),
            )
        }
        return ApiHttpRequest(
            url = "${v1BetaBase(connection)}/models/${modelId.removePrefix("models/")}:$action",
            headers = authHeaders(connection),
            body = body.toString(),
        )
    }

    override fun parseGeneratedText(body: String): String? {
        val parts = parseJsonObject(body)
            ?.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null
        return buildString {
            for (index in 0 until parts.length()) {
                val text = parts.optJSONObject(index)?.opt("text") as? String ?: continue
                append(text)
            }
        }.takeIf { it.isNotEmpty() }
    }

    override fun parseStreamEvent(eventType: String?, data: String): ApiStreamEvent {
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return ApiStreamEvent.Ignore
        val root = parseJsonObject(trimmed) ?: return ApiStreamEvent.Ignore
        if (root.has("error") && !root.isNull("error")) {
            val errObj = root.optJSONObject("error")
            val code = errObj?.optInt("code", -1)
            val status = errObj?.optString("status")?.uppercase()
            val message = (errObj?.opt("message") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: (root.opt("error") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val error = if (code == 429 || status == "RESOURCE_EXHAUSTED") {
                GenerationError.QuotaExceeded
            } else {
                GenerationError.Failed(message)
            }
            return ApiStreamEvent.Error(error)
        }
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return ApiStreamEvent.Ignore
        val textBuilder = StringBuilder()
        val thinkingBuilder = StringBuilder()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val text = part.opt("text") as? String ?: continue
            if (part.optBoolean("thought", false)) {
                thinkingBuilder.append(text)
            } else {
                textBuilder.append(text)
            }
        }
        val text = textBuilder.toString()
        val thinking = thinkingBuilder.toString()
        return if (text.isEmpty() && thinking.isEmpty()) {
            ApiStreamEvent.Ignore
        } else {
            ApiStreamEvent.Delta(text = text, thinking = thinking)
        }
    }

    private fun v1BetaBase(connection: ApiConnection): String {
        val base = normalizedBase(connection)
        return if (base.endsWith("/v1beta")) base else "$base/v1beta"
    }

    private fun authHeaders(connection: ApiConnection): Map<String, String> =
        connection.apiKey.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { mapOf("x-goog-api-key" to it) }
            ?: emptyMap()
}

private fun normalizedBase(connection: ApiConnection): String =
    connection.baseUrl.trim().trimEnd('/')

private fun parseJsonObject(body: String): JSONObject? = try {
    JSONObject(body)
} catch (_: JSONException) {
    null
}

private fun parseDataModelIds(body: String): List<String> {
    val data = parseJsonObject(body)?.optJSONArray("data") ?: return emptyList()
    return buildList {
        for (index in 0 until data.length()) {
            val id = (data.optJSONObject(index)?.opt("id") as? String)?.trim().orEmpty()
            if (id.isNotEmpty()) add(id)
        }
    }.distinct()
}

private fun joinTypedTextParts(array: JSONArray): String? = buildString {
    for (index in 0 until array.length()) {
        val part = array.optJSONObject(index) ?: continue
        if (part.optString("type") != "text") continue
        val text = part.opt("text") as? String ?: continue
        append(text)
    }
}.takeIf { it.isNotEmpty() }

private fun containsString(array: JSONArray, target: String): Boolean {
    for (index in 0 until array.length()) {
        if ((array.opt(index) as? String) == target) return true
    }
    return false
}
