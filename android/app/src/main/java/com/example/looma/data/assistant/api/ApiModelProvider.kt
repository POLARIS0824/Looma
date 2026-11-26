package com.example.looma.data.assistant.api

import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.ConnectionStatus
import com.example.looma.data.assistant.GenerationError
import com.example.looma.data.assistant.GenerationMeta
import com.example.looma.data.assistant.GeneratorAvailability
import com.example.looma.data.assistant.InferenceOrigin
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.LlmBackendProvider
import com.example.looma.data.assistant.ModelSource
import com.example.looma.data.assistant.ThinkingEffort
import com.example.looma.data.assistant.apiModelId
import com.example.looma.data.assistant.runtime.LlmError
import com.example.looma.data.assistant.runtime.LlmGenerateRequest
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import com.example.looma.data.assistant.toLlmError
import com.example.looma.data.assistant.toLlmMetadata
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal class ApiRuntimeBackend(
    private val connection: ApiConnection,
    override val modelId: String,
    override val sessionKey: String,
    private val client: KtorApiClient,
) : LlmRuntimeBackend {
    override val contextBudgetChars: Int = 24_000
    override val maxContextTokens: Int = 32_768
    override val supportsStatefulSession: Boolean = false
    private val activeJob = AtomicReference<Job?>(null)

    override suspend fun generate(request: LlmGenerateRequest): LlmResult<String> =
        when (
            val result = client.generate(
                connection = connection,
                modelId = modelId,
                messages = request.messages,
                systemInstruction = request.systemInstruction,
                maxOutputTokens = request.maxOutputTokens,
                responseMimeType = request.responseMimeType,
            )
        ) {
            is ApiResult.Success -> LlmResult.Success(
                value = result.value,
                metadata = GenerationMeta(InferenceOrigin.Cloud).toLlmMetadata(),
            )
            is ApiResult.Failure -> toLlmFailure(result)
        }

    override fun generateStream(request: LlmGenerateRequest): Flow<LlmResult<LlmStreamChunk>> = flow {
        val job = currentCoroutineContext()[Job]
        activeJob.set(job)
        try {
            val metadata = GenerationMeta(InferenceOrigin.Cloud).toLlmMetadata()
            client.generateStream(
                connection = connection,
                modelId = modelId,
                messages = request.messages,
                systemInstruction = request.systemInstruction,
                maxOutputTokens = request.maxOutputTokens,
                responseMimeType = request.responseMimeType,
            ).collect { result ->
                when (result) {
                    is ApiResult.Success -> emit(LlmResult.Success(result.value, metadata))
                    is ApiResult.Failure -> emit(toLlmFailure(result))
                }
            }
        } finally {
            activeJob.compareAndSet(job, null)
        }
    }

    override suspend fun stop() {
        activeJob.getAndSet(null)?.cancel()
    }

    private fun toLlmFailure(failure: ApiResult.Failure): LlmResult.Failure =
        if (failure.httpStatus == 401 || failure.httpStatus == 403) {
            LlmResult.Failure(
                LlmError(LlmError.AUTH_FAILED, (failure.error as? GenerationError.Failed)?.message)
            )
        } else {
            LlmResult.Failure(failure.error.toLlmError())
        }
}

internal class ApiModelProvider(
    private val connections: StateFlow<List<ApiConnection>>,
    private val client: KtorApiClient,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : LlmBackendProvider {
    override val models: StateFlow<List<AnswerModel>> =
        connections
            .map(::toAnswerModels)
            .stateIn(scope, SharingStarted.Eagerly, toAnswerModels(connections.value))

    override fun backend(model: AnswerModel, route: InferenceRoute?): LlmRuntimeBackend? {
        for (connection in connections.value) {
            if (connection.baseUrl.isBlank()) continue
            val match = connection.models.firstOrNull { apiModel ->
                apiModel.enabled && apiModelId(connection.id, apiModel.id) == model.id
            } ?: continue
            return ApiRuntimeBackend(connection, match.id, model.id, client)
        }
        return null
    }

    private fun toAnswerModels(connections: List<ApiConnection>): List<AnswerModel> =
        connections.flatMap { connection ->
            val availability = if (connection.status == ConnectionStatus.Failed) {
                GeneratorAvailability.Unavailable()
            } else {
                GeneratorAvailability.Ready
            }
            connection.models
                .filter { it.enabled }
                .map { apiModel ->
                    AnswerModel(
                        id = apiModelId(connection.id, apiModel.id),
                        source = ModelSource.Api,
                        efforts = ThinkingEffort.entries,
                        defaultEffort = ThinkingEffort.Medium,
                        availability = availability,
                        supportedRoutes = listOf(InferenceRoute.CloudOnly),
                        defaultRoute = InferenceRoute.CloudOnly,
                        displayName = apiModel.id,
                        description = connection.name,
                    )
                }
        }
}
