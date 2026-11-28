package com.example.looma.data.assistant.firebase

import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.GeneratorAvailability
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.LlmBackendProvider
import com.example.looma.data.assistant.ModelSource
import com.example.looma.data.assistant.ThinkingEffort
import com.example.looma.data.assistant.runtime.LlmRuntimeBackend
import com.google.firebase.Firebase
import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.OnDeviceExtension
import com.google.firebase.ai.OnDeviceModelStatus
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(PublicPreviewAPI::class)
internal class FirebaseHybridModelProvider(
    private val firebaseConfigured: Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : LlmBackendProvider {
    private val prepareMutex = Mutex()
    private val generators = ConcurrentHashMap<InferenceRoute, FirebaseHybridGenerator>()
    private val onDeviceExtension: OnDeviceExtension? by lazy {
        if (!firebaseConfigured) {
            null
        } else {
            try {
                Firebase.ai(backend = GenerativeBackend.googleAI())
                    .generativeModel(HybridRoutingPolicy.CLOUD_FALLBACK_MODEL)
                    .onDeviceExtension
            } catch (_: Exception) {
                null
            }
        }
    }
    private val mutableModels = MutableStateFlow(
        if (firebaseConfigured) {
            listOf(buildModel(GeneratorAvailability.Ready))
        } else {
            emptyList()
        }
    )
    override val models: StateFlow<List<AnswerModel>> = mutableModels.asStateFlow()

    override fun refresh() {
        scope.launch {
            refreshStatus()
        }
    }

    override fun backend(model: AnswerModel, route: InferenceRoute?): LlmRuntimeBackend? {
        if (!firebaseConfigured || model.id != MODEL_ID) return null
        val effectiveRoute = route ?: InferenceRoute.PreferOnDevice
        return generators.getOrPut(effectiveRoute) { FirebaseHybridGenerator(effectiveRoute) }
    }

    override suspend fun prepare(model: AnswerModel): Result<Unit> {
        if (model.id != MODEL_ID) {
            return Result.failure(IllegalArgumentException("Unknown model: ${model.id}"))
        }
        val extension = onDeviceExtension ?: return if (firebaseConfigured) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Firebase not configured"))
        }
        return prepareMutex.withLock {
            try {
                when (extension.checkStatus()) {
                    OnDeviceModelStatus.AVAILABLE -> {
                        warmUpSafely(extension)
                        updateAvailability(GeneratorAvailability.Ready)
                        Result.success(Unit)
                    }
                    OnDeviceModelStatus.DOWNLOADABLE -> {
                        var totalBytes = 0L
                        extension.download().collect { status ->
                            when (status) {
                                is DownloadStatus.DownloadStarted -> {
                                    totalBytes = status.bytesToDownload
                                    updateAvailability(GeneratorAvailability.Downloading(0f))
                                }
                                is DownloadStatus.DownloadInProgress -> {
                                    val progress = if (totalBytes > 0L) {
                                        (status.totalBytesDownloaded.toFloat() / totalBytes.toFloat())
                                            .coerceIn(0f, 1f)
                                    } else {
                                        null
                                    }
                                    updateAvailability(GeneratorAvailability.Downloading(progress))
                                }
                                is DownloadStatus.DownloadCompleted -> {
                                    warmUpSafely(extension)
                                    updateAvailability(GeneratorAvailability.Ready)
                                }
                                is DownloadStatus.DownloadFailed -> {
                                    updateAvailability(GeneratorAvailability.Unavailable(status.exception.message))
                                    throw status.exception
                                }
                            }
                        }
                        Result.success(Unit)
                    }
                    OnDeviceModelStatus.DOWNLOADING -> {
                        updateAvailability(GeneratorAvailability.Downloading())
                        Result.success(Unit)
                    }
                    OnDeviceModelStatus.UNAVAILABLE -> {
                        updateAvailability(
                            if (firebaseConfigured) {
                                GeneratorAvailability.Ready
                            } else {
                                GeneratorAvailability.Unavailable()
                            }
                        )
                        Result.success(Unit)
                    }
                    else -> Result.success(Unit)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }

    private suspend fun refreshStatus() {
        if (!firebaseConfigured) {
            mutableModels.value = emptyList()
            return
        }
        val extension = onDeviceExtension
        if (extension == null) {
            updateAvailability(
                if (firebaseConfigured) GeneratorAvailability.Ready else GeneratorAvailability.Unavailable()
            )
            return
        }
        try {
            when (extension.checkStatus()) {
                OnDeviceModelStatus.AVAILABLE -> {
                    warmUpSafely(extension)
                    updateAvailability(GeneratorAvailability.Ready)
                }
                OnDeviceModelStatus.DOWNLOADABLE ->
                    updateAvailability(GeneratorAvailability.Downloadable())
                OnDeviceModelStatus.DOWNLOADING ->
                    updateAvailability(GeneratorAvailability.Downloading())
                OnDeviceModelStatus.UNAVAILABLE ->
                    updateAvailability(
                        if (firebaseConfigured) {
                            GeneratorAvailability.Ready
                        } else {
                            GeneratorAvailability.Unavailable()
                        }
                    )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            updateAvailability(
                if (firebaseConfigured) {
                    GeneratorAvailability.Ready
                } else {
                    GeneratorAvailability.Unavailable(error.message)
                }
            )
        }
    }

    private suspend fun warmUpSafely(extension: OnDeviceExtension) {
        try {
            extension.warmUp()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
        }
    }

    private fun updateAvailability(availability: GeneratorAvailability) {
        mutableModels.value = listOf(buildModel(availability))
    }

    private fun buildModel(availability: GeneratorAvailability) = AnswerModel(
        id = MODEL_ID,
        source = ModelSource.Hybrid,
        efforts = ThinkingEffort.entries,
        defaultEffort = ThinkingEffort.Off,
        availability = availability,
        supportedRoutes = SUPPORTED_ROUTES,
        defaultRoute = InferenceRoute.PreferOnDevice,
    )

    companion object {
        const val MODEL_ID = "firebase-hybrid"
        private val SUPPORTED_ROUTES = listOf(
            InferenceRoute.PreferOnDevice,
            InferenceRoute.OnDeviceOnly,
            InferenceRoute.PreferCloud,
            InferenceRoute.CloudOnly,
        )
    }
}
