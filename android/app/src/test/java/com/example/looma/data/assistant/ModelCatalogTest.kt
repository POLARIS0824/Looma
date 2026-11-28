package com.example.looma.data.assistant

import android.net.Uri
import com.example.looma.data.assistant.api.KtorApiClient
import com.example.looma.data.assistant.api.StoredApiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelCatalogTest {

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
    fun `localModels contains no fake catalog entries when no local model is imported`() = runTest {
        val repo = createRepository(installed = false)

        assertTrue(repo.localModels.value.isEmpty())
        assertNull(repo.defaultModelId.value)
    }

    @Test
    fun `localModels contains only imported local model when installed`() = runTest {
        val repo = createRepository(installed = true, installedBytes = 4096L)

        assertEquals(1, repo.localModels.value.size)
        assertEquals(AnswerModels.LocalModel.id, repo.localModels.value.single().id)
        assertEquals(4096L, repo.localModels.value.single().sizeBytes)
        assertEquals(AnswerModels.LocalModel.id, repo.defaultModelId.value)
    }

    @Test
    fun `download does not populate fake ready state or defaultModelId`() = runTest {
        val notified = mutableListOf<String?>()
        val repo = createRepository(installed = false, onDefaultModelChanged = { notified += it })

        repo.download("gemma-3n-e2b")

        assertTrue(repo.localModels.value.isEmpty())
        assertNull(repo.defaultModelId.value)
        assertTrue(notified.isEmpty())
    }

    @Test
    fun `setDefault and importLocal notify onDefaultModelChanged`() = runTest {
        val notified = mutableListOf<String?>()
        val store = FakeModelCatalogStore()
        val generator = FakeLocalModelSource(installed = false)
        val repo = LoomaModelSettingsRepository(
            store = store,
            apiClient = KtorApiClient(HttpClient(MockEngine { respondOk() })),
            generator = generator,
            onDefaultModelChanged = { notified += it },
        )

        repo.importLocalWith { generator.installed = true }

        assertEquals(AnswerModels.LocalModel.id, repo.defaultModelId.value)
        assertEquals(listOf<String?>(AnswerModels.LocalModel.id), notified)

        repo.setDefault("api:conn-1:gpt-4o")

        assertEquals("api:conn-1:gpt-4o", repo.defaultModelId.value)
        assertEquals(listOf<String?>(AnswerModels.LocalModel.id, "api:conn-1:gpt-4o"), notified)
    }

    private fun createRepository(
        installed: Boolean,
        installedBytes: Long = 1024L,
        onDefaultModelChanged: suspend (String?) -> Unit = {},
    ): LoomaModelSettingsRepository = LoomaModelSettingsRepository(
        store = FakeModelCatalogStore(),
        apiClient = KtorApiClient(HttpClient(MockEngine { respondOk() })),
        generator = FakeLocalModelSource(installed = installed, installedBytes = installedBytes),
        onDefaultModelChanged = onDefaultModelChanged,
    )

    private class FakeModelCatalogStore : ModelCatalogStore {
        private val mutableState = MutableStateFlow(StoredApiState())
        override val state: Flow<StoredApiState> = mutableState
        var savedDefaultModelId: String? = null

        override suspend fun saveConnections(connections: List<ApiConnection>) {
            mutableState.value = mutableState.value.copy(connections = connections)
        }

        override suspend fun saveDefaultModelId(modelId: String?) {
            savedDefaultModelId = modelId
            mutableState.value = mutableState.value.copy(defaultModelId = modelId)
        }
    }

    private class FakeLocalModelSource(
        override var installed: Boolean,
        override var installedBytes: Long = 1024L,
    ) : LocalModelSource {
        override suspend fun install(uri: Uri) {
            installed = true
        }
    }
}
