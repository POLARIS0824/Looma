package com.example.looma.data.assistant.api

import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.ApiProvider
import com.example.looma.data.assistant.GenerationError
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

internal interface ApiProtocol {
    fun buildListModelsRequest(connection: ApiConnection): ApiHttpRequest
    fun parseModelIds(body: String): List<String>
    fun buildGenerateRequest(
        connection: ApiConnection,
        modelId: String,
        systemInstruction: String,
        userPayload: String,
    ): ApiHttpRequest
    fun parseGeneratedText(body: String): String?
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
        systemInstruction: String,
        userPayload: String,
    ): ApiHttpRequest = ApiHttpRequest(
        url = "${normalizedBase(connection)}/chat/completions",
        headers = authHeaders(connection),
        body = JSONObject()
            .put("model", modelId)
            .put("temperature", 0.0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemInstruction))
                    .put(JSONObject().put("role", "user").put("content", userPayload)),
            )
            .toString(),
    )

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
        systemInstruction: String,
        userPayload: String,
    ): ApiHttpRequest = ApiHttpRequest(
        url = "${v1Base(connection)}/messages",
        headers = authHeaders(connection),
        body = JSONObject()
            .put("model", modelId)
            .put("max_tokens", MAX_TOKENS)
            .put("temperature", 0.0)
            .put("system", systemInstruction)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", userPayload)),
            )
            .toString(),
    )

    override fun parseGeneratedText(body: String): String? {
        val parts = parseJsonObject(body)?.optJSONArray("content") ?: return null
        return joinTypedTextParts(parts)
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
        systemInstruction: String,
        userPayload: String,
    ): ApiHttpRequest = ApiHttpRequest(
        url = "${v1BetaBase(connection)}/models/${modelId.removePrefix("models/")}:generateContent",
        headers = authHeaders(connection),
        body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction)),
                ),
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userPayload))),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.0)
                    .put("responseMimeType", "application/json"),
            )
            .toString(),
    )

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
