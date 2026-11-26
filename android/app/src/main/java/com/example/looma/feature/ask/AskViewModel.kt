package com.example.looma.feature.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.AnswerModels
import com.example.looma.data.assistant.GenerationError
import com.example.looma.data.assistant.GenerationMeta
import com.example.looma.data.assistant.GeneratorAvailability
import com.example.looma.data.assistant.InferenceOrigin
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.ModelChoice
import com.example.looma.data.assistant.ThinkingEffort
import com.example.looma.data.assistant.history.AskConversationSummary
import com.example.looma.data.assistant.history.AskHistoryRepository
import com.example.looma.data.assistant.history.StoredChatMessage
import com.example.looma.data.assistant.runtime.AgentEvent
import com.example.looma.data.assistant.runtime.AgentRequest
import com.example.looma.data.assistant.runtime.AgentRuntimeExecutor
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.sessions.CompactionResult
import com.example.looma.data.assistant.toGenerationError
import com.example.looma.data.assistant.toGenerationMeta
import com.example.looma.data.assistant.tools.ToolsProvider
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.settings.AppSettings
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
    val conversationId: String? = null,
    val conversations: List<AskConversationSummary> = emptyList(),
) {
    val busy: Boolean get() = phase != AskPhase.Idle
    val saving: Boolean get() = phase is AskPhase.Saving
}

internal interface AskModelSettings {
    val settings: Flow<AppSettings>
    suspend fun setAskModel(choice: ModelChoice)
}

internal fun interface AskNoteSaver {
    suspend fun saveNote(vault: Vault, title: String, content: String): Note
}

internal class AskViewModel(
    private val vault: Vault,
    private val executor: AgentRuntimeExecutor,
    private val tools: ToolsProvider,
    private val models: AnswerModels,
    private val noteSaver: AskNoteSaver,
    private val modelSettings: AskModelSettings,
    private val history: AskHistoryRepository = NoopAskHistoryRepository,
) : ViewModel() {
    constructor(
        vault: Vault,
        executor: AgentRuntimeExecutor,
        tools: ToolsProvider,
        models: AnswerModels,
        notes: NoteRepository,
        settings: SettingsRepository,
        history: AskHistoryRepository = NoopAskHistoryRepository,
    ) : this(
        vault = vault,
        executor = executor,
        tools = tools,
        models = models,
        noteSaver = AskNoteSaver { v, title, content -> notes.createWithContent(v, title, content) },
        modelSettings = object : AskModelSettings {
            override val settings: Flow<AppSettings> = settings.settings
            override suspend fun setAskModel(choice: ModelChoice) = settings.setAskModel(choice)
        },
        history = history,
    )
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
            history.conversations(vault.id.value).collect { list ->
                mutable.update { it.copy(conversations = list) }
            }
        }
        viewModelScope.launch {
            val stored = modelSettings.settings.first()
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
        viewModelScope.launch { modelSettings.setAskModel(choice) }
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
            current.copy(
                messages = updated,
                issue = null,
                conversationId = current.conversationId ?: UUID.randomUUID().toString(),
            )
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
                        persistCurrentConversation()
                    }
                    AgentEvent.LoopCancelled -> {
                        finalizeCancelledAssistant(assistantId)
                        persistCurrentConversation()
                    }
                    is AgentEvent.Error -> {
                        updateAssistant(assistantId) { msg ->
                            msg.copy(isStreaming = false, error = event.error.toGenerationError())
                        }
                        persistCurrentConversation()
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
                    conversationId = null,
                )
            }
        }
    }

    fun loadConversation(conversationId: String) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            val stored = history.loadMessages(conversationId)
            val loaded = stored.map { it.toChatMessage(vault) }
            val llmMessages = loaded
                .filter { it.text.isNotBlank() && it.error == null }
                .map { msg ->
                    LlmMessage(
                        role = if (msg.role == ChatRole.User) LlmRole.User else LlmRole.Model,
                        content = msg.text,
                    )
                }
            executor.sessionManager.replaceHistory(llmMessages)
            mutable.update {
                it.copy(
                    conversationId = conversationId,
                    messages = loaded,
                    compactionNotice = null,
                    issue = null,
                )
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        if (mutable.value.saving) return
        val isCurrent = conversationId == mutable.value.conversationId
        if (isCurrent) {
            val runningTask = task
            task = null
            runningTask?.cancel()
        }
        viewModelScope.launch {
            history.deleteConversation(conversationId)
            if (isCurrent) {
                executor.interrupt()
                executor.resetSession()
                mutable.update {
                    it.copy(
                        messages = emptyList(),
                        conversationId = null,
                        compactionNotice = null,
                        issue = null,
                        phase = AskPhase.Idle,
                    )
                }
            }
        }
    }

    fun saveMessage(messageId: String, title: String) {
        val current = mutable.value
        if (current.busy || title.isBlank()) return
        val message = current.messages.firstOrNull { it.id == messageId && it.role == ChatRole.Assistant } ?: return
        if (message.savedNoteId != null || message.text.isBlank()) return
        start(AskPhase.Saving, AskIssue.Save) {
            val note = noteSaver.saveNote(vault, title, message.text)
            updateAssistant(messageId) { it.copy(savedNoteId = note.id) }
            persistCurrentConversation()
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
            persistCurrentConversation()
        }
    }

    private suspend fun persistCurrentConversation() {
        val current = mutable.value
        val conversationId = current.conversationId ?: return
        val persistedMessages = current.messages.filter { !it.isStreaming }
        if (persistedMessages.isEmpty()) return
        val now = System.currentTimeMillis()
        val createdAt = current.conversations.firstOrNull { it.id == conversationId }?.createdAt ?: now
        val title = persistedMessages
            .firstOrNull { it.role == ChatRole.User }
            ?.text
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.take(80)
            .orEmpty()
            .ifBlank { vault.name }
        val summary = AskConversationSummary(
            id = conversationId,
            vaultId = vault.id.value,
            title = title,
            createdAt = createdAt,
            updatedAt = now,
            modelId = current.choice?.model?.id,
        )
        history.saveConversation(
            summary = summary,
            messages = persistedMessages.mapIndexed { index, msg -> msg.toStored(now + index) },
        )
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

private object NoopAskHistoryRepository : AskHistoryRepository {
    override fun conversations(vaultId: String): Flow<List<AskConversationSummary>> = flowOf(emptyList())
    override suspend fun loadMessages(conversationId: String): List<StoredChatMessage> = emptyList()
    override suspend fun saveConversation(summary: AskConversationSummary, messages: List<StoredChatMessage>) = Unit
    override suspend fun deleteConversation(conversationId: String) = Unit
}

private fun ChatMessage.toStored(createdAt: Long): StoredChatMessage = StoredChatMessage(
    id = id,
    role = role.name,
    text = text,
    thinking = thinking,
    origin = meta?.origin?.name,
    errorCode = error?.toStoredCode(),
    savedNotePath = savedNoteId?.path?.relativePath,
    createdAt = createdAt,
)

private fun StoredChatMessage.toChatMessage(vault: Vault): ChatMessage = ChatMessage(
    id = id,
    role = ChatRole.entries.firstOrNull { it.name == role } ?: ChatRole.Assistant,
    text = text,
    thinking = thinking,
    isStreaming = false,
    meta = origin?.let { name -> InferenceOrigin.entries.firstOrNull { it.name == name } }?.let { GenerationMeta(it) },
    error = errorCode?.toStoredGenerationError(),
    savedNoteId = savedNotePath?.let { NoteId(vault.id, NotePath(it)) },
)

private fun GenerationError.toStoredCode(): String = when (this) {
    GenerationError.Offline -> "offline"
    GenerationError.OnDeviceUnavailable -> "unavailable"
    GenerationError.AppCheckFailed -> "auth_failed"
    GenerationError.QuotaExceeded -> "quota_exceeded"
    is GenerationError.Failed -> if (message == null) "failed" else "failed:$message"
}

private fun String.toStoredGenerationError(): GenerationError = when {
    this == "offline" -> GenerationError.Offline
    this == "unavailable" -> GenerationError.OnDeviceUnavailable
    this == "auth_failed" -> GenerationError.AppCheckFailed
    this == "quota_exceeded" -> GenerationError.QuotaExceeded
    this == "failed" -> GenerationError.Failed()
    startsWith("failed:") -> GenerationError.Failed(removePrefix("failed:"))
    else -> GenerationError.Failed(this)
}
