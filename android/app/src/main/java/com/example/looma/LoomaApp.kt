package com.example.looma

import android.app.Application
import com.example.looma.data.folder.FolderRepository
import com.example.looma.data.image.ImageUploader
import com.example.looma.data.index.NoteIndexDatabase
import com.example.looma.data.index.RoomNoteIndex
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.search.SearchHistoryRepository
import com.example.looma.data.search.SemanticSearchRepository
import com.example.looma.data.settings.SettingsRepository
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
    val noteRepository: NoteRepository by lazy {
        NoteRepository(
            vaultRepository,
            RoomNoteIndex(indexDatabase),
            onFilesChanged = { vault, delta -> updateSemanticIndex { semanticSearchRepository.filesChanged(vault, delta) } },
            onVaultScanned = { vault -> updateSemanticIndex { semanticSearchRepository.resumeUpdates(vault) } },
        )
    }
    val searchHistoryRepository by lazy {
        SearchHistoryRepository(vaultRepository, indexDatabase.searchHistoryDao())
    }
    val semanticSearchRepository by lazy { SemanticSearchRepository(this, noteRepository, vaultRepository) }
    val folderRepository by lazy { FolderRepository(noteRepository) }
    val settingsRepository by lazy { SettingsRepository(this) }
    val imageUploader by lazy { ImageUploader(this, vaultRepository) }

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
