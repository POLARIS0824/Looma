package com.example.looma.data.assistant.firebase

import com.example.looma.data.assistant.AnswerGenerator
import com.example.looma.data.assistant.AnswerInput
import com.example.looma.data.assistant.CitedClaim
import com.example.looma.data.assistant.GenerationError
import com.example.looma.data.assistant.GenerationMeta
import com.example.looma.data.assistant.GenerationOutcome
import com.example.looma.data.assistant.InferenceOrigin
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.JsonPromptCodec
import com.example.looma.data.assistant.PlannerAction
import com.example.looma.data.assistant.PlannerInput
import com.google.firebase.Firebase
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
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

@OptIn(PublicPreviewAPI::class)
internal class FirebaseHybridGenerator : AnswerGenerator {
    override val contextBudgetChars: Int = HybridRoutingPolicy.ON_DEVICE_CHAR_BUDGET

    override fun contextBudgetChars(route: InferenceRoute?, multiStep: Boolean): Int =
        HybridRoutingPolicy.contextBudgetFor(route, multiStep)

    override suspend fun plan(input: PlannerInput): GenerationOutcome<PlannerAction> {
        val prompt = JsonPromptCodec.encodePayload(input.question, input.passages, input.searched)
        val effectiveRoute = HybridRoutingPolicy.resolveEffectiveRoute(
            requested = input.route,
            multiStep = true,
            promptChars = prompt.length,
        )
        return try {
            val model = buildModel(JsonPromptCodec.PLANNER_INSTRUCTION, effectiveRoute)
            val response = model.generateObject(HybridPlannerAction.firebaseAISchema(), prompt)
            val action = response.getObject()?.toDomain() ?: PlannerAction.Finish
            val origin = response.response.inferenceSource.toOrigin()
            GenerationOutcome.Success(
                value = action,
                meta = GenerationMeta(
                    origin = origin,
                    fallbackUsed = isFallback(effectiveRoute, origin),
                ),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            GenerationOutcome.Failure(mapError(error))
        }
    }

    override suspend fun answer(input: AnswerInput): GenerationOutcome<List<CitedClaim>> {
        val prompt = JsonPromptCodec.encodePayload(input.question, input.passages, emptySet())
        val effectiveRoute = HybridRoutingPolicy.resolveEffectiveRoute(
            requested = input.route,
            multiStep = input.multiStep,
            promptChars = prompt.length,
        )
        return try {
            val model = buildModel(JsonPromptCodec.ANSWER_INSTRUCTION, effectiveRoute)
            val response = model.generateObject(HybridAnswerClaims.firebaseAISchema(), prompt)
            val claims = response.getObject()?.toDomain(input.passages.size).orEmpty()
            val origin = response.response.inferenceSource.toOrigin()
            GenerationOutcome.Success(
                value = claims,
                meta = GenerationMeta(
                    origin = origin,
                    fallbackUsed = isFallback(effectiveRoute, origin),
                ),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            GenerationOutcome.Failure(mapError(error))
        }
    }

    private fun buildModel(instruction: String, route: InferenceRoute) =
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = HybridRoutingPolicy.CLOUD_FALLBACK_MODEL,
            generationConfig = generationConfig {
                temperature = 0.2f
            },
            systemInstruction = content {
                text(instruction)
            },
            onDeviceConfig = OnDeviceConfig(
                mode = route.toInferenceMode(),
                temperature = 0.2f,
                maxOutputTokens = 256,
            ),
        )

    private fun InferenceRoute.toInferenceMode(): InferenceMode = when (this) {
        InferenceRoute.PreferOnDevice -> InferenceMode.PREFER_ON_DEVICE
        InferenceRoute.OnDeviceOnly -> InferenceMode.ONLY_ON_DEVICE
        InferenceRoute.PreferCloud -> InferenceMode.PREFER_IN_CLOUD
        InferenceRoute.CloudOnly -> InferenceMode.ONLY_IN_CLOUD
    }

    private fun InferenceSource?.toOrigin(): InferenceOrigin = when (this) {
        InferenceSource.ON_DEVICE -> InferenceOrigin.OnDevice
        InferenceSource.IN_CLOUD -> InferenceOrigin.Cloud
        else -> InferenceOrigin.Cloud
    }

    private fun isFallback(route: InferenceRoute, origin: InferenceOrigin): Boolean =
        (route == InferenceRoute.PreferOnDevice && origin == InferenceOrigin.Cloud) ||
            (route == InferenceRoute.PreferCloud && origin == InferenceOrigin.OnDevice)

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
