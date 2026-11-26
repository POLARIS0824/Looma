package com.example.looma.data.assistant.skills

import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.AnswerModels
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.LlmBackendProvider
import com.example.looma.data.assistant.ModelChoice
import com.example.looma.data.assistant.ModelSource
import com.example.looma.data.assistant.ThinkingEffort
import com.example.looma.data.assistant.runtime.AgentEvent
import com.example.looma.data.assistant.runtime.AgentRequest
import com.example.looma.data.assistant.runtime.AgentResponse
import com.example.looma.data.assistant.runtime.AgentRuntimeExecutor
import com.example.looma.data.assistant.runtime.AgentStep
import com.example.looma.data.assistant.runtime.LlmGenerateRequest
import com.example.looma.data.assistant.runtime.LlmMessage
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.runtime.LlmStreamChunk
import com.example.looma.data.assistant.sessions.CompactionResult
import com.example.looma.data.assistant.sessions.ContextCompactor
import com.example.looma.data.assistant.sessions.ContextUsage
import com.example.looma.data.assistant.sessions.LlmSessionManager
import com.example.looma.data.assistant.tools.AgentSkillDetail
import com.example.looma.data.assistant.tools.AgentSkillSummary
import com.example.looma.data.assistant.tools.DefaultToolsProvider
import com.example.looma.data.assistant.tools.LoadSkillTool
import com.example.looma.data.assistant.tools.SkillCatalog
import com.example.looma.data.assistant.tools.ToolDispatcher
import com.example.looma.data.assistant.tools.ToolsProvider
import com.example.looma.data.settings.AppSettings
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import com.example.looma.feature.ask.AskModelSettings
import com.example.looma.feature.ask.AskNoteSaver
import com.example.looma.feature.ask.AskViewModel
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSkillsTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `SkillParser parses valid SKILL md with unquoted frontmatter and markdown body`() {
        val raw = "\uFEFF---\r\nname: daily-review\r\ndescription: Summarize daily notes and tasks.\r\n---\r\n\r\n# Steps\r\n1. Read today's note.\r\n2. List open items.\r\n"

        val parsed = SkillParser.parse(raw)

        assertNotNull(parsed)
        assertEquals("daily-review", parsed!!.name)
        assertEquals("Summarize daily notes and tasks.", parsed.description)
        assertEquals("# Steps\n1. Read today's note.\n2. List open items.", parsed.instructions)
    }

    @Test
    fun `SkillParser strips single and double quotes from frontmatter values`() {
        val rawDouble = """
            ---
            name: "quoted-skill"
            description: "Description with: colons inside"
            ---
            Follow these steps.
        """.trimIndent()
        val rawSingle = """
            ---
            name: 'single-quoted'
            description: 'Single quoted description'
            ---
            Body text.
        """.trimIndent()

        val parsedDouble = SkillParser.parse(rawDouble)
        val parsedSingle = SkillParser.parse(rawSingle)

        assertEquals(
            AgentSkillDetail(
                name = "quoted-skill",
                description = "Description with: colons inside",
                instructions = "Follow these steps.",
            ),
            parsedDouble,
        )
        assertEquals(
            AgentSkillDetail(
                name = "single-quoted",
                description = "Single quoted description",
                instructions = "Body text.",
            ),
            parsedSingle,
        )
    }

    @Test
    fun `SkillParser returns null for missing frontmatter, missing or blank fields, or blank body`() {
        val cases = listOf(
            "name: no-frontmatter\ndescription: desc\n---\nBody",
            "---\nname: unclosed\ndescription: desc\nBody",
            "---\ndescription: missing name\n---\nBody",
            "---\nname:   \ndescription: blank name\n---\nBody",
            "---\nname: missing-desc\n---\nBody",
            "---\nname: blank-desc\ndescription: \"   \"\n---\nBody",
            "---\nname: blank-body\ndescription: valid desc\n---\n   \n  ",
        )

        for (raw in cases) {
            assertNull("Expected null for:\n$raw", SkillParser.parse(raw))
        }
    }

    @Test
    fun `VaultSkillManager exposes built-in skills when vault is null or has no skills directory`() = runBlocking {
        val nullVaultManager = VaultSkillManager(currentVaultOrNull = { null })
        nullVaultManager.refresh()

        val expectedNames = listOf("vault-briefing", "note-organizer", "wiki-linker")
        assertEquals(expectedNames, nullVaultManager.skills.value.map { it.name })
        assertTrue(nullVaultManager.skills.value.all { it.isBuiltIn && it.enabled })
        assertEquals(expectedNames, nullVaultManager.enabledSkills().map { it.name })

        val vaultRoot = tempFolder.newFolder("empty-vault")
        val emptyVault = Vault(VaultId("v1"), "Empty", vaultRoot.absolutePath)
        val emptyVaultManager = VaultSkillManager(currentVaultOrNull = { emptyVault })

        assertEquals(expectedNames, emptyVaultManager.enabledSkills().map { it.name })
    }

    @Test
    fun `VaultSkillManager discovers custom skills and overrides built-in skill on name collision`() = runBlocking {
        val vaultRoot = tempFolder.newFolder("custom-vault")
        writeSkillFile(
            vaultRoot = vaultRoot,
            dirName = "meeting-prep",
            content = """
                ---
                name: meeting-prep
                description: Prepare agenda from recent project notes.
                ---
                Custom meeting prep instructions.
            """.trimIndent(),
        )
        writeSkillFile(
            vaultRoot = vaultRoot,
            dirName = "override-briefing",
            content = """
                ---
                name: vault-briefing
                description: Custom vault briefing override.
                ---
                Overridden briefing instructions.
            """.trimIndent(),
        )
        writeSkillFile(
            vaultRoot = vaultRoot,
            dirName = "broken-skill",
            content = "not valid frontmatter",
        )
        File(vaultRoot, ".looma/skills/empty-dir").mkdirs()

        val vault = Vault(VaultId("v2"), "Custom", vaultRoot.absolutePath)
        val manager = VaultSkillManager(currentVaultOrNull = { vault })
        manager.refresh()

        val summaries = manager.skills.value
        assertEquals(
            listOf("vault-briefing", "note-organizer", "wiki-linker", "meeting-prep"),
            summaries.map { it.name },
        )

        val overridden = summaries.first { it.name == "vault-briefing" }
        assertFalse(overridden.isBuiltIn)
        assertEquals("Custom vault briefing override.", overridden.description)

        val custom = summaries.first { it.name == "meeting-prep" }
        assertFalse(custom.isBuiltIn)
        assertTrue(custom.enabled)

        val loadedOverride = manager.loadSkill("vault-briefing")
        assertEquals("Overridden briefing instructions.", loadedOverride?.instructions)

        val loadedCustom = manager.loadSkill("meeting-prep")
        assertEquals("Custom meeting prep instructions.", loadedCustom?.instructions)
    }

    @Test
    fun `VaultSkillManager persists disabled state across instances and enforces loadSkill availability`() = runBlocking {
        val vaultRoot = tempFolder.newFolder("persisted-vault")
        val vault = Vault(VaultId("v3"), "Persisted", vaultRoot.absolutePath)
        val manager1 = VaultSkillManager(currentVaultOrNull = { vault })

        manager1.setEnabled("wiki-linker", false)

        val disabledFile = File(vaultRoot, ".looma/skills-disabled.txt")
        assertTrue(disabledFile.isFile)
        assertEquals(listOf("wiki-linker"), disabledFile.readLines())

        assertFalse(manager1.skills.value.first { it.name == "wiki-linker" }.enabled)
        assertEquals(listOf("vault-briefing", "note-organizer"), manager1.enabledSkills().map { it.name })
        assertNull(manager1.loadSkill("wiki-linker"))
        assertNotNull(manager1.loadSkill("  vault-briefing  "))
        assertNull(manager1.loadSkill("non-existent"))
        assertNull(manager1.loadSkill("   "))

        val manager2 = VaultSkillManager(currentVaultOrNull = { vault })
        assertEquals(listOf("vault-briefing", "note-organizer"), manager2.enabledSkills().map { it.name })
        assertNull(manager2.loadSkill("wiki-linker"))

        manager2.setEnabled("wiki-linker", true)

        assertFalse(disabledFile.exists())
        assertTrue(manager2.skills.value.first { it.name == "wiki-linker" }.enabled)
        assertNotNull(manager2.loadSkill("wiki-linker"))
    }

    @Test
    fun `LoadSkillTool spec declares load_skill with required skill_name`() {
        val tool = LoadSkillTool(FakeSkillCatalog())
        val schema = JSONObject(tool.spec.parametersSchemaJson)

        assertEquals("load_skill", tool.spec.name)
        assertEquals("object", schema.getString("type"))
        assertTrue(schema.getJSONObject("properties").has("skill_name"))
        val required = schema.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("skill_name", required.getString(0))
    }

    @Test
    fun `LoadSkillTool returns instructions when enabled and error for blank, unknown, or disabled skills`() = runBlocking {
        val catalog = FakeSkillCatalog(
            initialDetails = listOf(
                AgentSkillDetail(
                    name = "vault-briefing",
                    description = "Summarize recent notes.",
                    instructions = "1. List notes.\n2. Summarize.",
                ),
                AgentSkillDetail(
                    name = "disabled-skill",
                    description = "Disabled skill.",
                    instructions = "Secret instructions.",
                ),
            ),
            initiallyDisabled = setOf("disabled-skill"),
        )
        val tool = LoadSkillTool(catalog)
        val dispatcher = ToolDispatcher()
        val provider = DefaultToolsProvider(listOf(tool))

        val missingArg = tool.execute(JSONObject())
        assertTrue(missingArg.isError)

        val blankArg = tool.execute(JSONObject().put("skill_name", "   "))
        assertTrue(blankArg.isError)

        val unknown = tool.execute(JSONObject().put("skill_name", "unknown"))
        assertTrue(unknown.isError)

        val disabled = tool.execute(JSONObject().put("skill_name", "disabled-skill"))
        assertTrue(disabled.isError)

        val success = dispatcher.dispatch(
            provider = provider,
            name = "load_skill",
            rawArgsJson = """{"skill_name":"vault-briefing"}""",
        )
        assertFalse(success.isError)
        assertEquals(
            "Skill: vault-briefing\nDescription: Summarize recent notes.\n\n1. List notes.\n2. Summarize.",
            success.output,
        )
    }

    @Test
    fun `AskViewModel progressive disclosure includes only enabled skill summaries and load_skill guidance`() = runTest {
        val catalog = FakeSkillCatalog(
            initialDetails = listOf(
                AgentSkillDetail(
                    name = "vault-briefing",
                    description = "Summarize recent notes and tasks.",
                    instructions = "SECRET_BRIEFING_BODY_SHOULD_NOT_BE_INLINED",
                ),
                AgentSkillDetail(
                    name = "wiki-linker",
                    description = "Suggest wiki-link connections.",
                    instructions = "SECRET_LINKER_BODY_SHOULD_NOT_BE_INLINED",
                ),
            ),
            initiallyDisabled = setOf("wiki-linker"),
        )
        val executor = CapturingAgentRuntimeExecutor()
        val viewModel = createAskViewModel(executor, catalog)

        assertEquals(2, viewModel.state.value.skills.size)

        viewModel.ask("Give me a briefing")

        val request = executor.lastRequest
        assertNotNull(request)
        val systemInstruction = request!!.systemInstruction!!
        assertTrue(systemInstruction.contains("load_skill"))
        assertTrue(systemInstruction.contains("skill_name"))
        assertTrue(systemInstruction.contains("- vault-briefing: Summarize recent notes and tasks."))
        assertFalse(systemInstruction.contains("wiki-linker"))
        assertFalse(systemInstruction.contains("Suggest wiki-link connections."))
        assertFalse(systemInstruction.contains("SECRET_BRIEFING_BODY_SHOULD_NOT_BE_INLINED"))
        assertFalse(systemInstruction.contains("SECRET_LINKER_BODY_SHOULD_NOT_BE_INLINED"))
    }

    private fun writeSkillFile(vaultRoot: File, dirName: String, content: String) {
        val skillDir = File(vaultRoot, ".looma/skills/$dirName")
        skillDir.mkdirs()
        File(skillDir, "SKILL.md").writeText(content, Charsets.UTF_8)
    }

    private fun createAskViewModel(
        executor: AgentRuntimeExecutor,
        catalog: SkillCatalog,
    ): AskViewModel {
        val vault = Vault(VaultId("vault-1"), "Notes", "/tmp/notes")
        val model = AnswerModel(
            id = "test-model",
            source = ModelSource.Local,
            efforts = ThinkingEffort.entries,
            defaultEffort = ThinkingEffort.Low,
        )
        val backend = object : LlmRuntimeBackend {
            override val modelId: String = model.id
            override val contextBudgetChars: Int = 8000
            override val maxContextTokens: Int = 4096
            override suspend fun generate(request: LlmGenerateRequest): LlmResult<String> = LlmResult.Success("")
            override fun generateStream(request: LlmGenerateRequest): Flow<LlmResult<LlmStreamChunk>> =
                flowOf(LlmResult.Success(LlmStreamChunk(done = true)))
        }
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
            skills = catalog,
        )
    }

    private class FakeSkillCatalog(
        initialDetails: List<AgentSkillDetail> = emptyList(),
        initiallyDisabled: Set<String> = emptySet(),
    ) : SkillCatalog {
        private val detailsByName = initialDetails.associateBy { it.name }
        private val disabled = initiallyDisabled.toMutableSet()
        private val mutableSkills = MutableStateFlow(buildSummaries())

        override val skills: StateFlow<List<AgentSkillSummary>> = mutableSkills.asStateFlow()

        override suspend fun refresh() {
            mutableSkills.value = buildSummaries()
        }

        override suspend fun enabledSkills(): List<AgentSkillSummary> =
            mutableSkills.value.filter { it.enabled }

        override suspend fun loadSkill(name: String): AgentSkillDetail? {
            val trimmed = name.trim()
            if (trimmed in disabled) return null
            return detailsByName[trimmed]
        }

        override suspend fun setEnabled(name: String, enabled: Boolean) {
            if (enabled) disabled.remove(name) else disabled.add(name)
            mutableSkills.value = buildSummaries()
        }

        private fun buildSummaries(): List<AgentSkillSummary> =
            detailsByName.values.map { detail ->
                AgentSkillSummary(
                    name = detail.name,
                    description = detail.description,
                    isBuiltIn = true,
                    enabled = detail.name !in disabled,
                )
            }
    }

    private class CapturingAgentRuntimeExecutor : AgentRuntimeExecutor {
        var lastRequest: AgentRequest? = null

        override val sessionManager: LlmSessionManager = object : LlmSessionManager {
            override var backend: LlmRuntimeBackend? = null
            override var systemInstruction: String? = null
            override val messages: List<LlmMessage> = emptyList()
            override suspend fun createSession(backend: LlmRuntimeBackend, systemInstruction: String?) = Unit
            override suspend fun ensureSession(backend: LlmRuntimeBackend, systemInstruction: String?) = Unit
            override suspend fun resetSession() = Unit
            override suspend fun replaceHistory(messages: List<LlmMessage>) = Unit
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

        override suspend fun execute(
            backend: LlmRuntimeBackend,
            request: AgentRequest,
            tools: ToolsProvider?,
            onStep: (AgentStep) -> Unit,
        ): LlmResult<AgentResponse> {
            lastRequest = request
            return LlmResult.Success(AgentResponse("Done"))
        }

        override fun executeStream(
            backend: LlmRuntimeBackend,
            request: AgentRequest,
            tools: ToolsProvider?,
        ): Flow<AgentEvent> {
            lastRequest = request
            return flow {
                emit(AgentEvent.LoopInitiated)
                emit(AgentEvent.LoopTerminated(AgentResponse("Done")))
            }
        }

        override suspend fun compactContext(): CompactionResult = CompactionResult(compacted = false)
        override suspend fun interrupt() = Unit
        override suspend fun resetSession() = Unit
        override suspend fun cleanUp() = Unit
    }
}
