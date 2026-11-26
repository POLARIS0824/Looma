package com.example.looma.data.assistant

import com.example.looma.domain.model.Vault
import com.example.looma.data.search.cappedForModel
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
        generator: AnswerGenerator?,
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
            val initial = VaultAnswer(pool.toList(), status = AnswerStatus.NoEvidence,
                semantic = first.semantic, partial = partial)
            val investigate = effort.investigationSteps > 0
            if (generator == null) return@withTimeout initial.copy(
                status = if (pool.isEmpty()) AnswerStatus.NoEvidence else AnswerStatus.NeedsModel)
            if (pool.isEmpty() && !investigate) return@withTimeout initial

            val steps = mutableListOf<AgentStep>()
            try {
                run {
                    val searched = mutableSetOf(question.trim().lowercase())
                    val expanded = mutableSetOf<Int>()
                    if (investigate) {
                        for (step in 1..effort.investigationSteps) {
                            evidence.checkVault(vault)
                            val outcome = generator.plan(
                                PlannerInput(question, passages(question, pool, generator.contextBudgetChars), searched, route)
                            )
                            when (outcome) {
                                is GenerationOutcome.Failure -> {
                                    evidence.checkVault(vault)
                                    return@withTimeout initial.copy(
                                        sources = pool.toList(),
                                        partial = partial,
                                        error = outcome.error,
                                        steps = steps.toList(),
                                        status = AnswerStatus.ModelFailed,
                                    )
                                }
                                is GenerationOutcome.Success -> when (val action = outcome.value) {
                                    is PlannerAction.Search -> {
                                        val query = action.query.trim()
                                        if (query.length !in 1..200 || !searched.add(query.lowercase())) break
                                        steps += AgentStep(query, outcome.meta.origin)
                                        progress(query)
                                        val found = evidence.search(scope, query, 4)
                                        partial = partial || found.partial
                                        pool += found.hits.filter { hit ->
                                            pool.none { it.id == hit.id && it.anchor == hit.anchor }
                                        }.take(2)
                                    }
                                    is PlannerAction.Read -> {
                                        val index = action.passageId - 1
                                        if (index !in pool.indices || !expanded.add(index)) break
                                        val path = pool[index].id.path.relativePath
                                        steps += AgentStep(path, outcome.meta.origin)
                                        progress(path)
                                        evidence.resolve(vault, pool[index], expand = true)?.let { pool[index] = it }
                                    }
                                    PlannerAction.Finish -> break
                                }
                            }
                        }
                    }
                    val verified = pool.mapNotNull { evidence.resolve(vault, it) }
                    if (verified.isEmpty()) return@run initial.copy(sources = emptyList(), partial = partial, steps = steps.toList())
                    when (val outcome = generator.answer(AnswerInput(question, passages(question, verified, generator.contextBudgetChars), route))) {
                        is GenerationOutcome.Failure -> {
                            evidence.checkVault(vault)
                            initial.copy(
                                sources = verified,
                                partial = partial,
                                error = outcome.error,
                                steps = steps.toList(),
                                status = AnswerStatus.ModelFailed,
                            )
                        }
                        is GenerationOutcome.Success -> {
                            val claims = outcome.value
                            evidence.checkVault(vault)
                            val stillCurrent = claims.flatMap { it.citations }.distinct().all {
                                evidence.resolve(vault, verified[it - 1]) != null
                            }
                            val usable = claims.takeIf { stillCurrent }.orEmpty()
                            initial.copy(
                                sources = verified,
                                claims = usable,
                                partial = partial,
                                meta = outcome.meta,
                                steps = steps.toList(),
                                status = if (usable.isEmpty()) AnswerStatus.Insufficient else AnswerStatus.Answered,
                            )
                        }
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                evidence.checkVault(vault)
                initial.copy(error = GenerationError.Failed(error.message), steps = steps.toList(), status = AnswerStatus.ModelFailed)
            } catch (error: LinkageError) {
                initial.copy(error = GenerationError.Failed(error.message), steps = steps.toList(), status = AnswerStatus.ModelFailed)
            }
        }

    private fun passages(question: String, pool: List<Evidence>, budgetChars: Int): List<PassageInput> {
        val perPassage = (budgetChars - question.length).coerceAtLeast(1800) / pool.size.coerceAtLeast(1)
        return pool.mapIndexed { index, hit ->
            PassageInput(
                id = index + 1,
                path = hit.id.path.relativePath.cappedForModel(160),
                section = hit.section.cappedForModel(80),
                text = hit.text.cappedForModel(perPassage),
            )
        }
    }
}
