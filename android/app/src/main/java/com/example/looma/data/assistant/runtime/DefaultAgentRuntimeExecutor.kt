package com.example.looma.data.assistant.runtime

import com.example.looma.data.assistant.AgentStep
import com.example.looma.data.assistant.GenerationMeta
import com.example.looma.data.assistant.GenerationOutcome
import com.example.looma.data.assistant.sessions.ContextCompactor
import com.example.looma.data.assistant.sessions.DefaultLlmSessionManager
import com.example.looma.data.assistant.sessions.LlmSessionManager
import com.example.looma.data.assistant.tools.ToolDispatcher
import com.example.looma.data.assistant.tools.ToolsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

internal class DefaultAgentRuntimeExecutor(
    private val sessionManager: LlmSessionManager = DefaultLlmSessionManager(),
    private val toolDispatcher: ToolDispatcher = ToolDispatcher(),
    private val contextCompactor: ContextCompactor? = null,
) : AgentRuntimeExecutor {
    private val activeJob = AtomicReference<Job?>(null)
    private val activeBackend = AtomicReference<LlmRuntimeBackend?>(null)

    override suspend fun execute(
        backend: LlmRuntimeBackend,
        request: AgentRequest,
        tools: ToolsProvider?,
        onStep: (AgentStep) -> Unit,
    ): GenerationOutcome<AgentResponse> = coroutineScope {
        val job = currentCoroutineContext()[Job]
        activeJob.getAndSet(job)?.cancel()
        activeBackend.set(backend)
        try {
            val systemInstruction = buildSystemInstruction(request.systemInstruction, tools)
            sessionManager.createSession(backend, systemInstruction)

            val steps = mutableListOf<AgentStep>()
            val maxSteps = request.maxSteps.coerceAtLeast(1)
            var currentPrompt = request.question

            for (stepIndex in 0 until maxSteps) {
                contextCompactor?.compactIfNeeded(sessionManager, backend)
                val stepLabel = if (stepIndex == 0) "generate" else steps.lastOrNull()?.label ?: "tool"
                if (stepIndex == 0) {
                    val initialStep = AgentStep(label = stepLabel, origin = backend.origin)
                    steps.add(initialStep)
                    onStep(initialStep)
                }

                when (
                    val outcome = sessionManager.generateResponse(
                        prompt = currentPrompt,
                        route = request.route,
                        responseMimeType = request.responseMimeType,
                        maxOutputTokens = request.maxOutputTokens,
                    )
                ) {
                    is GenerationOutcome.Failure -> return@coroutineScope outcome
                    is GenerationOutcome.Success -> {
                        val cleaned = filterControlTokens(outcome.value)
                        val toolCall = if (tools != null && stepIndex < maxSteps - 1) {
                            toolDispatcher.extractToolCall(cleaned)
                        } else {
                            null
                        }
                        if (toolCall == null) {
                            return@coroutineScope GenerationOutcome.Success(
                                value = AgentResponse(text = cleaned, steps = steps, meta = outcome.meta),
                                meta = outcome.meta,
                            )
                        }
                        val toolStep = AgentStep(label = toolCall.name, origin = outcome.meta.origin)
                        steps.add(toolStep)
                        onStep(toolStep)
                        val result = toolDispatcher.dispatch(tools!!, toolCall.name, toolCall.argumentsJson)
                        currentPrompt = formatToolResult(toolCall.name, result.output, result.isError)
                    }
                }
            }
            error("Unreachable")
        } finally {
            activeJob.compareAndSet(job, null)
        }
    }

    override fun executeStream(
        backend: LlmRuntimeBackend,
        request: AgentRequest,
        tools: ToolsProvider?,
    ): Flow<AgentEvent> = channelFlow {
        activeBackend.set(backend)
        send(AgentEvent.LoopInitiated)
        val runJob = launch {
            val systemInstruction = buildSystemInstruction(request.systemInstruction, tools)
            sessionManager.createSession(backend, systemInstruction)

            val steps = mutableListOf<AgentStep>()
            val maxSteps = request.maxSteps.coerceAtLeast(1)
            var currentPrompt = request.question

            for (stepIndex in 0 until maxSteps) {
                contextCompactor?.compactIfNeeded(sessionManager, backend)
                val stepLabel = if (stepIndex == 0) "generate" else steps.lastOrNull()?.label ?: "tool"
                if (stepIndex == 0) {
                    steps.add(AgentStep(label = stepLabel, origin = backend.origin))
                }
                send(AgentEvent.StepStarted(label = stepLabel, origin = backend.origin))

                val textBuilder = StringBuilder()
                var lastMeta = GenerationMeta(origin = backend.origin)
                var failed = false

                sessionManager.generateStream(
                    prompt = currentPrompt,
                    route = request.route,
                    responseMimeType = request.responseMimeType,
                    maxOutputTokens = request.maxOutputTokens,
                ).collect { outcome ->
                    when (outcome) {
                        is GenerationOutcome.Failure -> {
                            failed = true
                            send(AgentEvent.Error(outcome.error))
                        }
                        is GenerationOutcome.Success -> {
                            lastMeta = outcome.meta
                            val token = filterControlTokens(outcome.value.text)
                            val thinking = filterControlTokens(outcome.value.thinking)
                            textBuilder.append(token)
                            if (token.isNotEmpty() || thinking.isNotEmpty()) {
                                send(AgentEvent.StreamToken(token = token, thinking = thinking))
                            }
                        }
                    }
                }
                if (failed) return@launch

                val cleaned = textBuilder.toString()
                val toolCall = if (tools != null && stepIndex < maxSteps - 1) {
                    toolDispatcher.extractToolCall(cleaned)
                } else {
                    null
                }
                if (toolCall == null) {
                    send(
                        AgentEvent.LoopTerminated(
                            AgentResponse(text = cleaned, steps = steps, meta = lastMeta)
                        )
                    )
                    return@launch
                }
                steps.add(AgentStep(label = toolCall.name, origin = backend.origin))
                val result = toolDispatcher.dispatch(tools!!, toolCall.name, toolCall.argumentsJson)
                currentPrompt = formatToolResult(toolCall.name, result.output, result.isError)
            }
        }
        activeJob.getAndSet(runJob)?.cancel()
        try {
            runJob.join()
            if (runJob.isCancelled && isActive) {
                send(AgentEvent.LoopCancelled)
            }
        } catch (e: CancellationException) {
            runJob.cancel(e)
            throw e
        } finally {
            activeJob.compareAndSet(runJob, null)
        }
    }

    override suspend fun interrupt() {
        activeJob.getAndSet(null)?.cancel()
        activeBackend.get()?.stop()
        sessionManager.stopResponse()
    }

    override suspend fun resetSession() {
        interrupt()
        sessionManager.resetSession()
    }

    override suspend fun cleanUp() {
        resetSession()
        activeBackend.set(null)
    }

    private fun buildSystemInstruction(base: String?, tools: ToolsProvider?): String? {
        val specs = tools?.specs().orEmpty()
        if (specs.isEmpty()) return base
        val toolsBlock = buildString {
            appendLine("Available tools (call one by returning JSON {\"tool\":\"<name>\",\"arguments\":{...}}):")
            specs.forEach { spec ->
                append("- ").append(spec.name).append(": ").append(spec.description)
                if (spec.parametersSchemaJson.isNotBlank() && spec.parametersSchemaJson != "{}") {
                    append(" | schema: ").append(spec.parametersSchemaJson)
                }
                appendLine()
            }
        }.trim()
        return if (base.isNullOrBlank()) toolsBlock else "$base\n\n$toolsBlock"
    }

    private fun formatToolResult(name: String, output: String, isError: Boolean): String =
        if (isError) "Tool '$name' error:\n$output" else "Tool '$name' result:\n$output"

    private fun filterControlTokens(text: String): String =
        text.replace(CTRL_TOKEN_REGEX, "")

    private companion object {
        val CTRL_TOKEN_REGEX = Regex("<ctrl[^>]*>?")
    }
}
