package com.example.looma.feature.ask

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.looma.R
import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.AnswerModels
import com.example.looma.data.assistant.ApiConnection
import com.example.looma.data.assistant.ApiModel
import com.example.looma.data.assistant.ApiProvider
import com.example.looma.data.assistant.ConnectionStatus
import com.example.looma.data.assistant.GeneratorAvailability
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.ModelChoice
import com.example.looma.data.assistant.ModelSource
import com.example.looma.data.assistant.ThinkingEffort
import com.example.looma.data.assistant.api.ApiModelProvider
import com.example.looma.data.assistant.api.KtorApiClient
import com.example.looma.data.assistant.apiModelId
import com.example.looma.data.assistant.firebase.FirebaseHybridModelProvider
import com.example.looma.data.assistant.runtime.AgentEvent
import com.example.looma.data.assistant.runtime.AgentRequest
import com.example.looma.data.assistant.runtime.AgentResponse
import com.example.looma.data.assistant.runtime.AgentRuntimeExecutor
import com.example.looma.data.assistant.runtime.AgentStep
import com.example.looma.data.assistant.runtime.LlmResult
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.example.looma.data.assistant.sessions.CompactionResult
import com.example.looma.data.assistant.sessions.DefaultLlmSessionManager
import com.example.looma.data.assistant.sessions.LlmSessionManager
import com.example.looma.data.assistant.tools.DefaultToolsProvider
import com.example.looma.data.assistant.tools.ToolsProvider
import com.example.looma.data.settings.AppSettings
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import com.example.looma.feature.ask.components.AskModelSheet
import com.example.looma.ui.theme.LoomaTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AskModelSheetReproTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun sheetAlone_scrollingToUnavailableHybridDoesNotFreezeSheetInteractions() {
        val hybridModel = AnswerModel(
            id = FirebaseHybridModelProvider.MODEL_ID,
            source = ModelSource.Hybrid,
            efforts = ThinkingEffort.entries,
            defaultEffort = ThinkingEffort.Off,
            availability = GeneratorAvailability.Unavailable(),
            supportedRoutes = listOf(
                InferenceRoute.PreferOnDevice,
                InferenceRoute.OnDeviceOnly,
                InferenceRoute.PreferCloud,
                InferenceRoute.CloudOnly,
            ),
            defaultRoute = InferenceRoute.PreferOnDevice,
        )
        val apiModel = AnswerModel(
            id = "api:conn1:gpt-4o",
            source = ModelSource.Api,
            efforts = ThinkingEffort.entries,
            defaultEffort = ThinkingEffort.Medium,
            availability = GeneratorAvailability.Ready,
            supportedRoutes = listOf(InferenceRoute.CloudOnly),
            defaultRoute = InferenceRoute.CloudOnly,
            displayName = "gpt-4o",
            description = "OpenAI",
        )
        val models = listOf(hybridModel, apiModel)
        var choice by mutableStateOf<ModelChoice?>(
            ModelChoice(apiModel, apiModel.defaultEffort, apiModel.defaultRoute),
        )
        var manageModelsClicked = false

        rule.setContent {
            LoomaTheme(dynamicColor = false) {
                AskModelSheet(
                    models = models,
                    choice = choice,
                    onSelect = { selected ->
                        val effort = choice?.effort?.takeIf { it in selected.efforts } ?: selected.defaultEffort
                        choice = ModelChoice(selected, effort, selected.defaultRoute)
                    },
                    onEffort = { effort ->
                        choice = choice?.copy(effort = effort)
                    },
                    onRoute = { route ->
                        choice = choice?.copy(route = route)
                    },
                    onPrepareModel = {},
                    onManageModels = { manageModelsClicked = true },
                    onDismiss = {},
                )
            }
        }

        val hybridLabel = context.getString(R.string.ask_model_hybrid)
        val autoRouteLabel = context.getString(R.string.ask_route_prefer_on_device)
        val deviceRouteLabel = context.getString(R.string.ask_route_on_device_only)
        val manageModelsLabel = context.getString(R.string.models_manage)

        rule.waitForIdle()
        rule.onNodeWithText("gpt-4o").assertIsDisplayed()

        // Scroll the wheel down to bring Hybrid (index 0) to center.
        rule.onNodeWithText("gpt-4o").performTouchInput {
            down(center)
            moveBy(Offset(0f, 180f), delayMillis = 300L)
            up()
        }
        rule.waitForIdle()

        assertEquals(hybridModel.id, choice?.model?.id)
        rule.onNodeWithText(autoRouteLabel).assertIsDisplayed()

        // Click a route button while Hybrid is selected.
        rule.onNodeWithText(deviceRouteLabel).performClick()
        rule.waitForIdle()
        val routeChanged = choice?.route == InferenceRoute.OnDeviceOnly

        // Scroll back up to gpt-4o.
        rule.onNodeWithText(hybridLabel).performTouchInput {
            swipeUp()
        }
        rule.waitForIdle()
        val scrolledBack = choice?.model?.id == apiModel.id

        // Click Manage models.
        rule.onNodeWithText(manageModelsLabel).performClick()
        rule.waitForIdle()
        assertTrue(
            "Sheet froze after scrolling to Hybrid: routeChanged=$routeChanged, scrolledBack=$scrolledBack, manageModelsClicked=$manageModelsClicked",
            routeChanged && scrolledBack && manageModelsClicked,
        )
    }

    @Test
    fun askScreen_multipleApiModels_slowDragToUnavailableHybridDoesNotFreezeSheet() {
        val connection = ApiConnection(
            id = "conn1",
            name = "OpenAI",
            provider = ApiProvider.OpenAiCompatible,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            status = ConnectionStatus.Ok,
            models = listOf(
                ApiModel(id = "gpt-4o", enabled = true),
                ApiModel(id = "claude-3.5", enabled = true),
                ApiModel(id = "gemini-2.5", enabled = true),
            ),
        )
        val gptId = apiModelId(connection.id, "gpt-4o")
        val hybridProvider = FirebaseHybridModelProvider(firebaseConfigured = false)
        val apiProvider = ApiModelProvider(
            connections = MutableStateFlow(listOf(connection)),
            client = KtorApiClient(HttpClient(OkHttp)),
        )
        val answerModels = AnswerModels(listOf(hybridProvider, apiProvider))
        val settingsFlow = MutableStateFlow(
            AppSettings(
                askModelId = gptId,
                askEffort = ThinkingEffort.Medium.name,
                askRoute = InferenceRoute.CloudOnly.name,
            ),
        )
        val modelSettings = object : AskModelSettings {
            override val settings: Flow<AppSettings> = settingsFlow
            override suspend fun setAskModel(choice: ModelChoice) {
                settingsFlow.value = settingsFlow.value.copy(
                    askModelId = choice.model.id,
                    askEffort = choice.effort.name,
                    askRoute = choice.route?.name,
                )
            }
        }
        val executor = object : AgentRuntimeExecutor {
            override val sessionManager: LlmSessionManager = DefaultLlmSessionManager()
            override suspend fun execute(
                backend: LlmRuntimeBackend,
                request: AgentRequest,
                tools: ToolsProvider?,
                onStep: (AgentStep) -> Unit,
            ): LlmResult<AgentResponse> = error("unused")
            override fun executeStream(
                backend: LlmRuntimeBackend,
                request: AgentRequest,
                tools: ToolsProvider?,
            ): Flow<AgentEvent> = flow {}
            override suspend fun compactContext(): CompactionResult = CompactionResult(compacted = false)
            override suspend fun interrupt() {}
            override suspend fun resetSession() {}
            override suspend fun cleanUp() {}
        }
        val vault = Vault(id = VaultId("v1"), name = "TestVault", rootPath = "/tmp/vault")
        val viewModel = AskViewModel(
            vault = vault,
            executor = executor,
            tools = DefaultToolsProvider(emptyList()),
            models = answerModels,
            noteSaver = AskNoteSaver { v, title, _ ->
                Note(
                    id = NoteId(v.id, NotePath("$title.md")),
                    title = title,
                    content = "",
                    modifiedAt = Instant.EPOCH,
                )
            },
            modelSettings = modelSettings,
        )
        var manageModelsClicked = false

        rule.setContent {
            LoomaTheme(dynamicColor = false) {
                AskScreen(
                    model = viewModel,
                    navigateUp = {},
                    openNote = {},
                    manageModels = { manageModelsClicked = true },
                )
            }
        }

        val hybridLabel = context.getString(R.string.ask_model_hybrid)
        val autoRouteLabel = context.getString(R.string.ask_route_prefer_on_device)
        val deviceRouteLabel = context.getString(R.string.ask_route_on_device_only)
        val offEffortLabel = context.getString(R.string.ask_effort_off)
        val navBackLabel = context.getString(R.string.nav_back)
        val manageModelsLabel = context.getString(R.string.models_manage)

        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("gpt-4o").assertIsDisplayed().performClick()
        rule.waitUntil(2_000) {
            rule.onAllNodesWithText("gpt-4o").fetchSemanticsNodes().size >= 2
        }
        rule.waitForIdle()

        // Slow drag down to bring Hybrid (index 0) to center.
        rule.onAllNodesWithText("gpt-4o")[1].performTouchInput {
            down(center)
            moveBy(Offset(0f, 180f), delayMillis = 300L)
            up()
        }
        rule.waitForIdle()

        assertEquals(FirebaseHybridModelProvider.MODEL_ID, viewModel.state.value.choice?.model?.id)
        rule.onNodeWithText(autoRouteLabel).assertIsDisplayed()

        // Click route button while Hybrid is selected.
        rule.onNodeWithText(deviceRouteLabel).performClick()
        rule.waitForIdle()
        val routeChanged = viewModel.state.value.choice?.route == InferenceRoute.OnDeviceOnly

        // Scroll back to gpt-4o.
        rule.onAllNodesWithText(hybridLabel)[1].performTouchInput {
            swipeUp()
        }
        rule.waitForIdle()
        val scrolledBack = viewModel.state.value.choice?.model?.id == gptId

        // Click Manage models.
        rule.onNodeWithText(manageModelsLabel).performClick()
        rule.waitForIdle()
        assertTrue(
            "Sheet froze after scrolling to Hybrid: routeChanged=$routeChanged, scrolledBack=$scrolledBack, manageModelsClicked=$manageModelsClicked",
            routeChanged && scrolledBack && manageModelsClicked,
        )
    }
}
