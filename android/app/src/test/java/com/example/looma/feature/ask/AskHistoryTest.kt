package com.example.looma.feature.ask

import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.AnswerModels
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.LlmBackendProvider
import com.example.looma.data.assistant.ModelChoice
import com.example.looma.data.assistant.ModelSource
import com.example.looma.data.assistant.ThinkingEffort
import com.example.looma.data.assistant.history.AskConversationSummary
import com.example.looma.data.assistant.history.AskHistoryRepository
import com.example.looma.data.assistant.history.StoredChatMessage
import com.example.looma.data.assistant.runtime.AgentEvent
import com.example.looma.data.assistant.runtime.AgentRequest
import com.example.looma.data.assistant.runtime.AgentResponse
import com.example.looma.data.assistant.runtime.AgentRuntimeExecutor
import com.example.looma.data.assistant.runtime.LlmGenerateRequest
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRole
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import com.example.looma.data.assistant.sessions.CompactionResult
import com.example.looma.data.assistant.sessions.ContextCompactor
import com.example.looma.data.assistant.sessions.ContextUsage
import com.example.looma.data.assistant.sessions.LlmSessionManager
import com.example.looma.data.assistant.tools.ToolsProvider
import com.example.looma.data.settings.AppSettings
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskHistoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val vault = Vault(VaultId("vault-1"), "Notes", "/tmp/notes")

    private lateinit var historyRepo: FakeAskHistoryRepository
    private lateinit var sessionManager: FakeLlmSessionManager
    private lateinit var executor: FakeAgentRuntimeExecutor
    private lateinit var viewModel: AskViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        historyRepo = FakeAskHistoryRepository()
        sessionManager = FakeLlmSessionManager()
        executor = FakeAgentRuntimeExecutor(sessionManager)
        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `new question in fresh chat auto-creates conversation and persists user and assistant messages`() = runTest {
        executor.nextResponseText = "Here is the summary."

        viewModel.ask("Summarize my notes")

        val state = viewModel.state.value
        val conversationId = state.conversationId
        assertNotNull(conversationId)
        assertEquals(2, state.messages.size)
        assertEquals(ChatRole.User, state.messages[0].role)
        assertEquals("Summarize my notes", state.messages[0].text)
        assertEquals(ChatRole.Assistant, state.messages[1].role)
        assertEquals("Here is the summary.", state.messages[1].text)

        val savedSummaries = state.conversations
        assertEquals(1, savedSummaries.size)
        assertEquals(conversationId, savedSummaries[0].id)
        assertEquals("Summarize my notes", savedSummaries[0].title)

        val persisted = historyRepo.loadMessages(conversationId!!)
        assertEquals(2, persisted.size)
        assertEquals("User", persisted[0].role)
        assertEquals("Summarize my notes", persisted[0].text)
        assertEquals("Assistant", persisted[1].role)
        assertEquals("Here is the summary.", persisted[1].text)
    }

    @Test
    fun `multi-turn follow-up appends to same conversationId and updates repository`() = runTest {
        executor.nextResponseText = "First answer"
        viewModel.ask("First question")
        val firstConversationId = viewModel.state.value.conversationId
        assertNotNull(firstConversationId)

        executor.nextResponseText = "Second answer"
        viewModel.ask("Follow-up question")

        val state = viewModel.state.value
        assertEquals(firstConversationId, state.conversationId)
        assertEquals(4, state.messages.size)
        assertEquals(1, state.conversations.size)

        val persisted = historyRepo.loadMessages(firstConversationId!!)
        assertEquals(4, persisted.size)
        assertEquals(
            listOf("First question", "First answer", "Follow-up question", "Second answer"),
            persisted.map { it.text },
        )
    }

    @Test
    fun `loadConversation restores UI messages and replaces session history excluding error and blank messages`() = runTest {
        val convId = "conv-existing"
        historyRepo.saveConversation(
            summary = AskConversationSummary(
                id = convId,
                vaultId = vault.id.value,
                title = "Saved chat",
                createdAt = 100L,
                updatedAt = 200L,
                modelId = "test-model",
            ),
            messages = listOf(
                StoredChatMessage("m1", "User", "Hello", "", null, null, null, 100L),
                StoredChatMessage("m2", "Assistant", "Hi there", "", null, null, null, 101L),
                StoredChatMessage("m3", "Assistant", "   ", "", null, null, null, 102L),
                StoredChatMessage("m4", "Assistant", "Failed partial", "", null, "offline", null, 103L),
            ),
        )

        viewModel.loadConversation(convId)

        val state = viewModel.state.value
        assertEquals(convId, state.conversationId)
        assertEquals(4, state.messages.size)
        assertEquals(ChatRole.User, state.messages[0].role)
        assertEquals("Hello", state.messages[0].text)
        assertEquals("Hi there", state.messages[1].text)
        assertNotNull(state.messages[3].error)

        assertEquals(1, sessionManager.replaceHistoryCalls.size)
        assertEquals(
            listOf(
                LlmMessage(LlmRole.User, "Hello"),
                LlmMessage(LlmRole.Model, "Hi there"),
            ),
            sessionManager.replaceHistoryCalls.single(),
        )
    }

    @Test
    fun `deleteConversation clears active chat and resets session when deleting active conversation`() = runTest {
        executor.nextResponseText = "Active reply"
        viewModel.ask("Active question")
        val activeId = viewModel.state.value.conversationId!!
        val resetsBefore = executor.resetSessionCount

        viewModel.deleteConversation(activeId)

        val state = viewModel.state.value
        assertNull(state.conversationId)
        assertTrue(state.messages.isEmpty())
        assertTrue(state.conversations.isEmpty())
        assertEquals(resetsBefore + 1, executor.resetSessionCount)
        assertTrue(historyRepo.loadMessages(activeId).isEmpty())
    }

    @Test
    fun `deleteConversation preserves active chat when deleting non-active conversation`() = runTest {
        val otherId = "conv-other"
        historyRepo.saveConversation(
            summary = AskConversationSummary(
                id = otherId,
                vaultId = vault.id.value,
                title = "Other chat",
                createdAt = 10L,
                updatedAt = 20L,
                modelId = "test-model",
            ),
            messages = listOf(
                StoredChatMessage("o1", "User", "Old question", "", null, null, null, 10L),
            ),
        )

        executor.nextResponseText = "Current reply"
        viewModel.ask("Current question")
        val activeId = viewModel.state.value.conversationId!!
        val resetsBefore = executor.resetSessionCount

        viewModel.deleteConversation(otherId)

        val state = viewModel.state.value
        assertEquals(activeId, state.conversationId)
        assertEquals(2, state.messages.size)
        assertEquals(listOf(activeId), state.conversations.map { it.id })
        assertEquals(resetsBefore, executor.resetSessionCount)
        assertTrue(historyRepo.loadMessages(otherId).isEmpty())
        assertEquals(2, historyRepo.loadMessages(activeId).size)
    }

    @Test
    fun `newChat clears UI state and resets session without deleting persisted history`() = runTest {
        executor.nextResponseText = "Persisted answer"
        viewModel.ask("Persisted question")
        val convId = viewModel.state.value.conversationId!!
        val resetsBefore = executor.resetSessionCount

        viewModel.newChat()

        val state = viewModel.state.value
        assertNull(state.conversationId)
        assertTrue(state.messages.isEmpty())
        assertEquals(resetsBefore + 1, executor.resetSessionCount)
        assertEquals(1, state.conversations.size)
        assertEquals(convId, state.conversations[0].id)
        assertEquals(2, historyRepo.loadMessages(convId).size)
    }

    private fun createViewModel(): AskViewModel {
        val model = AnswerModel(
            id = "test-model",
            source = ModelSource.Local,
            efforts = ThinkingEffort.entries,
            defaultEffort = ThinkingEffort.Low,
        )
        val backend = FakeLlmRuntimeBackend(model.id)
        val provider = object : LlmBackendProvider {
            override val models: StateFlow<List<AnswerModel>> = MutableStateFlow(listOf(model))
            override fun backend(model: AnswerModel, route: InferenceRoute?): LlmRuntimeBackend = backend
        }
        return AskViewModel(
            vault = vault,
            executor = executor,
            tools = ToolsProvider { emptyList() },
            models = AnswerModels(listOf(provider)),
            noteSaver = AskNoteSaver { v, title, content ->
                Note(
                    id = NoteId(v.id, NotePath("$title.md")),
                    title = title,
                    content = content,
                    modifiedAt = Instant.EPOCH,
                )
            },
            modelSettings = object : AskModelSettings {
                override val settings: Flow<AppSettings> = flowOf(AppSettings())
                override suspend fun setAskModel(choice: ModelChoice) = Unit
            },
            history = historyRepo,
        )
    }

    private class FakeAskHistoryRepository : AskHistoryRepository {
        private val summaries = MutableStateFlow<List<AskConversationSummary>>(emptyList())
        private val messagesByConversation = mutableMapOf<String, List<StoredChatMessage>>()

        override fun conversations(vaultId: String): Flow<List<AskConversationSummary>> =
            summaries.map { list -> list.filter { it.vaultId == vaultId } }

        override suspend fun loadMessages(conversationId: String): List<StoredChatMessage> =
            messagesByConversation[conversationId].orEmpty()

        override suspend fun saveConversation(
            summary: AskConversationSummary,
            messages: List<StoredChatMessage>,
        ) {
            messagesByConversation[summary.id] = messages
            summaries.value = summaries.value.filterNot { it.id == summary.id } + summary
        }

        override suspend fun deleteConversation(conversationId: String) {
            messagesByConversation.remove(conversationId)
            summaries.value = summaries.value.filterNot { it.id == conversationId }
        }
    }

    private class FakeLlmSessionManager : LlmSessionManager {
        override var backend: LlmRuntimeBackend? = null
        override var systemInstruction: String? = null
        private val currentMessages = mutableListOf<LlmMessage>()
        override val messages: List<LlmMessage> get() = currentMessages.toList()

        val replaceHistoryCalls = mutableListOf<List<LlmMessage>>()
        var resetSessionCount = 0

        override suspend fun createSession(backend: LlmRuntimeBackend, systemInstruction: String?) {
            this.backend = backend
            this.systemInstruction = systemInstruction
            currentMessages.clear()
        }

        override suspend fun ensureSession(backend: LlmRuntimeBackend, systemInstruction: String?) {
            if (this.backend == null) createSession(backend, systemInstruction)
        }

        override suspend fun resetSession() {
            resetSessionCount++
            currentMessages.clear()
        }

        override suspend fun replaceHistory(messages: List<LlmMessage>) {
            replaceHistoryCalls.add(messages)
            currentMessages.clear()
            currentMessages.addAll(messages)
        }

        override fun estimateTokens(): Int = 0
        override fun contextUsage(): ContextUsage? = null
        override suspend fun compactIfNeeded(compactor: ContextCompactor): CompactionResult =
            CompactionResult(compacted = false)

        override suspend fun generateResponse(
            prompt: String,
            ephemeral: Boolean,
            responseMimeType: String?,
            maxOutputTokens: Int?,
        ): LlmResult<String> = LlmResult.Success("")

        override fun generateStream(
            prompt: String,
            ephemeral: Boolean,
            responseMimeType: String?,
            maxOutputTokens: Int?,
        ): Flow<LlmResult<LlmStreamChunk>> = flowOf(LlmResult.Success(LlmStreamChunk(done = true)))

        override suspend fun stopResponse() = Unit
    }

    private class FakeAgentRuntimeExecutor(
        override val sessionManager: FakeLlmSessionManager,
    ) : AgentRuntimeExecutor {
        var nextResponseText: String = "Default response"
        var resetSessionCount = 0

        override suspend fun execute(
            backend: LlmRuntimeBackend,
            request: AgentRequest,
            tools: ToolsProvider?,
            onStep: (com.example.looma.data.assistant.runtime.AgentStep) -> Unit,
        ): LlmResult<AgentResponse> = LlmResult.Success(AgentResponse(nextResponseText))

        override fun executeStream(
            backend: LlmRuntimeBackend,
            request: AgentRequest,
            tools: ToolsProvider?,
        ): Flow<AgentEvent> = flow {
            emit(AgentEvent.LoopInitiated)
            emit(AgentEvent.StreamToken(nextResponseText))
            emit(AgentEvent.LoopTerminated(AgentResponse(nextResponseText)))
        }

        override suspend fun compactContext(): CompactionResult = CompactionResult(compacted = false)
        override suspend fun interrupt() = Unit
        override suspend fun resetSession() {
            resetSessionCount++
            sessionManager.resetSession()
        }

        override suspend fun cleanUp() = Unit
    }

    private class FakeLlmRuntimeBackend(
        override val modelId: String,
    ) : LlmRuntimeBackend {
        override val contextBudgetChars: Int = 8000
        override val maxContextTokens: Int = 4096

        override suspend fun generate(request: LlmGenerateRequest): LlmResult<String> =
            LlmResult.Success("")

        override fun generateStream(request: LlmGenerateRequest): Flow<LlmResult<LlmStreamChunk>> =
            flowOf(LlmResult.Success(LlmStreamChunk(done = true)))
    }
}
