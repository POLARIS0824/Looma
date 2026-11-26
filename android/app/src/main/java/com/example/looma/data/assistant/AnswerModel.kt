package com.example.looma.data.assistant

import android.net.Uri
import com.example.looma.data.assistant.runtime.LlmBackendProvider
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import kotlinx.coroutines.flow.Flow
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

internal sealed interface GenerationOutcome<out T> {
    data class Success<T>(val value: T, val meta: GenerationMeta) : GenerationOutcome<T>
    data class Failure(val error: GenerationError) : GenerationOutcome<Nothing>
}

internal data class AgentStep(val label: String, val origin: InferenceOrigin)

internal class AnswerModels(
    private val providers: List<LlmBackendProvider>,
    private val localImporter: (suspend (Uri) -> Unit)? = null,
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

    fun backend(model: AnswerModel): LlmRuntimeBackend? =
        providers.firstNotNullOfOrNull { it.backend(model) }

    suspend fun prepare(model: AnswerModel): Result<Unit> {
        val owner = providers.firstOrNull { provider ->
            provider.models.value.any { it.id == model.id } || provider.backend(model) != null
        } ?: return Result.failure(IllegalArgumentException("Unknown model: ${model.id}"))
        return owner.prepare(model)
    }

    suspend fun importLocal(uri: Uri) {
        val importer = localImporter ?: error("Local model import not supported")
        importer(uri)
        refresh()
    }

    companion object {
        val LocalModel = AnswerModel("local", ModelSource.Local, ThinkingEffort.entries, ThinkingEffort.Off)
    }
}
