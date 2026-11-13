package com.example.looma.feature.note

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.image.ImageUpload
import com.example.looma.data.image.ImageUploadException
import com.example.looma.data.image.ImageUploadFailure
import com.example.looma.data.image.ImageUploader
import com.example.looma.data.note.ConflictCopy
import com.example.looma.data.note.NoteChangedExternallyException
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.note.RelocationStalledException
import com.example.looma.data.note.VaultFileException
import com.example.looma.data.note.VaultFileFailure
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteWriteMode
import com.example.looma.domain.wiki.WikiContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NoteViewModel(
    private val repository: NoteRepository,
    private val settingsRepository: SettingsRepository,
    private val imageUploader: ImageUploader,
    initialId: NoteId,
    isNewNote: Boolean,
) : ViewModel() {
    private var currentId: NoteId = initialId
    private var retargetVersion = 0L
    private val _uiState = MutableStateFlow(NoteUiState(isNewNote = isNewNote))
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    private data class SaveRequest(val content: String, val mode: NoteWriteMode)

    private data class EditorBuffer(
        val reloadToken: Int,
        val read: () -> String,
        val replace: (String) -> Unit,
    )

    private var editorBuffer: EditorBuffer? = null
    private val editorWrites = Mutex()
    private var operationGeneration = 0
    private var loadJob: Job? = null
    private var leaving = false
    private var lastConflictCopy: ConflictCopy? = null
    private val scheduler = SaveScheduler(viewModelScope, onSave = { saveFromScheduler() })

    init {
        load()
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        showInlineTitle = settings.showInlineTitle,
                        toolbarOrder = settings.toolbarOrder,
                    )
                }
            }
        }
    }

    fun load() {
        if (_uiState.value.isDirty || loadJob?.isActive == true) return
        loadJob = enqueue { generation ->
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                val note = repository.openNote(currentId)
                val keepEmptyNotes = settingsRepository.settings.first().keepEmptyNotes
                val wiki = runCatching { repository.wikiContext(currentId) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(WikiContext.Empty)
                val imageContext = runCatching { repository.imageContext(currentId) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                if (generation != operationGeneration) return@enqueue
                lastConflictCopy = null
                _uiState.update {
                    it.copy(
                        content = note.content,
                        savedContent = note.content,
                        modifiedAt = note.modifiedAt,
                        revision = note.revision,
                        conflictRevision = null,
                        isLoading = false,
                        saveState = NoteSaveState.Idle,
                        saveFailed = false,
                        loadFailed = false,
                        isConflict = false,
                        keepEmptyNotes = keepEmptyNotes,
                        fileName = fileNameOf(currentId),
                        wiki = wiki,
                        imageContext = imageContext,
                        conflictCopy = null,
                        reloadToken = it.reloadToken + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == operationGeneration) {
                    _uiState.update { it.copy(isLoading = false, loadFailed = true) }
                }
            }
        }
    }

    fun onRouteStarted(id: NoteId, routeVersion: Long) {
        syncEditorBuffer()
        enqueue {
            val retargeted = routeVersion > retargetVersion && currentId != id
            if (routeVersion > retargetVersion) {
                retargetVersion = routeVersion
                currentId = id
            }
            if (retargeted) {
                _uiState.update {
                    it.copy(
                        fileName = fileNameOf(currentId),
                        isNewNote = false,
                        imageContext = null,
                    )
                }
            }
            try {
                val state = _uiState.value
                if (!state.isLoading && !state.isDirty && !state.isConflict && !state.saveFailed) {
                    val disk = repository.readNote(currentId)
                    syncEditorBuffer()
                    _uiState.update {
                        if (!it.canAdoptDiskRevision(disk.revision)) {
                            it
                        } else {
                            it.copy(
                                content = disk.content,
                                savedContent = disk.content,
                                modifiedAt = disk.modifiedAt,
                                revision = disk.revision,
                                fileName = fileNameOf(currentId),
                                reloadToken = it.reloadToken + 1,
                                saveState = NoteSaveState.Idle,
                                saveFailed = false,
                                isConflict = false,
                            )
                        }
                    }
                }
                if (retargeted) {
                    val context = repository.imageContext(currentId)
                    _uiState.update { it.copy(imageContext = context) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            refreshWiki()
        }
    }

    fun refreshWiki() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            try {
                val wiki = repository.wikiContext(currentId)
                _uiState.update { it.copy(wiki = wiki) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun uploadImage(uri: Uri) {
        if (_uiState.value.isUploadingImage) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingImage = true, imageUploadError = null) }
            try {
                val settings = settingsRepository.settings.first()
                val upload = imageUploader.upload(uri, settings)
                val snippet = when (upload) {
                    is ImageUpload.Attachment -> "![[${upload.fileName}]]"
                    is ImageUpload.Remote -> "![](${upload.url})"
                }
                _uiState.update {
                    it.copy(
                        isUploadingImage = false,
                        imageInsertion = ImageInsertion(snippet, System.nanoTime()),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImageUploadException) {
                _uiState.update {
                    it.copy(isUploadingImage = false, imageUploadError = e.toNoteError())
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isUploadingImage = false, imageUploadError = NoteImageError.Unknown)
                }
            }
        }
    }

    fun onImageInserted() {
        _uiState.update { it.copy(imageInsertion = null) }
    }

    fun onImageErrorShown() {
        _uiState.update { it.copy(imageUploadError = null) }
    }

    internal fun bindEditorBuffer(
        reloadToken: Int,
        read: () -> String,
        replace: (String) -> Unit,
    ): () -> Unit {
        val buffer = EditorBuffer(reloadToken, read, replace)
        editorBuffer = buffer
        return {
            if (editorBuffer === buffer) {
                onContentChanged(buffer.read(), buffer.reloadToken)
                editorBuffer = null
            }
        }
    }

    fun onContentChanged(value: String, reloadToken: Int) {
        val current = _uiState.value
        if (current.reloadToken != reloadToken) return
        if (current.isLoading || current.loadFailed || current.leaveReady || current.content == value) return
        _uiState.update {
            it.copy(
                content = value,
                saveState = if (it.isConflict) NoteSaveState.Failed else NoteSaveState.Pending,
                saveFailed = if (it.isConflict) it.saveFailed else false,
            )
        }
        scheduler.onEdit()
    }

    private fun syncEditorBuffer() {
        editorBuffer?.let { onContentChanged(it.read(), it.reloadToken) }
    }

    fun commitTitle(raw: String) {
        enqueue { generation ->
            syncEditorBuffer()
            val state = _uiState.value
            if (state.isLoading || state.loadFailed || state.rename != null) return@enqueue
            if (state.isConflict || state.saveFailed) {
                _uiState.update { it.copy(titleError = NoteTitleError.Blocked) }
                return@enqueue
            }
            val name = titleEditFor(raw, state.fileName, lastCommitted = null).renameTo
                ?: return@enqueue
            _uiState.update { it.copy(isRenaming = true) }
            try {
                scheduler.cancel()
                if (state.isDirty && !saveCurrent(generation)) return@enqueue
                if (generation != operationGeneration) return@enqueue
                val previousId = currentId
                val outcome = repository.renameNote(previousId, name)
                currentId = outcome.note.id
                val imageContext = runCatching { repository.imageContext(currentId) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                val current = _uiState.value
                val buffer = editorBuffer?.takeIf { it.reloadToken == current.reloadToken }
                val editorContent = buffer?.read() ?: current.content
                val renamed = current.afterRename(outcome.note, editorContent)
                if (renamed.content != editorContent) buffer?.replace?.invoke(renamed.content)
                _uiState.value = renamed.copy(
                    fileName = fileNameOf(currentId),
                    isNewNote = false,
                    imageContext = imageContext,
                    renameNotice = RenameNotice(outcome.linksUpdated, outcome.linksFailed)
                        .takeIf { outcome.linksUpdated > 0 || outcome.linksFailed > 0 },
                    rename = NoteRename(previousId, currentId),
                    titleError = null,
                )
                when {
                    renamed.isConflict -> writeConflictCopy()
                    renamed.isDirty -> scheduler.onEdit()
                }
                refreshWiki()
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultFileException) {
                _uiState.update { it.copy(titleError = e.toTitleError()) }
            } catch (e: RelocationStalledException) {
                _uiState.update { it.copy(titleError = NoteTitleError.Stalled) }
            } catch (_: Exception) {
                _uiState.update { it.copy(titleError = NoteTitleError.Failed) }
            } finally {
                _uiState.update { it.copy(isRenaming = false) }
            }
        }
    }

    fun onTitleErrorShown() {
        _uiState.update { it.copy(titleError = null) }
    }

    fun onRenameNoticeShown() {
        _uiState.update { it.copy(renameNotice = null) }
    }

    fun onRenameNavigated() {
        _uiState.update { it.copy(rename = null) }
    }

    fun prepareToOpenLinkedNote(): Boolean {
        val state = _uiState.value
        if (state.isRenaming || state.rename != null || leaving) return false
        onEnterBackground()
        return true
    }

    fun retrySave() {
        syncEditorBuffer()
        scheduler.cancel()
        enqueue { generation ->
            if (!_uiState.value.isLoading) saveCurrent(generation)
        }
    }

    fun overwrite() {
        if (_uiState.value.isLoading) return
        syncEditorBuffer()
        val request = overwriteRequest()
        scheduler.cancel()
        enqueue { generation -> saveCurrent(generation, request) }
    }

    fun reloadFromDisk() {
        operationGeneration++
        scheduler.cancel()
        leaving = false
        _uiState.update {
            it.copy(
                isLoading = true,
                leaveBlocked = false,
                leaveReady = false,
            )
        }
        loadJob = enqueue { generation ->
            try {
                val note = repository.reloadNote(currentId)
                if (generation != operationGeneration) return@enqueue
                lastConflictCopy = null
                _uiState.update {
                    it.copy(
                        content = note.content,
                        savedContent = note.content,
                        modifiedAt = note.modifiedAt,
                        revision = note.revision,
                        conflictRevision = null,
                        isLoading = false,
                        loadFailed = false,
                        saveState = NoteSaveState.Idle,
                        saveFailed = false,
                        isConflict = false,
                        fileName = fileNameOf(currentId),
                        conflictCopy = null,
                        reloadToken = it.reloadToken + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == operationGeneration) {
                    _uiState.update {
                        it.copy(isLoading = false, saveFailed = true, saveState = NoteSaveState.Failed)
                    }
                }
            }
        }
    }

    fun onEnterBackground() {
        syncEditorBuffer()
        scheduler.cancel()
        enqueue { generation ->
            val state = _uiState.value
            if (!state.isLoading && (state.isDirty || state.isConflict || state.saveFailed)) {
                if (state.isConflict || state.saveFailed) writeConflictCopy()
                if (!state.isConflict) saveCurrent(generation)
            }
        }
    }

    fun saveThenLeave(force: Boolean = false) {
        if (leaving || _uiState.value.isLoading || _uiState.value.leaveReady) return
        syncEditorBuffer()
        val request = if (force) overwriteRequest() else null
        leaving = true
        scheduler.cancel()
        enqueue { generation ->
            try {
                syncEditorBuffer()
                val state = _uiState.value
                val saved = if (request == null && state.willDiscardEmpty) {
                    discardEmpty(state)
                } else {
                    saveCurrent(generation, request)
                }
                if (!saved && _uiState.value.isConflict) writeConflictCopy()
                if (generation == operationGeneration) {
                    _uiState.update { it.copy(leaveReady = saved, leaveBlocked = !saved) }
                }
            } finally {
                leaving = false
            }
        }
    }

    fun requestLeave() {
        saveThenLeave()
    }

    fun onLeaveHandled() {
        _uiState.update { it.copy(leaveBlocked = false) }
    }

    fun onLeaveReadyHandled() {
        _uiState.update { it.copy(leaveReady = false) }
    }

    private fun overwriteRequest(): SaveRequest {
        val state = _uiState.value
        return SaveRequest(
            state.content,
            NoteWriteMode.Overwrite(state.conflictRevision ?: state.revision),
        )
    }

    private suspend fun saveFromScheduler() {
        enqueue { generation ->
            syncEditorBuffer()
            val state = _uiState.value
            when {
                state.isConflict -> writeConflictCopy()
                state.isDirty -> saveCurrent(generation)
            }
        }
    }

    private fun enqueue(action: suspend (Int) -> Unit): Job {
        val generation = operationGeneration
        return viewModelScope.launch {
            editorWrites.withLock {
                if (generation == operationGeneration) action(generation)
            }
        }
    }

    private suspend fun saveCurrent(generation: Int, request: SaveRequest? = null): Boolean {
        if (request != null && !persistOnce(request)) return false
        while (generation == operationGeneration) {
            syncEditorBuffer()
            val state = _uiState.value
            if (state.isConflict) return false
            if (!state.isDirty && !state.saveFailed) return true
            if (!persistOnce(SaveRequest(state.content, NoteWriteMode.Checked(state.revision)))) return false
        }
        return false
    }

    private suspend fun persistOnce(request: SaveRequest): Boolean {
        _uiState.update { it.copy(saveState = NoteSaveState.Saving, saveFailed = false) }
        try {
            val updated = repository.saveNote(currentId, request.content, request.mode)
            val copy = lastConflictCopy
            lastConflictCopy = null
            _uiState.update { state ->
                state.copy(
                    savedContent = request.content,
                    modifiedAt = updated.modifiedAt,
                    revision = updated.revision,
                    conflictRevision = null,
                    saveState = if (state.content == request.content) {
                        NoteSaveState.Saved
                    } else {
                        NoteSaveState.Pending
                    },
                    saveFailed = false,
                    isConflict = false,
                    conflictCopy = null,
                )
            }
            if (copy != null && copy.revision == updated.revision) {
                try {
                    repository.discardConflictCopy(currentId, copy)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(e)
            writeConflictCopy()
            return false
        }
        return true
    }

    private suspend fun writeConflictCopy() {
        val state = _uiState.value
        val content = state.content
        if (content == state.savedContent) return
        try {
            val copy = repository.saveConflictCopy(currentId, content, lastConflictCopy)
            lastConflictCopy = copy
            _uiState.update { it.copy(conflictCopy = copy.id.path) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun discardEmpty(state: NoteUiState): Boolean {
        return try {
            repository.discardNote(currentId, state.revision)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(e)
            writeConflictCopy()
            false
        }
    }

    private fun fileNameOf(id: NoteId): String =
        id.path.relativePath.substringAfterLast('/').substringBeforeLast('.')

    private fun recordFailure(error: Exception) {
        _uiState.update {
            it.copy(
                saveState = NoteSaveState.Failed,
                saveFailed = true,
                isConflict = it.isConflict || error is NoteChangedExternallyException,
                conflictRevision = (error as? NoteChangedExternallyException)?.actualRevision
                    ?: it.conflictRevision,
            )
        }
    }

    companion object {
        fun factory(
            repository: NoteRepository,
            settingsRepository: SettingsRepository,
            imageUploader: ImageUploader,
            initialId: NoteId,
            isNewNote: Boolean = false,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NoteViewModel(repository, settingsRepository, imageUploader, initialId, isNewNote)
            }
        }
    }
}

private fun VaultFileException.toTitleError(): NoteTitleError = when (failure) {
    VaultFileFailure.INVALID_NAME -> NoteTitleError.InvalidName
    VaultFileFailure.NAME_TAKEN -> NoteTitleError.NameTaken
    else -> NoteTitleError.Failed
}

private fun ImageUploadException.toNoteError(): NoteImageError = when (failure) {
    ImageUploadFailure.NotConfigured -> NoteImageError.NotConfigured
    ImageUploadFailure.UnsupportedImage -> NoteImageError.UnsupportedImage
    ImageUploadFailure.ImageTooLarge -> NoteImageError.ImageTooLarge
    ImageUploadFailure.ReadFailed, ImageUploadFailure.StorageFailed -> NoteImageError.Storage
    ImageUploadFailure.Rejected -> NoteImageError.Rejected(detail.orEmpty())
    ImageUploadFailure.Network -> NoteImageError.Network
}
