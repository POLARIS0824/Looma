package com.example.looma.data.assistant.api

import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.ConnectionStatus
import com.example.looma.data.assistant.GenerationMeta
import com.example.looma.data.assistant.GeneratorAvailability
import com.example.looma.data.assistant.InferenceOrigin
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.LlmBackendProvider
import com.example.looma.data.assistant.ModelSource
import com.example.looma.data.assistant.ThinkingEffort
import com.example.looma.data.assistant.apiModelId
import com.example.looma.data.assistant.runtime.LlmGenerateRequest
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import com.example.looma.data.assistant.toLlmError
import com.example.looma.data.assistant.toLlmMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            is ApiResult.Failure -> LlmResult.Failure(result.error.toLlmError())
        }

    override fun generateStream(request: LlmGenerateRequest): Flow<LlmResult<LlmStreamChunk>> = flow {
        when (val outcome = generate(request)) {
            is LlmResult.Success -> emit(
                LlmResult.Success(
                    value = LlmStreamChunk(text = outcome.value, done = true),
                    metadata = outcome.metadata,
                ),
            )
            is LlmResult.Failure -> emit(outcome)
        }
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
