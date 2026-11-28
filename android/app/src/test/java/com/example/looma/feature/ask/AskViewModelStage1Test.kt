package com.example.looma.feature.ask

import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.AnswerModels
import com.example.looma.data.assistant.GenerationMeta
import com.example.looma.data.assistant.InferenceOrigin
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
import com.example.looma.data.assistant.runtime.AgentStep
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
import com.example.looma.data.assistant.toLlmMetadata
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelStage1Test {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val vault = Vault(VaultId("vault-1"), "Notes", "/tmp/notes")

    private lateinit var historyRepo: FakeAskHistoryRepository
    private lateinit var sessionManager: FakeLlmSessionManager
    private lateinit var executor: FakeAgentRuntimeExecutor

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        historyRepo = FakeAskHistoryRepository()
        sessionManager = FakeLlmSessionManager()
        executor = FakeAgentRuntimeExecutor(sessionManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `retry rebuilds session history without duplicating retried user message`() = runTest {
        val viewModel = createViewModel()
        executor.nextResponseText = "First answer"
        viewModel.ask("First question")

        executor.nextResponseText = "Second answer"
        viewModel.ask("Second question")

        executor.nextResponseText = "Retried second answer"
        viewModel.retry()

        assertEquals(1, sessionManager.replaceHistoryCalls.size)
        assertEquals(
            listOf(
                LlmMessage(LlmRole.User, "First question"),
                LlmMessage(LlmRole.Model, "First answer"),
            ),
            sessionManager.replaceHistoryCalls.single(),
        )
        assertEquals("Retried second answer", viewModel.state.value.messages.last().text)
    }

    @Test
    fun `loadConversation restores saved modelId into choice`() = runTest {
        val modelA = AnswerModel("model-a", ModelSource.Local, ThinkingEffort.entries, ThinkingEffort.Low)
        val modelB = AnswerModel("model-b", ModelSource.Api, ThinkingEffort.entries, ThinkingEffort.Medium)
        val viewModel = createViewModel(modelsList = listOf(modelA, modelB))

        historyRepo.saveConversation(
            summary = AskConversationSummary(
                id = "conv-b",
                vaultId = vault.id.value,
                title = "Saved with B",
                createdAt = 10L,
                updatedAt = 20L,
                modelId = "model-b",
            ),
            messages = listOf(
                StoredChatMessage("m1", "User", "Hello", "", null, null, null, 10L),
                StoredChatMessage("m2", "Assistant", "World", "", null, null, null, 11L),
            ),
        )

        viewModel.loadConversation("conv-b")

        assertEquals("model-b", viewModel.state.value.choice?.model?.id)
    }

    @Test
    fun `prepareSelectedModel surfaces AskIssue Run when prepare fails`() = runTest {
        val viewModel = createViewModel(prepareResult = Result.failure(IllegalStateException("download failed")))

        viewModel.prepareSelectedModel()

        assertEquals(AskIssue.Run, viewModel.state.value.issue)
    }

    @Test
    fun `fallbackUsed metadata survives conversation persistence and reload`() = runTest {
        val viewModel = createViewModel()
        executor.nextMetadata = GenerationMeta(InferenceOrigin.Cloud, fallbackUsed = true)
        executor.nextResponseText = "Cloud fallback answer"

        viewModel.ask("Question")
        val convId = viewModel.state.value.conversationId!!
        val stored = historyRepo.loadMessages(convId)
        assertTrue(stored[1].fallbackUsed)

        val reloadedViewModel = createViewModel()
        reloadedViewModel.loadConversation(convId)
        assertEquals(true, reloadedViewModel.state.value.messages[1].meta?.fallbackUsed)
    }

    private fun createViewModel(
        modelsList: List<AnswerModel> = listOf(
            AnswerModel("test-model", ModelSource.Local, ThinkingEffort.entries, ThinkingEffort.Low),
        ),
        prepareResult: Result<Unit> = Result.success(Unit),
    ): AskViewModel {
        val provider = object : LlmBackendProvider {
            override val models: StateFlow<List<AnswerModel>> = MutableStateFlow(modelsList)
            override fun backend(model: AnswerModel, route: InferenceRoute?): LlmRuntimeBackend =
                FakeLlmRuntimeBackend(model.id)
            override suspend fun prepare(model: AnswerModel): Result<Unit> = prepareResult
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

        override suspend fun createSession(backend: LlmRuntimeBackend, systemInstruction: String?) {
            this.backend = backend
            this.systemInstruction = systemInstruction
            currentMessages.clear()
        }

        override suspend fun ensureSession(backend: LlmRuntimeBackend, systemInstruction: String?) {
            if (this.backend == null) createSession(backend, systemInstruction)
        }

        override suspend fun resetSession() {
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
        var nextMetadata: GenerationMeta? = null

        override suspend fun execute(
            backend: LlmRuntimeBackend,
            request: AgentRequest,
            tools: ToolsProvider?,
            onStep: (AgentStep) -> Unit,
        ): LlmResult<AgentResponse> = LlmResult.Success(AgentResponse(nextResponseText))

        override fun executeStream(
            backend: LlmRuntimeBackend,
            request: AgentRequest,
            tools: ToolsProvider?,
        ): Flow<AgentEvent> = flow {
            emit(AgentEvent.LoopInitiated)
            emit(AgentEvent.StreamToken(nextResponseText))
            val response = if (nextMetadata != null) {
                AgentResponse(nextResponseText, metadata = nextMetadata!!.toLlmMetadata())
            } else {
                AgentResponse(nextResponseText)
            }
            emit(AgentEvent.LoopTerminated(response))
        }

        override suspend fun compactContext(): CompactionResult = CompactionResult(compacted = false)
        override suspend fun interrupt() = Unit
        override suspend fun resetSession() {
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
