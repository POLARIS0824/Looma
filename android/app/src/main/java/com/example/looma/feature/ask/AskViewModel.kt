package com.example.looma.feature.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.AnswerModels
import com.example.looma.data.assistant.GenerationError
import com.example.looma.data.assistant.GenerationMeta
import com.example.looma.data.assistant.GeneratorAvailability
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.ModelChoice
import com.example.looma.data.assistant.ThinkingEffort
import com.example.looma.data.assistant.runtime.AgentEvent
import com.example.looma.data.assistant.runtime.AgentRequest
import com.example.looma.data.assistant.runtime.AgentRuntimeExecutor
import com.example.looma.data.assistant.sessions.CompactionResult
import com.example.looma.data.assistant.toGenerationError
import com.example.looma.data.assistant.toGenerationMeta
import com.example.looma.data.assistant.tools.ToolsProvider
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.Vault
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class AskIssue { Run, Timeout, NoModel, Save }

internal enum class ChatRole { User, Assistant }

internal data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val thinking: String = "",
    val isStreaming: Boolean = false,
    val meta: GenerationMeta? = null,
    val error: GenerationError? = null,
    val savedNoteId: NoteId? = null,
)

internal sealed interface AskStatus {
    data object Thinking : AskStatus
    data class ToolCalling(val toolName: String) : AskStatus
    data object Compacting : AskStatus
}

internal sealed interface AskPhase {
    data object Idle : AskPhase
    data class Running(val status: AskStatus = AskStatus.Thinking) : AskPhase
    data object Saving : AskPhase
}

internal data class AskState(
    val vaultName: String,
    val models: List<AnswerModel>,
    val choice: ModelChoice?,
    val messages: List<ChatMessage> = emptyList(),
    val phase: AskPhase = AskPhase.Idle,
    val compactionNotice: CompactionResult? = null,
    val issue: AskIssue? = null,
) {
    val busy: Boolean get() = phase != AskPhase.Idle
    val saving: Boolean get() = phase is AskPhase.Saving
}

internal class AskViewModel(
    private val vault: Vault,
    private val executor: AgentRuntimeExecutor,
    private val tools: ToolsProvider,
    private val models: AnswerModels,
    private val notes: NoteRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(
        models.installed().let { installed ->
            AskState(vault.name, installed, restoredChoice(installed, null, null, null))
        },
    )
    val state = mutable.asStateFlow()
    private var task: Job? = null
    private var picked = false

    init {
        viewModelScope.launch {
            val stored = settings.settings.first()
            mutable.update { current ->
                if (picked) {
                    current
                } else {
                    current.copy(choice = restoredChoice(current.models, stored.askModelId, stored.askEffort, stored.askRoute))
                }
            }
            models.models.collect { installed ->
                mutable.update { current ->
                    val next = if (picked) {
                        current.choice?.let { prev ->
                            installed.firstOrNull { it.id == prev.model.id }?.let { updated ->
                                prev.copy(model = updated)
                            }
                        } ?: installed.firstOrNull()?.let { ModelChoice(it, it.defaultEffort, it.defaultRoute) }
                    } else {
                        restoredChoice(installed, stored.askModelId, stored.askEffort, stored.askRoute)
                    }
                    current.copy(models = installed, choice = next)
                }
            }
        }
    }

    fun refreshModels() {
        models.refresh()
        val installed = models.installed()
        mutable.update { current ->
            if (current.models == installed) return@update current
            val kept = current.choice?.let { prev ->
                installed.firstOrNull { it.id == prev.model.id }?.let { updated ->
                    prev.copy(model = updated)
                }
            }
            current.copy(
                models = installed,
                choice = kept ?: installed.firstOrNull()?.let { ModelChoice(it, it.defaultEffort, it.defaultRoute) },
            )
        }
        if (mutable.value.choice?.model?.availability is GeneratorAvailability.Downloadable) {
            prepareSelectedModel()
        }
    }

    fun select(model: AnswerModel) = pick { current ->
        if (model !in current.models) return@pick current
        val effort = current.choice?.effort?.takeIf { it in model.efforts } ?: model.defaultEffort
        current.copy(choice = ModelChoice(model, effort, model.defaultRoute))
    }

    fun setEffort(effort: ThinkingEffort) = pick { current ->
        val choice = current.choice ?: return@pick current
        if (effort !in choice.model.efforts) current else current.copy(choice = choice.copy(effort = effort))
    }

    fun setRoute(route: InferenceRoute) = pick { current ->
        val choice = current.choice ?: return@pick current
        if (route !in choice.model.supportedRoutes) current else current.copy(choice = choice.copy(route = route))
    }

    fun prepareSelectedModel() {
        val model = mutable.value.choice?.model ?: return
        viewModelScope.launch { models.prepare(model) }
    }

    private fun pick(change: (AskState) -> AskState) {
        val before = mutable.value.choice
        mutable.update(change)
        val choice = mutable.value.choice ?: return
        if (choice == before) return
        picked = true
        viewModelScope.launch { settings.setAskModel(choice) }
    }

    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || mutable.value.busy) return
        sendTurn(trimmed, appendUserMessage = true)
    }

    fun retry() {
        if (mutable.value.busy) return
        val messages = mutable.value.messages
        val lastUser = messages.lastOrNull { it.role == ChatRole.User } ?: return
        val trimmedList = if (messages.lastOrNull()?.role == ChatRole.Assistant) {
            messages.dropLast(1)
        } else {
            messages
        }
        mutable.update { it.copy(messages = trimmedList, issue = null) }
        sendTurn(lastUser.text, appendUserMessage = false)
    }

    private fun sendTurn(question: String, appendUserMessage: Boolean) {
        val choice = mutable.value.choice
        val backend = choice?.let { models.backend(it) }
        if (backend == null) {
            mutable.update { it.copy(issue = AskIssue.NoModel) }
            return
        }

        val assistantId = UUID.randomUUID().toString()
        mutable.update { current ->
            val updated = buildList {
                addAll(current.messages)
                if (appendUserMessage) {
                    add(ChatMessage(role = ChatRole.User, text = question))
                }
                add(ChatMessage(id = assistantId, role = ChatRole.Assistant, text = "", isStreaming = true))
            }
            current.copy(messages = updated, issue = null)
        }

        val maxSteps = 2 + choice.effort.investigationSteps * 2
        val request = AgentRequest(
            question = question,
            systemInstruction = buildSystemInstruction(vault.name),
            maxSteps = maxSteps,
        )

        start(AskPhase.Running(AskStatus.Thinking), AskIssue.Run, assistantId) {
            executor.executeStream(backend, request, tools).collect { event ->
                when (event) {
                    AgentEvent.LoopInitiated -> {
                        mutable.update { it.copy(phase = AskPhase.Running(AskStatus.Thinking)) }
                    }
                    is AgentEvent.StepStarted -> {
                        mutable.update { it.copy(phase = AskPhase.Running(AskStatus.Thinking)) }
                    }
                    is AgentEvent.StreamToken -> {
                        updateAssistant(assistantId) { msg ->
                            msg.copy(
                                text = msg.text + event.token,
                                thinking = msg.thinking + event.thinking,
                                isStreaming = true,
                            )
                        }
                    }
                    is AgentEvent.ToolCallStarted -> {
                        mutable.update {
                            it.copy(phase = AskPhase.Running(AskStatus.ToolCalling(event.toolName)))
                        }
                    }
                    is AgentEvent.ToolCallFinished -> {
                        mutable.update { it.copy(phase = AskPhase.Running(AskStatus.Thinking)) }
                    }
                    is AgentEvent.ContextCompacted -> {
                        if (event.result.compacted) {
                            mutable.update {
                                it.copy(
                                    phase = AskPhase.Running(AskStatus.Compacting),
                                    compactionNotice = event.result,
                                )
                            }
                        }
                    }
                    is AgentEvent.LoopTerminated -> {
                        updateAssistant(assistantId) { msg ->
                            msg.copy(
                                text = event.response.text.ifBlank { msg.text },
                                isStreaming = false,
                                meta = event.response.metadata.toGenerationMeta(),
                            )
                        }
                    }
                    AgentEvent.LoopCancelled -> {
                        finalizeCancelledAssistant(assistantId)
                    }
                    is AgentEvent.Error -> {
                        updateAssistant(assistantId) { msg ->
                            msg.copy(isStreaming = false, error = event.error.toGenerationError())
                        }
                    }
                }
            }
        }
    }

    fun newChat() {
        if (mutable.value.saving) return
        val runningTask = task
        task = null
        runningTask?.cancel()
        viewModelScope.launch {
            executor.interrupt()
            executor.resetSession()
            mutable.update {
                it.copy(
                    messages = emptyList(),
                    phase = AskPhase.Idle,
                    compactionNotice = null,
                    issue = null,
                )
            }
        }
    }

    fun saveMessage(messageId: String, title: String) {
        val current = mutable.value
        if (current.busy || title.isBlank()) return
        val message = current.messages.firstOrNull { it.id == messageId && it.role == ChatRole.Assistant } ?: return
        if (message.savedNoteId != null || message.text.isBlank()) return
        start(AskPhase.Saving, AskIssue.Save) {
            val note = notes.createWithContent(vault, title, message.text)
            updateAssistant(messageId) { it.copy(savedNoteId = note.id) }
        }
    }

    fun cancel() {
        if (mutable.value.saving) return
        val runningTask = task
        task = null
        runningTask?.cancel()
        viewModelScope.launch {
            executor.interrupt()
            mutable.update { current ->
                val cleaned = current.messages.mapNotNull { msg ->
                    if (!msg.isStreaming) msg
                    else if (msg.text.isBlank()) null
                    else msg.copy(isStreaming = false)
                }
                current.copy(messages = cleaned, phase = AskPhase.Idle)
            }
        }
    }

    private fun updateAssistant(assistantId: String, transform: (ChatMessage) -> ChatMessage) {
        mutable.update { current ->
            current.copy(
                messages = current.messages.map { msg ->
                    if (msg.id == assistantId) transform(msg) else msg
                },
            )
        }
    }

    private fun finalizeCancelledAssistant(assistantId: String) {
        mutable.update { current ->
            val updated = current.messages.mapNotNull { msg ->
                if (msg.id != assistantId) msg
                else if (msg.text.isBlank()) null
                else msg.copy(isStreaming = false)
            }
            current.copy(messages = updated)
        }
    }

    private fun start(
        phase: AskPhase,
        failure: AskIssue,
        streamingAssistantId: String? = null,
        action: suspend () -> Unit,
    ) {
        if (task?.isActive == true) return
        task = viewModelScope.launch {
            mutable.update { it.copy(phase = phase, issue = null) }
            try {
                action()
            } catch (_: TimeoutCancellationException) {
                streamingAssistantId?.let(::finalizeCancelledAssistant)
                mutable.update { it.copy(issue = AskIssue.Timeout) }
            } catch (cancel: CancellationException) {
                streamingAssistantId?.let(::finalizeCancelledAssistant)
                throw cancel
            } catch (_: Exception) {
                streamingAssistantId?.let { id ->
                    updateAssistant(id) { it.copy(isStreaming = false, error = it.error ?: GenerationError.Failed()) }
                }
                if (streamingAssistantId == null) {
                    mutable.update { it.copy(issue = failure) }
                }
            } catch (_: LinkageError) {
                streamingAssistantId?.let { id ->
                    updateAssistant(id) { it.copy(isStreaming = false, error = it.error ?: GenerationError.Failed()) }
                }
                if (streamingAssistantId == null) {
                    mutable.update { it.copy(issue = failure) }
                }
            } finally {
                mutable.update { it.copy(phase = AskPhase.Idle) }
            }
        }
    }
}

private fun buildSystemInstruction(vaultName: String): String =
    "You are Looma, a helpful assistant for the user's personal Markdown note vault \"$vaultName\". " +
        "Respond naturally in the user's language using clear Markdown. " +
        "When the user's question may depend on their notes, use the available vault tools " +
        "(search_vault for semantic search, keyword_search for exact text, list_notes to browse by folder/tag, " +
        "read_note to inspect a note, get_note_links for wiki links/backlinks, and create_note to create a new note) " +
        "and cite note paths when referencing them."

private fun restoredChoice(
    installed: List<AnswerModel>,
    modelId: String?,
    effortName: String?,
    routeName: String?,
): ModelChoice? {
    val model = installed.firstOrNull { it.id == modelId }
        ?: return installed.firstOrNull()?.let { ModelChoice(it, it.defaultEffort, it.defaultRoute) }
    val storedEffort = ThinkingEffort.entries.firstOrNull { it.name == effortName }
    val effort = storedEffort?.let { s ->
        model.efforts.lastOrNull { it <= s } ?: model.efforts.firstOrNull()
    } ?: model.defaultEffort
    val storedRoute = InferenceRoute.entries.firstOrNull { it.name == routeName }
    val route = storedRoute?.takeIf { it in model.supportedRoutes } ?: model.defaultRoute
    return ModelChoice(model, effort, route)
}
