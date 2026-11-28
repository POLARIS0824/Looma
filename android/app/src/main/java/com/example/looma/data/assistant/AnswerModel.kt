package com.example.looma.data.assistant

import com.example.looma.data.assistant.runtime.LlmError
import com.example.looma.data.assistant.runtime.LlmMetadata
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

internal enum class ThinkingEffort(val investigationSteps: Int) { Off(0), Low(1), Medium(2), High(3) }

internal enum class ModelSource { Hybrid, Local, Api }

internal enum class InferenceRoute { PreferOnDevice, OnDeviceOnly, PreferCloud, CloudOnly }

internal enum class InferenceOrigin { OnDevice, Cloud, LocalFile }

internal sealed interface GeneratorAvailability {
    data object Ready : GeneratorAvailability
    data class Downloadable(val bytes: Long? = null) : GeneratorAvailability
    data class Downloading(val progress: Float? = null) : GeneratorAvailability
    data object NeedsImport : GeneratorAvailability
    data class Unavailable(val reason: String? = null) : GeneratorAvailability
}

internal sealed interface GenerationError {
    data object Offline : GenerationError
    data object OnDeviceUnavailable : GenerationError
    data object AppCheckFailed : GenerationError
    data object QuotaExceeded : GenerationError
    data class Failed(val message: String? = null) : GenerationError
}

internal data class AnswerModel(
    val id: String,
    val source: ModelSource,
    val efforts: List<ThinkingEffort>,
    val defaultEffort: ThinkingEffort,
    val availability: GeneratorAvailability = GeneratorAvailability.Ready,
    val supportedRoutes: List<InferenceRoute> = emptyList(),
    val defaultRoute: InferenceRoute? = null,
    val displayName: String? = null,
    val description: String? = null,
)

internal data class ModelChoice(
    val model: AnswerModel,
    val effort: ThinkingEffort,
    val route: InferenceRoute? = model.defaultRoute,
)

internal data class GenerationMeta(
    val origin: InferenceOrigin,
    val fallbackUsed: Boolean = false,
    val fallbackReason: String? = null,
)

internal interface LlmBackendProvider {
    val models: StateFlow<List<AnswerModel>>
    fun refresh() {}
    fun backend(model: AnswerModel, route: InferenceRoute? = model.defaultRoute): LlmRuntimeBackend?
    suspend fun prepare(model: AnswerModel): Result<Unit> = Result.success(Unit)
}

internal class AnswerModels(
    private val providers: List<LlmBackendProvider>,
) {
    val models: Flow<List<AnswerModel>> = if (providers.isEmpty()) {
        flowOf(emptyList())
    } else {
        combine(providers.map { it.models }) { lists -> lists.flatMap { it } }
    }

    fun installed(): List<AnswerModel> = providers.flatMap { it.models.value }

    fun refresh() {
        providers.forEach { it.refresh() }
    }

    fun backend(model: AnswerModel, route: InferenceRoute? = model.defaultRoute): LlmRuntimeBackend? =
        providers.firstNotNullOfOrNull { it.backend(model, route) }

    fun backend(choice: ModelChoice): LlmRuntimeBackend? =
        backend(choice.model, choice.route)

    suspend fun prepare(model: AnswerModel): Result<Unit> {
        val owner = providers.firstOrNull { provider ->
            provider.models.value.any { it.id == model.id } || provider.backend(model) != null
        } ?: return Result.failure(IllegalArgumentException("Unknown model: ${model.id}"))
        return owner.prepare(model)
    }

    companion object {
        val LocalModel = AnswerModel("local", ModelSource.Local, ThinkingEffort.entries, ThinkingEffort.Off)
    }
}

private const val META_ORIGIN = "origin"
private const val META_FALLBACK_USED = "fallback_used"
private const val META_FALLBACK_REASON = "fallback_reason"

internal fun GenerationMeta.toLlmMetadata(): LlmMetadata = LlmMetadata(
    attributes = buildMap {
        put(
            META_ORIGIN,
            when (origin) {
                InferenceOrigin.OnDevice -> "on_device"
                InferenceOrigin.Cloud -> "cloud"
                InferenceOrigin.LocalFile -> "local_file"
            },
        )
        if (fallbackUsed) put(META_FALLBACK_USED, "true")
        fallbackReason?.let { put(META_FALLBACK_REASON, it) }
    },
)

internal fun LlmMetadata.toGenerationMeta(): GenerationMeta? {
    val origin = when (attributes[META_ORIGIN]) {
        "on_device" -> InferenceOrigin.OnDevice
        "cloud" -> InferenceOrigin.Cloud
        "local_file" -> InferenceOrigin.LocalFile
        else -> return null
    }
    return GenerationMeta(
        origin = origin,
        fallbackUsed = attributes[META_FALLBACK_USED] == "true",
        fallbackReason = attributes[META_FALLBACK_REASON],
    )
}

internal fun GenerationError.toLlmError(): LlmError = when (this) {
    GenerationError.Offline -> LlmError(LlmError.OFFLINE)
    GenerationError.QuotaExceeded -> LlmError(LlmError.QUOTA_EXCEEDED)
    GenerationError.OnDeviceUnavailable -> LlmError(LlmError.UNAVAILABLE)
    GenerationError.AppCheckFailed -> LlmError(LlmError.APP_CHECK_FAILED)
    is GenerationError.Failed -> LlmError(LlmError.FAILED, message)
}

internal fun LlmError.toGenerationError(): GenerationError = when (code) {
    LlmError.OFFLINE -> GenerationError.Offline
    LlmError.QUOTA_EXCEEDED -> GenerationError.QuotaExceeded
    LlmError.UNAVAILABLE -> GenerationError.OnDeviceUnavailable
    LlmError.APP_CHECK_FAILED -> GenerationError.AppCheckFailed
    LlmError.AUTH_FAILED -> GenerationError.Failed(message ?: "Authentication failed")
    else -> GenerationError.Failed(message)
}
