package com.example.looma.data.assistant.api

import com.example.looma.data.assistant.AnswerGenerator
import com.example.looma.data.assistant.AnswerInput
import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.AnswerModelProvider
import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.CitedClaim
import com.example.looma.data.assistant.ConnectionStatus
import com.example.looma.data.assistant.GenerationMeta
import com.example.looma.data.assistant.GenerationOutcome
import com.example.looma.data.assistant.GeneratorAvailability
import com.example.looma.data.assistant.InferenceOrigin
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.JsonPromptCodec
import com.example.looma.data.assistant.ModelSource
import com.example.looma.data.assistant.PlannerAction
import com.example.looma.data.assistant.PlannerInput
import com.example.looma.data.assistant.ThinkingEffort
import com.example.looma.data.assistant.apiModelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal class ApiAnswerGenerator(
    private val connection: ApiConnection,
    private val modelId: String,
    private val client: KtorApiClient,
) : AnswerGenerator {
    override val contextBudgetChars: Int = 24_000

    override suspend fun plan(input: PlannerInput): GenerationOutcome<PlannerAction> =
        when (
            val result = client.generate(
                connection = connection,
                modelId = modelId,
                systemInstruction = JsonPromptCodec.PLANNER_INSTRUCTION,
                userPayload = JsonPromptCodec.encodePayload(input.question, input.passages, input.searched),
            )
        ) {
            is ApiResult.Success -> GenerationOutcome.Success(
                value = JsonPromptCodec.decodePlannerAction(result.value) ?: PlannerAction.Finish,
                meta = GenerationMeta(InferenceOrigin.Cloud),
            )
            is ApiResult.Failure -> GenerationOutcome.Failure(result.error)
        }

    override suspend fun answer(input: AnswerInput): GenerationOutcome<List<CitedClaim>> =
        when (
            val result = client.generate(
                connection = connection,
                modelId = modelId,
                systemInstruction = JsonPromptCodec.ANSWER_INSTRUCTION,
                userPayload = JsonPromptCodec.encodePayload(input.question, input.passages, emptySet()),
            )
        ) {
            is ApiResult.Success -> GenerationOutcome.Success(
                value = JsonPromptCodec.decodeClaims(result.value, input.passages.size),
                meta = GenerationMeta(InferenceOrigin.Cloud),
            )
            is ApiResult.Failure -> GenerationOutcome.Failure(result.error)
        }
}

internal class ApiModelProvider(
    private val connections: StateFlow<List<ApiConnection>>,
    private val client: KtorApiClient,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AnswerModelProvider {
    override val models: StateFlow<List<AnswerModel>> =
        connections
            .map(::toAnswerModels)
            .stateIn(scope, SharingStarted.Eagerly, toAnswerModels(connections.value))

    override fun generator(model: AnswerModel): AnswerGenerator? {
        for (connection in connections.value) {
            if (connection.baseUrl.isBlank()) continue
            val match = connection.models.firstOrNull { apiModel ->
                apiModel.enabled && apiModelId(connection.id, apiModel.id) == model.id
            } ?: continue
            return ApiAnswerGenerator(connection, match.id, client)
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
