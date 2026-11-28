package com.example.looma.data.assistant.runtime

import com.example.looma.data.assistant.sessions.CompactionResult
import com.example.looma.data.assistant.sessions.ContextCompactor
import com.example.looma.data.assistant.sessions.DefaultLlmSessionManager
import com.example.looma.data.assistant.sessions.LlmSessionManager
import com.example.looma.data.assistant.sessions.SummarizationContextCompactor
import com.example.looma.data.assistant.tools.ToolDispatcher
import com.example.looma.data.assistant.tools.ToolsProvider
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class DefaultAgentRuntimeExecutor(
    override val sessionManager: LlmSessionManager = DefaultLlmSessionManager(),
    private val toolDispatcher: ToolDispatcher = ToolDispatcher(),
    private val contextCompactor: ContextCompactor? = SummarizationContextCompactor(),
) : AgentRuntimeExecutor {
    private val activeJob = AtomicReference<Job?>(null)
    private val activeBackend = AtomicReference<LlmRuntimeBackend?>(null)

    override suspend fun execute(
        backend: LlmRuntimeBackend,
        request: AgentRequest,
        tools: ToolsProvider?,
        onStep: (AgentStep) -> Unit,
    ): LlmResult<AgentResponse> = coroutineScope {
        val job = currentCoroutineContext()[Job]
        activeJob.getAndSet(job)?.cancel()
        activeBackend.set(backend)
        try {
            val systemInstruction = buildSystemInstruction(request.systemInstruction, tools)
            sessionManager.ensureSession(backend, systemInstruction)

            val steps = mutableListOf<AgentStep>()
            val maxSteps = request.maxSteps.coerceAtLeast(1)
            var currentPrompt = request.question

            for (stepIndex in 0 until maxSteps) {
                contextCompactor?.compactIfNeeded(sessionManager, backend)
                val stepLabel = if (stepIndex == 0) "generate" else steps.lastOrNull()?.label ?: "tool"
                if (stepIndex == 0) {
                    val initialStep = AgentStep(label = stepLabel)
                    steps.add(initialStep)
                    onStep(initialStep)
                }

                when (
                    val outcome = sessionManager.generateResponse(
                        prompt = currentPrompt,
                        responseMimeType = request.responseMimeType,
                        maxOutputTokens = request.maxOutputTokens,
                    )
                ) {
                    is LlmResult.Failure -> return@coroutineScope outcome
                    is LlmResult.Success -> {
                        val cleaned = outcome.value
                        val toolCall = if (tools != null) {
                            toolDispatcher.extractToolCall(cleaned)
                        } else {
                            null
                        }
                        if (toolCall == null) {
                            return@coroutineScope LlmResult.Success(
                                value = AgentResponse(text = cleaned, steps = steps, metadata = outcome.metadata),
                                metadata = outcome.metadata,
                            )
                        }
                        if (stepIndex == maxSteps - 1) {
                            break
                        }
                        val toolStep = AgentStep(label = toolCall.name, metadata = outcome.metadata)
                        steps.add(toolStep)
                        onStep(toolStep)
                        val result = toolDispatcher.dispatch(tools!!, toolCall.name, toolCall.argumentsJson)
                        currentPrompt = formatToolResult(toolCall.name, result.output, result.isError)
                    }
                }
            }
            LlmResult.Failure(LlmError(LlmError.FAILED, "Max agent steps reached"))
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
            sessionManager.ensureSession(backend, systemInstruction)

            val steps = mutableListOf<AgentStep>()
            val maxSteps = request.maxSteps.coerceAtLeast(1)
            var currentPrompt = request.question

            for (stepIndex in 0 until maxSteps) {
                val compaction = contextCompactor?.compactIfNeeded(sessionManager, backend)
                if (compaction != null && compaction.compacted) {
                    send(AgentEvent.ContextCompacted(compaction))
                }
                val stepLabel = if (stepIndex == 0) "generate" else steps.lastOrNull()?.label ?: "tool"
                if (stepIndex == 0) {
                    steps.add(AgentStep(label = stepLabel))
                }
                send(AgentEvent.StepStarted(label = stepLabel))

                val textBuilder = StringBuilder()
                val pendingBuffer = StringBuilder()
                var bufferMode = if (tools != null) StreamBufferMode.UNDECIDED else StreamBufferMode.PASSTHROUGH
                var lastMetadata = LlmMetadata()
                var failed = false

                sessionManager.generateStream(
                    prompt = currentPrompt,
                    responseMimeType = request.responseMimeType,
                    maxOutputTokens = request.maxOutputTokens,
                ).collect { outcome ->
                    when (outcome) {
                        is LlmResult.Failure -> {
                            failed = true
                            send(AgentEvent.Error(outcome.error))
                        }
                        is LlmResult.Success -> {
                            lastMetadata = outcome.metadata
                            val token = outcome.value.text
                            val thinking = outcome.value.thinking
                            textBuilder.append(token)
                            when (bufferMode) {
                                StreamBufferMode.PASSTHROUGH -> {
                                    if (token.isNotEmpty() || thinking.isNotEmpty()) {
                                        send(AgentEvent.StreamToken(token = token, thinking = thinking))
                                    }
                                }
                                StreamBufferMode.BUFFERING -> {
                                    pendingBuffer.append(token)
                                    if (thinking.isNotEmpty()) {
                                        send(AgentEvent.StreamToken(token = "", thinking = thinking))
                                    }
                                }
                                StreamBufferMode.UNDECIDED -> {
                                    pendingBuffer.append(token)
                                    val firstNonWs = pendingBuffer.firstOrNull { !it.isWhitespace() }
                                    when {
                                        firstNonWs == null -> {
                                            if (thinking.isNotEmpty()) {
                                                send(AgentEvent.StreamToken(token = "", thinking = thinking))
                                            }
                                        }
                                        firstNonWs == '{' || firstNonWs == '`' -> {
                                            bufferMode = StreamBufferMode.BUFFERING
                                            if (thinking.isNotEmpty()) {
                                                send(AgentEvent.StreamToken(token = "", thinking = thinking))
                                            }
                                        }
                                        else -> {
                                            bufferMode = StreamBufferMode.PASSTHROUGH
                                            val flushed = pendingBuffer.toString()
                                            pendingBuffer.clear()
                                            if (flushed.isNotEmpty() || thinking.isNotEmpty()) {
                                                send(AgentEvent.StreamToken(token = flushed, thinking = thinking))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (failed) return@launch

                val cleaned = textBuilder.toString()
                val toolCall = if (tools != null) {
                    toolDispatcher.extractToolCall(cleaned)
                } else {
                    null
                }
                if (toolCall == null) {
                    if (pendingBuffer.isNotEmpty()) {
                        send(AgentEvent.StreamToken(token = pendingBuffer.toString()))
                    }
                    send(
                        AgentEvent.LoopTerminated(
                            AgentResponse(text = cleaned, steps = steps, metadata = lastMetadata)
                        )
                    )
                    return@launch
                }
                if (stepIndex == maxSteps - 1) {
                    break
                }
                steps.add(AgentStep(label = toolCall.name, metadata = lastMetadata))
                send(AgentEvent.ToolCallStarted(toolCall.name, toolCall.argumentsJson))
                val result = toolDispatcher.dispatch(tools!!, toolCall.name, toolCall.argumentsJson)
                send(AgentEvent.ToolCallFinished(toolCall.name, result.output, result.isError))
                currentPrompt = formatToolResult(toolCall.name, result.output, result.isError)
            }
            send(AgentEvent.Error(LlmError(LlmError.FAILED, "Max agent steps reached")))
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

    override suspend fun compactContext(): CompactionResult {
        val compactor = contextCompactor ?: return CompactionResult(compacted = false)
        return sessionManager.compactIfNeeded(compactor)
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
            appendLine("Available tools (when you need a tool, respond ONLY with JSON {\"tool\":\"<name>\",\"arguments\":{...}}):")
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

    private enum class StreamBufferMode {
        UNDECIDED,
        BUFFERING,
        PASSTHROUGH,
    }
}
