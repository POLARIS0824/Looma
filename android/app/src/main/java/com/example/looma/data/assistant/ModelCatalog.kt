package com.example.looma.data.assistant

import android.net.Uri
import com.example.looma.data.assistant.api.ApiConnectionStore
import com.example.looma.data.assistant.api.ApiResult
import com.example.looma.data.assistant.api.KtorApiClient
import com.example.looma.data.assistant.api.StoredApiState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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

internal interface ModelCatalogStore {
    val state: Flow<StoredApiState>
    suspend fun saveConnections(connections: List<ApiConnection>)
    suspend fun saveDefaultModelId(modelId: String?)
}

internal interface LocalModelSource {
    val installed: Boolean
    val installedBytes: Long
    suspend fun install(uri: Uri)
}

internal class LoomaModelSettingsRepository(
    private val store: ModelCatalogStore,
    private val apiClient: KtorApiClient,
    private val generator: LocalModelSource,
    private val onDefaultModelChanged: suspend (String?) -> Unit = {},
) : ModelSettingsRepository {

    constructor(
        store: ApiConnectionStore,
        apiClient: KtorApiClient,
        generator: LocalGenerator,
        onDefaultModelChanged: suspend (String?) -> Unit = {},
    ) : this(
        store = object : ModelCatalogStore {
            override val state: Flow<StoredApiState> = store.state
            override suspend fun saveConnections(connections: List<ApiConnection>) = store.saveConnections(connections)
            override suspend fun saveDefaultModelId(modelId: String?) = store.saveDefaultModelId(modelId)
        },
        apiClient = apiClient,
        generator = object : LocalModelSource {
            override val installed: Boolean get() = generator.installed
            override val installedBytes: Long get() = generator.installedBytes
            override suspend fun install(uri: Uri) = generator.install(uri)
        },
        onDefaultModelChanged = onDefaultModelChanged,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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

    override fun download(id: String) = Unit

    override fun cancelDownload(id: String) = Unit

    override fun deleteLocal(id: String) {
        if (id == AnswerModels.LocalModel.id) return
        if (mutableDefault.value == id) {
            mutableDefault.value = null
            scope.launch {
                store.saveDefaultModelId(null)
                onDefaultModelChanged(null)
            }
        }
    }

    override fun importLocal(uri: Uri) {
        importLocalWith { generator.install(uri) }
    }

    internal fun importLocalWith(installAction: suspend () -> Unit) {
        if (mutableImport.value == LocalImport.Running) return
        mutableImport.value = LocalImport.Running
        scope.launch {
            val result = runCatching { installAction() }
            mutableLocal.value = catalog(imported())
            mutableImport.value = if (result.isSuccess) LocalImport.Idle else LocalImport.Failed
            if (result.isSuccess && mutableDefault.value == null) {
                val localId = AnswerModels.LocalModel.id
                mutableDefault.value = localId
                store.saveDefaultModelId(localId)
                onDefaultModelChanged(localId)
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
            if (defaultCleared) {
                store.saveDefaultModelId(defaultSnapshot)
                onDefaultModelChanged(defaultSnapshot)
            }
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
            if (defaultCleared) {
                store.saveDefaultModelId(defaultSnapshot)
                onDefaultModelChanged(defaultSnapshot)
            }
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
        scope.launch {
            store.saveDefaultModelId(modelId)
            onDefaultModelChanged(modelId)
        }
    }

    private fun updateConnection(id: String, change: (ApiConnection) -> ApiConnection) {
        mutableConnections.update { list -> list.map { if (it.id == id) change(it) else it } }
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

private fun catalog(imported: LocalModelEntry?): List<LocalModelEntry> = listOfNotNull(imported)
