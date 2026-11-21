package com.example.looma.feature.note

import com.example.looma.data.assistant.Connection
import com.example.looma.data.assistant.VaultEvidence
import com.example.looma.data.assistant.addition
import com.example.looma.domain.model.Vault
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.looma.data.image.ImageUpload
import com.example.looma.data.image.ImageUploadException
import com.example.looma.data.image.ImageUploadFailure
import com.example.looma.data.image.ImageUploader
import com.example.looma.data.note.ConflictCopy
import com.example.looma.data.note.NoteChangedExternallyException
import com.example.looma.data.note.NoteTooLargeException
import com.example.looma.data.note.UnsupportedNoteEncoding
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.note.RelocationStalledException
import com.example.looma.data.note.VaultFileException
import com.example.looma.data.note.VaultFileFailure
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteWriteMode
import com.example.looma.domain.search.PassageAnchor
import com.example.looma.domain.wiki.WikiContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var currentId: NoteId = initialId
    private var retargetVersion = 0L
    private val _uiState = MutableStateFlow(NoteUiState(isNewNote = isNewNote))
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    private data class SaveRequest(val content: String, val mode: NoteWriteMode)

    private enum class TitleCommitResult { Accepted, Rejected, SaveBlocked }

    private val draft = NoteDraft()
    internal val editor get() = draft.editor
    internal fun matchesPassage(passage: PassageAnchor): Boolean =
        !draft.isDirty && passage.matches(draft.revision, draft.content)
    private var restoredSelection = savedStateHandle.get<Bundle>(EDITOR_SELECTION)
        ?.getIntArray(EDITOR_SELECTION)
        ?.takeIf { it.size == 2 }
    private val editorWrites = Mutex()
    private var pendingEditorWrites by mutableIntStateOf(0)
    private var titleCommit: Deferred<TitleCommitResult>? = null
    private var operationGeneration = 0
    private var loadJob: Job? = null
    private var wikiRefreshJob: Job? = null
    private var initialStartPending = true
    private var lastConflictCopy: ConflictCopy? = null
    private val scheduler = SaveScheduler(viewModelScope, onSave = { saveFromScheduler() })

    init {
        savedStateHandle.setSavedStateProvider(EDITOR_SELECTION) {
            val selection = editor.selection
            Bundle().apply {
                putIntArray(EDITOR_SELECTION, restoredSelection ?: intArrayOf(selection.start, selection.end))
            }
        }
        viewModelScope.launch {
            snapshotFlow { draft.content }.collect {
                val state = _uiState.value
                if (!state.isLoading && !state.loadFailed && state.leave != NoteLeave.Ready) {
                    if (state.saveProblem == NoteSaveProblem.WriteFailed) {
                        _uiState.update { it.afterEditorEdit() }
                    }
                    if (draft.isDirty) scheduler.onEdit()
                }
            }
        }
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
        if (draft.isDirty || loadJob?.isActive == true) return
        loadJob = enqueue { generation ->
            if (draft.isDirty) return@enqueue
            _uiState.update { it.copy(isLoading = true, loadFailed = false, readOnlyPreview = null) }
            try {
                val note = repository.editorDocument(currentId, recover = true)
                val keepEmptyNotes = settingsRepository.settings.first().keepEmptyNotes
                if (generation != operationGeneration) return@enqueue
                if (draft.isDirty) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@enqueue
                }
                lastConflictCopy = null
                val selection = restoredSelection?.let { (start, end) ->
                    TextRange(start.coerceIn(0, note.content.length), end.coerceIn(0, note.content.length))
                } ?: TextRange(note.content.length)
                draft.replaceFromDisk(note.content, note.revision, selection)
                restoredSelection = null
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        saveProblem = null,
                        loadFailed = false,
                        keepEmptyNotes = keepEmptyNotes,
                        fileName = fileNameOf(currentId),
                        wiki = WikiContext.Empty,
                        imageContext = null,
                        conflictCopy = null,
                    )
                }
                refreshWiki()
                val id = currentId
                viewModelScope.launch {
                    val images = try {
                        repository.imageContext(id)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                    if (id == currentId && generation == operationGeneration) {
                        _uiState.update { it.copy(imageContext = images) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == operationGeneration) {
                    _uiState.update { it.copy(isLoading = false, loadFailed = true, readOnlyPreview = (e as? UnsupportedNoteEncoding)?.preview) }
                }
            }
        }
    }

    fun onRouteStarted(id: NoteId, routeVersion: Long) {
        val isInitialStart = initialStartPending
        initialStartPending = false
        if ((isInitialStart || loadJob?.isActive == true) && id == currentId && routeVersion <= retargetVersion) return
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
                if (canRefreshDraft()) {
                    val disk = repository.editorDocument(currentId)
                    if (disk.revision != draft.revision && canRefreshDraft()) {
                        draft.replaceFromDisk(disk.content, disk.revision)
                        _uiState.update { it.copy(fileName = fileNameOf(currentId)) }
                    }
                }
                if (retargeted) {
                    val context = repository.imageContext(currentId)
                    _uiState.update { it.copy(imageContext = context) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: UnsupportedNoteEncoding) {
                if (canRefreshDraft()) {
                    _uiState.update { it.copy(loadFailed = true, readOnlyPreview = error.preview) }
                }
            } catch (_: Exception) {
            }
            refreshWiki()
        }
    }

    fun refreshWiki() {
        if (_uiState.value.isLoading) return
        val id = currentId
        val generation = operationGeneration
        wikiRefreshJob?.cancel()
        wikiRefreshJob = viewModelScope.launch {
            try {
                val wiki = repository.wikiContext(id)
                if (id == currentId && generation == operationGeneration && !_uiState.value.isLoading) {
                    _uiState.update { it.copy(wiki = wiki) }
                }
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
                val upload = imageUploader.upload(uri, settings, currentId.path)
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

    private fun canRefreshDraft(): Boolean = _uiState.value.canRefreshDraft(draft.isDirty)

    internal fun requiresSaveBeforeLeaving(state: NoteUiState): Boolean =
        !state.isLoading && !state.loadFailed && (
            state.leave != NoteLeave.Idle || state.isRenaming || state.rename != null ||
                state.saveProblem != null || draft.isDirty || state.shouldDiscardEmpty(editor.text) ||
                pendingEditorWrites > 0
            )

    fun commitTitle(raw: String) {
        val commit = CompletableDeferred<TitleCommitResult>()
        titleCommit = commit
        val job = enqueue { generation ->
            commit.complete(renameTitle(raw, generation))
        }
        job.invokeOnCompletion { commit.complete(TitleCommitResult.Rejected) }
    }

    private suspend fun renameTitle(raw: String, generation: Int): TitleCommitResult {
        val state = _uiState.value
        if (state.isLoading || state.loadFailed) return TitleCommitResult.Rejected
        val name = titleEditFor(raw, state.fileName, lastCommitted = null).renameTo
            ?: return TitleCommitResult.Accepted
        if (state.saveProblem != null || state.rename != null) {
            _uiState.update { it.copy(titleError = NoteTitleError.Blocked) }
            return if (state.saveProblem != null) TitleCommitResult.SaveBlocked else TitleCommitResult.Rejected
        }
        _uiState.update { it.copy(isRenaming = true) }
        val error = try {
            scheduler.cancel()
            if (draft.isDirty && !saveCurrent(generation)) {
                _uiState.update { it.copy(titleError = NoteTitleError.Blocked) }
                return TitleCommitResult.SaveBlocked
            }
            if (generation != operationGeneration) return TitleCommitResult.Rejected
            val previousId = currentId
            val outcome = repository.renameNote(previousId, name)
            currentId = outcome.note.id
            val imageContext = runCatching { repository.imageContext(currentId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            val conflict = draft.rebaseAfterRename(outcome.note)
            _uiState.update {
                it.copy(
                    fileName = fileNameOf(currentId),
                    isNewNote = false,
                    imageContext = imageContext,
                    renameNotice = RenameNotice(outcome.linksUpdated, outcome.linksFailed)
                        .takeIf { outcome.linksUpdated > 0 || outcome.linksFailed > 0 },
                    rename = NoteRename(previousId, currentId),
                    titleError = null,
                    saveProblem = conflict,
                )
            }
            when {
                conflict != null -> writeConflictCopy()
                draft.isDirty -> scheduler.onEdit()
            }
            refreshWiki()
            return TitleCommitResult.Accepted
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultFileException) {
            e.toTitleError()
        } catch (_: RelocationStalledException) {
            NoteTitleError.Stalled
        } catch (_: Exception) {
            NoteTitleError.Failed
        } finally {
            _uiState.update { it.copy(isRenaming = false) }
        }
        _uiState.update { it.copy(titleError = error) }
        return TitleCommitResult.Rejected
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
        if (state.isRenaming || state.rename != null || state.leave == NoteLeave.Saving) return false
        onEnterBackground()
        return true
    }

    internal suspend fun confirmWeave(
        vault: Vault,
        suggestion: Connection,
        evidence: VaultEvidence,
    ): Boolean = editorWrites.withLock {
        val source = draft.content
        val generation = operationGeneration
        if (currentId != suggestion.source || uiState.value.saveProblem != null ||
            !evidence.linkStillValid(vault, suggestion, source)) return@withLock false
        if (source != draft.content || generation != operationGeneration || currentId != suggestion.source) return@withLock false
        val at = suggestion.passage?.insertAt ?: source.length
        editor.edit { replace(at, at, suggestion.addition(source)) }
        saveCurrent(generation)
    }

    fun retrySave() {
        scheduler.cancel()
        enqueue { generation ->
            if (!_uiState.value.isLoading) saveCurrent(generation)
        }
    }

    fun overwrite() {
        if (_uiState.value.isLoading) return
        val request = overwriteRequest()
        scheduler.cancel()
        enqueue { generation -> saveCurrent(generation, request) }
    }

    fun reloadFromDisk() {
        initialStartPending = false
        operationGeneration++
        scheduler.cancel()
        _uiState.update { it.copy(isLoading = true, leave = NoteLeave.Idle) }
        loadJob = enqueue { generation ->
            try {
                val note = repository.editorDocument(currentId, recover = true)
                if (generation != operationGeneration) return@enqueue
                lastConflictCopy = null
                draft.replaceFromDisk(note.content, note.revision)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadFailed = false,
                        saveProblem = null,
                        fileName = fileNameOf(currentId),
                        conflictCopy = null,
                    )
                }
                refreshWiki()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == operationGeneration) {
                    _uiState.update { it.afterSaveFailure().copy(isLoading = false) }
                }
            }
        }
    }

    fun onEnterBackground() {
        initialStartPending = false
        scheduler.cancel()
        enqueue { generation ->
            val state = _uiState.value
            if (!state.isLoading && (draft.isDirty || state.saveProblem != null)) {
                if (state.saveProblem != null) writeConflictCopy()
                if (!state.isConflict) saveCurrent(generation)
            }
        }
    }

    fun saveThenLeave(force: Boolean = false) = saveThenLeave(force, titleCommit = null)

    private fun saveThenLeave(force: Boolean, titleCommit: Deferred<TitleCommitResult>?) {
        val current = _uiState.value
        if (current.isLoading || current.leave == NoteLeave.Saving || current.leave == NoteLeave.Ready) return
        val generation = operationGeneration
        val request = if (force) overwriteRequest() else null
        _uiState.update { it.copy(leave = NoteLeave.Saving) }
        scheduler.cancel()
        viewModelScope.launch {
            var outcome = NoteLeave.Idle
            try {
                val titleResult = titleCommit?.await()
                editorWrites.withLock {
                    if (generation != operationGeneration) return@withLock
                    val state = _uiState.value
                    when (titleResult) {
                        TitleCommitResult.Rejected -> return@withLock
                        TitleCommitResult.SaveBlocked -> {
                            if (state.isConflict) writeConflictCopy()
                            if (state.saveProblem != null) outcome = NoteLeave.Blocked
                            return@withLock
                        }
                        TitleCommitResult.Accepted, null -> Unit
                    }
                    val saved = if (request == null && state.shouldDiscardEmpty(editor.text)) {
                        discardEmpty()
                    } else {
                        saveCurrent(generation, request)
                    }
                    if (!saved && _uiState.value.isConflict) writeConflictCopy()
                    outcome = if (saved) NoteLeave.Ready else NoteLeave.Blocked
                }
            } finally {
                if (generation == operationGeneration) {
                    _uiState.update { it.copy(leave = outcome) }
                }
            }
        }
    }

    internal fun requestLeave(clearFocus: () -> Unit, onAlreadySaved: (() -> Unit)? = null) {
        val previousCommit = titleCommit
        val pendingCommit = previousCommit?.takeUnless { it.isCompleted }
        clearFocus()
        val commit = titleCommit?.takeUnless { it === previousCommit } ?: pendingCommit
        if (onAlreadySaved != null && commit == null && !requiresSaveBeforeLeaving(_uiState.value)) {
            onAlreadySaved()
        } else {
            saveThenLeave(force = false, titleCommit = commit)
        }
    }

    fun onLeaveHandled() {
        _uiState.update { if (it.leave == NoteLeave.Blocked) it.copy(leave = NoteLeave.Idle) else it }
    }

    fun consumeLeaveReady(): Boolean {
        if (_uiState.value.leave != NoteLeave.Ready) return false
        _uiState.update { it.copy(leave = NoteLeave.Idle) }
        return true
    }

    private fun overwriteRequest(): SaveRequest {
        val state = _uiState.value
        val conflict = state.saveProblem as? NoteSaveProblem.Conflict
        return SaveRequest(
            draft.content,
            NoteWriteMode.Overwrite(conflict?.actualRevision ?: draft.revision),
        )
    }

    private suspend fun saveFromScheduler() {
        enqueue { generation ->
            val state = _uiState.value
            when {
                state.isConflict -> writeConflictCopy()
                draft.isDirty -> saveCurrent(generation)
            }
        }
    }

    private fun enqueue(action: suspend (Int) -> Unit): Job {
        val generation = operationGeneration
        pendingEditorWrites++
        val job = viewModelScope.launch {
            editorWrites.withLock {
                if (generation == operationGeneration) action(generation)
            }
        }
        job.invokeOnCompletion { pendingEditorWrites-- }
        return job
    }

    private suspend fun saveCurrent(generation: Int, request: SaveRequest? = null): Boolean {
        if (request != null && !persistOnce(request)) return false
        while (generation == operationGeneration) {
            val state = _uiState.value
            if (state.isConflict) return false
            if (!draft.isDirty && state.saveProblem == null) return true
            if (!persistOnce(SaveRequest(draft.content, NoteWriteMode.Checked(draft.revision)))) return false
        }
        return false
    }

    private suspend fun persistOnce(request: SaveRequest): Boolean {
        _uiState.update { it.whenSaveStarts() }
        try {
            val updated = repository.saveNote(currentId, request.content, request.mode)
            val copy = lastConflictCopy
            lastConflictCopy = null
            draft.markWritten(request.content, updated.revision)
            _uiState.update { it.copy(saveProblem = null, conflictCopy = null, exceedsSizeLimit = false) }
            if (copy != null && copy.revision == updated.revision) {
                try {
                    repository.discardConflictCopy(currentId, copy)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            refreshWiki()
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
        if (!draft.isDirty) return
        val content = draft.content
        try {
            val copy = repository.saveConflictCopy(currentId, content, lastConflictCopy)
            lastConflictCopy = copy
            _uiState.update { it.copy(conflictCopy = copy.id.path) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun discardEmpty(): Boolean {
        return try {
            repository.discardNote(currentId, draft.revision)
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
            it.afterSaveFailure((error as? NoteChangedExternallyException)?.actualRevision)
                .copy(exceedsSizeLimit = error is NoteTooLargeException, conflictCopy = null)
        }
    }

    companion object {
        private const val EDITOR_SELECTION = "note-editor-selection"

        fun factory(
            repository: NoteRepository,
            settingsRepository: SettingsRepository,
            imageUploader: ImageUploader,
            initialId: NoteId,
            isNewNote: Boolean = false,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NoteViewModel(
                    repository, settingsRepository, imageUploader, initialId, isNewNote, createSavedStateHandle(),
                )
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
