package com.example.looma.data.assistant

import android.net.Uri
import com.example.looma.data.assistant.api.ApiConnectionStore
import com.example.looma.data.assistant.api.ApiResult
import com.example.looma.data.assistant.api.KtorApiClient
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface LocalModelState {
    data object NotDownloaded : LocalModelState
    data class Downloading(val fraction: Float) : LocalModelState
    data object Ready : LocalModelState
}

internal data class LocalModelEntry(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val description: String?,
    val recommended: Boolean,
    val state: LocalModelState,
)

internal enum class ApiProvider(val defaultBaseUrl: String) {
    OpenAiCompatible("https://api.openai.com/v1"),
    Anthropic("https://api.anthropic.com"),
    Gemini("https://generativelanguage.googleapis.com"),
    Custom(""),
}

internal sealed interface ConnectionStatus {
    data object Unchecked : ConnectionStatus
    data object Checking : ConnectionStatus
    data object Ok : ConnectionStatus
    data object Failed : ConnectionStatus
}

internal data class ApiModel(val id: String, val enabled: Boolean)

internal data class ApiConnection(
    val id: String,
    val name: String,
    val provider: ApiProvider,
    val baseUrl: String,
    val apiKey: String,
    val status: ConnectionStatus,
    val models: List<ApiModel>,
) {
    companion object {
        fun draft(): ApiConnection = ApiConnection(
            id = UUID.randomUUID().toString(),
            name = "",
            provider = ApiProvider.OpenAiCompatible,
            baseUrl = "",
            apiKey = "",
            status = ConnectionStatus.Unchecked,
            models = emptyList(),
        )
    }
}

internal enum class LocalImport { Idle, Running, Failed }

internal fun apiModelId(connectionId: String, modelId: String): String = "api:$connectionId:$modelId"

internal interface ModelSettingsRepository {
    val localModels: StateFlow<List<LocalModelEntry>>
    val connections: StateFlow<List<ApiConnection>>
    val defaultModelId: StateFlow<String?>
    val localImport: StateFlow<LocalImport>
    fun download(id: String)
    fun cancelDownload(id: String)
    fun deleteLocal(id: String)
    fun importLocal(uri: Uri)
    fun saveConnection(c: ApiConnection)
    fun deleteConnection(id: String)
    fun testConnection(id: String)
    fun fetchModels(id: String)
    fun setDefault(modelId: String)
}

internal class LoomaModelSettingsRepository(
    private val store: ApiConnectionStore,
    private val apiClient: KtorApiClient,
    private val generator: LocalGenerator,
) : ModelSettingsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloads = mutableMapOf<String, Job>()
    private val connectionJobs = mutableMapOf<String, Job>()
    private val mutableLocal = MutableStateFlow(catalog(imported()))
    private val mutableConnections = MutableStateFlow<List<ApiConnection>>(emptyList())
    private val mutableDefault = MutableStateFlow(AnswerModels.LocalModel.id.takeIf { generator.installed })
    private val mutableImport = MutableStateFlow(LocalImport.Idle)

    override val localModels = mutableLocal.asStateFlow()
    override val connections = mutableConnections.asStateFlow()
    override val defaultModelId = mutableDefault.asStateFlow()
    override val localImport = mutableImport.asStateFlow()

    init {
        scope.launch {
            val stored = store.state.first()
            mutableConnections.value = stored.connections
            mutableDefault.value = stored.defaultModelId ?: AnswerModels.LocalModel.id.takeIf { generator.installed }
            clearMissingDefault()
        }
    }

    override fun download(id: String) {
        if (downloads[id]?.isActive == true) return
        downloads[id] = scope.launch {
            for (step in 0..20) {
                setLocalState(id, LocalModelState.Downloading(step / 20f))
                delay(250)
            }
            setLocalState(id, LocalModelState.Ready)
            if (mutableDefault.value == null) {
                mutableDefault.value = id
                store.saveDefaultModelId(id)
            }
        }
    }

    override fun cancelDownload(id: String) {
        downloads.remove(id)?.cancel()
        setLocalState(id, LocalModelState.NotDownloaded)
    }

    override fun deleteLocal(id: String) {
        if (id == AnswerModels.LocalModel.id) return
        setLocalState(id, LocalModelState.NotDownloaded)
        if (mutableDefault.value == id) {
            mutableDefault.value = null
            scope.launch { store.saveDefaultModelId(null) }
        }
    }

    override fun importLocal(uri: Uri) {
        if (mutableImport.value == LocalImport.Running) return
        mutableImport.value = LocalImport.Running
        scope.launch {
            val result = runCatching { generator.install(uri) }
            mutableLocal.value = catalog(imported(), mutableLocal.value)
            mutableImport.value = if (result.isSuccess) LocalImport.Idle else LocalImport.Failed
            if (result.isSuccess && mutableDefault.value == null) {
                val localId = AnswerModels.LocalModel.id
                mutableDefault.value = localId
                store.saveDefaultModelId(localId)
            }
        }
    }

    override fun saveConnection(c: ApiConnection) {
        mutableConnections.update { list ->
            val previous = list.firstOrNull { it.id == c.id }
            val changed = previous == null || previous.provider != c.provider ||
                previous.baseUrl != c.baseUrl || previous.apiKey != c.apiKey
            val saved = if (changed && previous != null) c.copy(status = ConnectionStatus.Unchecked) else c
            if (previous == null) list + saved else list.map { if (it.id == c.id) saved else it }
        }
        val defaultCleared = clearMissingDefault()
        val snapshot = mutableConnections.value
        val defaultSnapshot = mutableDefault.value
        scope.launch {
            store.saveConnections(snapshot)
            if (defaultCleared) store.saveDefaultModelId(defaultSnapshot)
        }
    }

    override fun deleteConnection(id: String) {
        connectionJobs.remove(id)?.cancel()
        mutableConnections.update { list -> list.filterNot { it.id == id } }
        val defaultCleared = clearMissingDefault()
        val snapshot = mutableConnections.value
        val defaultSnapshot = mutableDefault.value
        scope.launch {
            store.saveConnections(snapshot)
            if (defaultCleared) store.saveDefaultModelId(defaultSnapshot)
        }
    }

    override fun testConnection(id: String) {
        connectionJobs.remove(id)?.cancel()
        val connection = mutableConnections.value.firstOrNull { it.id == id } ?: return
        connectionJobs[id] = scope.launch {
            updateConnection(id) { it.copy(status = ConnectionStatus.Checking) }
            val nextStatus = when (apiClient.testConnection(connection)) {
                is ApiResult.Success -> ConnectionStatus.Ok
                is ApiResult.Failure -> ConnectionStatus.Failed
            }
            updateConnection(id) { it.copy(status = nextStatus) }
            store.saveConnections(mutableConnections.value)
        }
    }

    override fun fetchModels(id: String) {
        connectionJobs.remove(id)?.cancel()
        val connection = mutableConnections.value.firstOrNull { it.id == id } ?: return
        connectionJobs[id] = scope.launch {
            updateConnection(id) { it.copy(status = ConnectionStatus.Checking) }
            when (val result = apiClient.listModels(connection)) {
                is ApiResult.Success -> {
                    val ids = result.value
                    val fetchedSet = ids.toHashSet()
                    updateConnection(id) { current ->
                        val known = current.models.associateBy { it.id }
                        val hadEnabled = current.models.any { it.enabled }
                        val fetched = ids.mapIndexed { index, modelId ->
                            known[modelId] ?: ApiModel(
                                id = modelId,
                                enabled = !hadEnabled && (ids.size <= 5 || index < 3),
                            )
                        }
                        val custom = current.models.filter { it.id !in fetchedSet }
                        current.copy(status = ConnectionStatus.Ok, models = fetched + custom)
                    }
                }
                is ApiResult.Failure -> {
                    updateConnection(id) { it.copy(status = ConnectionStatus.Failed) }
                }
            }
            store.saveConnections(mutableConnections.value)
        }
    }

    override fun setDefault(modelId: String) {
        mutableDefault.value = modelId
        scope.launch { store.saveDefaultModelId(modelId) }
    }

    private fun updateConnection(id: String, change: (ApiConnection) -> ApiConnection) {
        mutableConnections.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun setLocalState(id: String, state: LocalModelState) {
        mutableLocal.update { list -> list.map { if (it.id == id) it.copy(state = state) else it } }
    }

    private fun clearMissingDefault(): Boolean {
        val current = mutableDefault.value ?: return false
        if (!current.startsWith("api:")) return false
        val exists = mutableConnections.value.any { connection ->
            connection.models.any { it.enabled && apiModelId(connection.id, it.id) == current }
        }
        if (!exists) {
            mutableDefault.value = null
            return true
        }
        return false
    }

    private fun imported(): LocalModelEntry? = if (generator.installed) {
        LocalModelEntry(
            id = AnswerModels.LocalModel.id,
            name = "",
            sizeBytes = generator.installedBytes,
            description = null,
            recommended = false,
            state = LocalModelState.Ready,
        )
    } else {
        null
    }
}

private fun catalog(imported: LocalModelEntry?, current: List<LocalModelEntry> = emptyList()): List<LocalModelEntry> {
    val states = current.associate { it.id to it.state }
    val entries = listOf(
        LocalModelEntry("gemma-3n-e2b", "Gemma 3n E2B", 1_300_000_000L, null, recommended = true, LocalModelState.NotDownloaded),
        LocalModelEntry("gemma-3n-e4b", "Gemma 3n E4B", 2_900_000_000L, null, recommended = false, LocalModelState.NotDownloaded),
        LocalModelEntry("qwen3-1.7b", "Qwen3 1.7B", 1_700_000_000L, null, recommended = false, LocalModelState.NotDownloaded),
    ).map { entry -> states[entry.id]?.let { entry.copy(state = it) } ?: entry }
    return listOfNotNull(imported) + entries
}
