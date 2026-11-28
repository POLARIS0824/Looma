package com.example.looma.data.assistant.firebase

import com.example.looma.data.assistant.GenerationError
import com.example.looma.data.assistant.GenerationMeta
import com.example.looma.data.assistant.InferenceOrigin
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.runtime.LlmGenerateRequest
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import com.example.looma.data.assistant.toLlmError
import com.example.looma.data.assistant.toLlmMetadata
import com.google.firebase.Firebase
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FirebaseAIException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

@OptIn(PublicPreviewAPI::class)
internal class FirebaseHybridGenerator(
    private val route: InferenceRoute = InferenceRoute.PreferOnDevice,
) : LlmRuntimeBackend {
    override val modelId: String = FirebaseHybridModelProvider.MODEL_ID
    override val sessionKey: String = "${FirebaseHybridModelProvider.MODEL_ID}:${route.name}"
    override val contextBudgetChars: Int = HybridRoutingPolicy.contextBudgetFor(route, multiStep = false)
    override val maxContextTokens: Int =
        if (route == InferenceRoute.CloudOnly || route == InferenceRoute.PreferCloud) 32_768 else 4_000
    override val supportsStatefulSession: Boolean = false

    override suspend fun generate(request: LlmGenerateRequest): LlmResult<String> {
        val totalChars = request.messages.sumOf { it.content.length } + request.systemInstruction.orEmpty().length
        val multiStep = request.messages.size > 1
        val effectiveRoute = HybridRoutingPolicy.resolveEffectiveRoute(
            requested = route,
            multiStep = multiStep,
            promptChars = totalChars,
        )
        return try {
            val model = buildModel(request.systemInstruction, effectiveRoute, request.maxOutputTokens)
            val contents = request.messages.toContents()
            val response = model.generateContent(contents)
            val actualOrigin = response.inferenceSource.toOrigin(defaultOriginFor(effectiveRoute))
            val (fallbackUsed, fallbackReason) = HybridRoutingPolicy.evaluateFallback(
                requested = route,
                effective = effectiveRoute,
                actualOrigin = actualOrigin,
                multiStep = multiStep,
                promptChars = totalChars,
            )
            LlmResult.Success(
                value = response.text.orEmpty(),
                metadata = GenerationMeta(
                    origin = actualOrigin,
                    fallbackUsed = fallbackUsed,
                    fallbackReason = fallbackReason,
                ).toLlmMetadata(),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            LlmResult.Failure(mapError(error).toLlmError())
        }
    }

    override fun generateStream(request: LlmGenerateRequest): Flow<LlmResult<LlmStreamChunk>> = flow<LlmResult<LlmStreamChunk>> {
        val totalChars = request.messages.sumOf { it.content.length } + request.systemInstruction.orEmpty().length
        val multiStep = request.messages.size > 1
        val effectiveRoute = HybridRoutingPolicy.resolveEffectiveRoute(
            requested = route,
            multiStep = multiStep,
            promptChars = totalChars,
        )
        val model = buildModel(request.systemInstruction, effectiveRoute, request.maxOutputTokens)
        val contents = request.messages.toContents()
        val defaultOrigin = defaultOriginFor(effectiveRoute)
        val (initialFallbackUsed, initialFallbackReason) = HybridRoutingPolicy.evaluateFallback(
            requested = route,
            effective = effectiveRoute,
            actualOrigin = defaultOrigin,
            multiStep = multiStep,
            promptChars = totalChars,
        )
        var lastMeta = GenerationMeta(
            origin = defaultOrigin,
            fallbackUsed = initialFallbackUsed,
            fallbackReason = initialFallbackReason,
        ).toLlmMetadata()
        model.generateContentStream(contents).collect { chunk ->
            val actualOrigin = chunk.inferenceSource.toOrigin(defaultOrigin)
            val (fallbackUsed, fallbackReason) = HybridRoutingPolicy.evaluateFallback(
                requested = route,
                effective = effectiveRoute,
                actualOrigin = actualOrigin,
                multiStep = multiStep,
                promptChars = totalChars,
            )
            lastMeta = GenerationMeta(
                origin = actualOrigin,
                fallbackUsed = fallbackUsed,
                fallbackReason = fallbackReason,
            ).toLlmMetadata()
            val text = chunk.text.orEmpty()
            if (text.isNotEmpty()) {
                emit(LlmResult.Success(LlmStreamChunk(text = text, done = false), lastMeta))
            }
        }
        emit(LlmResult.Success(LlmStreamChunk(done = true), lastMeta))
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(LlmResult.Failure(mapError(error).toLlmError()))
    }

    private fun buildModel(instruction: String?, route: InferenceRoute, maxOutputTokens: Int?) =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = HybridRoutingPolicy.CLOUD_FALLBACK_MODEL,
            generationConfig = generationConfig {
                temperature = 0.2f
                if (maxOutputTokens != null) {
                    this.maxOutputTokens = maxOutputTokens
                }
            },
            systemInstruction = instruction?.takeIf { it.isNotBlank() }?.let {
                content { text(it) }
            },
            onDeviceConfig = OnDeviceConfig(
                mode = route.toInferenceMode(),
                temperature = 0.2f,
                maxOutputTokens = (maxOutputTokens ?: 256).coerceAtMost(256),
            ),
        )

    private fun List<LlmMessage>.toContents(): List<Content> = map { message ->
        val role = when (message.role) {
            LlmRole.Model -> "model"
            LlmRole.System, LlmRole.User, LlmRole.Tool -> "user"
        }
        content(role = role) {
            text(message.content)
        }
    }

    private fun InferenceRoute.toInferenceMode(): InferenceMode = when (this) {
        InferenceRoute.PreferOnDevice -> InferenceMode.PREFER_ON_DEVICE
        InferenceRoute.OnDeviceOnly -> InferenceMode.ONLY_ON_DEVICE
        InferenceRoute.PreferCloud -> InferenceMode.PREFER_IN_CLOUD
        InferenceRoute.CloudOnly -> InferenceMode.ONLY_IN_CLOUD
    }

    private fun defaultOriginFor(route: InferenceRoute): InferenceOrigin = when (route) {
        InferenceRoute.PreferOnDevice, InferenceRoute.OnDeviceOnly -> InferenceOrigin.OnDevice
        InferenceRoute.PreferCloud, InferenceRoute.CloudOnly -> InferenceOrigin.Cloud
    }

    private fun mapError(error: Throwable): GenerationError {
        val message = error.message.orEmpty()
        return when {
            error is QuotaExceededException -> GenerationError.QuotaExceeded
            error is UnknownHostException || error is ConnectException -> GenerationError.Offline
            error is IOException && message.contains("network", ignoreCase = true) -> GenerationError.Offline
            message.contains("app check", ignoreCase = true) ||
                message.contains("appcheck", ignoreCase = true) -> GenerationError.AppCheckFailed
            message.contains("quota", ignoreCase = true) ||
                message.contains("resource_exhausted", ignoreCase = true) -> GenerationError.QuotaExceeded
            message.contains("offline", ignoreCase = true) ||
                message.contains("unable to resolve host", ignoreCase = true) -> GenerationError.Offline
            message.contains("on-device", ignoreCase = true) ||
                message.contains("ondevice", ignoreCase = true) ||
                error is FirebaseAIException && message.contains("unavailable", ignoreCase = true) ->
                GenerationError.OnDeviceUnavailable
            else -> GenerationError.Failed(error.message)
        }
    }
}

@OptIn(PublicPreviewAPI::class)
internal fun InferenceSource?.toOrigin(defaultOrigin: InferenceOrigin): InferenceOrigin = when (this) {
    InferenceSource.ON_DEVICE -> InferenceOrigin.OnDevice
    InferenceSource.IN_CLOUD -> InferenceOrigin.Cloud
    else -> defaultOrigin
}
