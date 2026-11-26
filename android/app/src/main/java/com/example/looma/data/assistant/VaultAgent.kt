package com.example.looma.data.assistant

import com.example.looma.data.assistant.runtime.AgentRequest
import com.example.looma.data.assistant.runtime.DefaultAgentRuntimeExecutor
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.sessions.DefaultLlmSessionManager
import com.example.looma.data.assistant.sessions.SummarizationContextCompactor
import com.example.looma.data.assistant.tools.ToolDispatcher
import com.example.looma.data.assistant.tools.VaultToolsProvider
import com.example.looma.domain.model.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class CitedClaim(val text: String, val citations: List<Int>)
internal enum class AnswerStatus { Answered, NoEvidence, NeedsModel, Insufficient, ModelFailed }
internal data class VaultAnswer(
    val sources: List<Evidence>,
    val claims: List<CitedClaim> = emptyList(),
    val status: AnswerStatus,
    val semantic: Boolean,
    val partial: Boolean,
    val meta: GenerationMeta? = null,
    val error: GenerationError? = null,
    val steps: List<AgentStep> = emptyList(),
)

internal class VaultAgent(private val evidence: VaultEvidence) {
    suspend fun answer(
        vault: Vault,
        question: String,
        backend: LlmRuntimeBackend?,
        effort: ThinkingEffort,
        route: InferenceRoute? = null,
        progress: (String) -> Unit,
    ): VaultAnswer =
        withTimeout(180_000L) {
            require(question.isNotBlank() && question.length <= 1000)
            val scope = evidence.scope(vault)
            val first = evidence.search(scope, question)
            val pool = first.hits.mapNotNull { evidence.resolve(vault, it) }.toMutableList()
            var partial = first.partial
            val initial = VaultAnswer(
                sources = pool.toList(),
                status = AnswerStatus.NoEvidence,
                semantic = first.semantic,
                partial = partial,
            )
            val investigate = effort.investigationSteps > 0
            if (backend == null) {
                return@withTimeout initial.copy(
                    status = if (pool.isEmpty()) AnswerStatus.NoEvidence else AnswerStatus.NeedsModel,
                )
            }
            if (pool.isEmpty() && !investigate) return@withTimeout initial

            val executor = DefaultAgentRuntimeExecutor(
                sessionManager = DefaultLlmSessionManager(),
                toolDispatcher = ToolDispatcher(),
                contextCompactor = SummarizationContextCompactor(),
            )
            val collectedSteps = mutableListOf<AgentStep>()
            try {
                evidence.checkVault(vault)
                val searched = mutableSetOf(question.trim().lowercase())
                val expanded = mutableSetOf<Int>()
                val tools = if (investigate) {
                    VaultToolsProvider(
                        evidence = evidence,
                        scope = scope,
                        pool = pool,
                        searched = searched,
                        expanded = expanded,
                        onPartial = { if (it) partial = true },
                        onStep = progress,
                    )
                } else {
                    null
                }
                val request = AgentRequest(
                    question = JsonPromptCodec.encodePrompt(question, pool, backend.contextBudgetChars),
                    systemInstruction = JsonPromptCodec.ANSWER_INSTRUCTION,
                    maxSteps = if (investigate) effort.investigationSteps + 1 else 1,
                    route = route,
                    responseMimeType = if (!investigate) "application/json" else null,
                )
                val outcome = executor.execute(
                    backend = backend,
                    request = request,
                    tools = tools,
                    onStep = { step ->
                        collectedSteps += step
                        if (step.label == "generate") progress("")
                    },
                )
                when (outcome) {
                    is GenerationOutcome.Failure -> {
                        evidence.checkVault(vault)
                        initial.copy(
                            sources = pool.toList(),
                            partial = partial,
                            error = outcome.error,
                            steps = collectedSteps.toList(),
                            status = AnswerStatus.ModelFailed,
                        )
                    }
                    is GenerationOutcome.Success -> {
                        evidence.checkVault(vault)
                        val response = outcome.value
                        val resolvedPool = pool.map { evidence.resolve(vault, it) }
                        val idRemap = buildMap {
                            var nextId = 1
                            resolvedPool.forEachIndexed { index, item ->
                                if (item != null) {
                                    put(index + 1, nextId++)
                                }
                            }
                        }
                        val sources = resolvedPool.filterNotNull()
                        val rawClaims = decodeOrFallback(response.text, pool.size)
                        val usable = rawClaims.mapNotNull { claim ->
                            if (claim.citations.isEmpty()) {
                                claim
                            } else {
                                val remapped = claim.citations.mapNotNull { idRemap[it] }.distinct()
                                if (remapped.isEmpty()) null else claim.copy(citations = remapped)
                            }
                        }
                        initial.copy(
                            sources = sources,
                            claims = usable,
                            partial = partial,
                            meta = response.meta,
                            steps = response.steps,
                            status = if (usable.isEmpty()) AnswerStatus.Insufficient else AnswerStatus.Answered,
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                evidence.checkVault(vault)
                initial.copy(
                    sources = pool.toList(),
                    partial = partial,
                    error = GenerationError.Failed(error.message),
                    steps = collectedSteps.toList(),
                    status = AnswerStatus.ModelFailed,
                )
            } catch (error: LinkageError) {
                initial.copy(
                    sources = pool.toList(),
                    partial = partial,
                    error = GenerationError.Failed(error.message),
                    steps = collectedSteps.toList(),
                    status = AnswerStatus.ModelFailed,
                )
            } finally {
                withContext(NonCancellable) {
                    executor.cleanUp()
                }
            }
        }

    private fun decodeOrFallback(raw: String, passageCount: Int): List<CitedClaim> {
        val parsed = JsonPromptCodec.parseObject(raw)
        if (parsed != null && parsed.has("claims")) {
            return JsonPromptCodec.decodeClaims(raw, passageCount)
        }
        val plain = JsonPromptCodec.stripThinking(raw)
        return if (plain.isNotBlank()) listOf(CitedClaim(plain, emptyList())) else emptyList()
    }
}
