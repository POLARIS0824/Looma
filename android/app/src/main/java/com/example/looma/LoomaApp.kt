package com.example.looma

import android.app.Application
import com.example.looma.data.assistant.AnswerModels
import com.example.looma.data.assistant.LocalGenerator
import com.example.looma.data.assistant.LoomaModelSettingsRepository
import com.example.looma.data.assistant.ModelSettingsRepository
import com.example.looma.data.assistant.VaultAgent
import com.example.looma.data.assistant.VaultEvidence
import com.example.looma.data.assistant.WeaveFeedbackStore
import com.example.looma.data.assistant.api.ApiConnectionStore
import com.example.looma.data.assistant.api.ApiModelProvider
import com.example.looma.data.assistant.api.KtorApiClient
import com.example.looma.data.folder.FolderRepository
import com.example.looma.data.image.ImageUploader
import com.example.looma.data.index.NoteIndexDatabase
import com.example.looma.data.index.RoomNoteIndex
import com.example.looma.data.inference.LocalModelSession
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.s3.createLoomaHttpClient
import com.example.looma.data.search.SearchHistoryRepository
import com.example.looma.data.search.SemanticSearchRepository
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.data.sync.SyncRepository
import com.example.looma.data.vault.VaultRepository
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.size.Precision

class LoomaApp : Application(), SingletonImageLoader.Factory {
    val vaultRepository by lazy { VaultRepository(this) }
    private val indexDatabase by lazy { NoteIndexDatabase.get(this) }
    internal val httpClient by lazy { createLoomaHttpClient() }
    val noteRepository: NoteRepository by lazy {
        NoteRepository(
            vaultRepository,
            RoomNoteIndex(indexDatabase) { delta ->
                try {
                    semanticSearchRepository.recordChanges(delta)
                } catch (error: Exception) {
                    android.util.Log.w("SemanticIndex", "Could not record derived index changes", error)
                }
            },
            onFilesChanged = { vault, delta -> updateSemanticIndex { semanticSearchRepository.filesChanged(vault, delta) } },
            onVaultScanned = { vault, partial ->
                updateSemanticIndex { semanticSearchRepository.resumeUpdates(vault, partial) }
            },
        )
    }
    val searchHistoryRepository by lazy {
        SearchHistoryRepository(vaultRepository, indexDatabase.searchHistoryDao())
    }
    val settingsRepository by lazy { SettingsRepository(this) }
    private val modelSession = LocalModelSession()
    val semanticSearchRepository by lazy {
        SemanticSearchRepository(this, noteRepository, vaultRepository, settingsRepository, modelSession)
    }
    private val weaveFeedbackStore by lazy { WeaveFeedbackStore(this) }
    internal val evidence by lazy { VaultEvidence(vaultRepository, noteRepository, semanticSearchRepository, weaveFeedbackStore) }
    private val localGenerator by lazy { LocalGenerator(this, modelSession) }
    private val apiConnectionStore by lazy { ApiConnectionStore(this) }
    private val ktorApiClient by lazy { KtorApiClient(httpClient) }
    internal val modelSettingsRepository: ModelSettingsRepository by lazy {
        LoomaModelSettingsRepository(apiConnectionStore, ktorApiClient, localGenerator)
    }
    private val apiModelProvider by lazy { ApiModelProvider(modelSettingsRepository.connections, ktorApiClient) }
    internal val answerModels by lazy {
        AnswerModels(listOf(localGenerator, apiModelProvider), localImporter = localGenerator::install)
    }
    internal val vaultAgent by lazy { VaultAgent(evidence) }
    val folderRepository by lazy { FolderRepository(noteRepository) }
    val imageUploader by lazy { ImageUploader(this, vaultRepository, httpClient) }
    val syncRepository by lazy {
        SyncRepository(
            this,
            vaultRepository,
            settingsRepository,
            noteRepository,
            indexDatabase.syncStateDao(),
            httpClient,
        )
    }

    private suspend fun updateSemanticIndex(action: suspend () -> Unit) {
        try {
            action()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("SemanticIndex", "Could not schedule derived index maintenance", error)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .precision(Precision.INEXACT)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .build()
            }
            .build()
}
