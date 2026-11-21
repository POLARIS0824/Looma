package com.example.looma.feature.note

import com.example.looma.domain.markdown.ImageContext
import com.example.looma.domain.markdown.ToolbarItem
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.wiki.WikiContext

sealed interface NoteSaveProblem {
    data object WriteFailed : NoteSaveProblem
    data class Conflict(
        val actualRevision: NoteRevision,
        val showBanner: Boolean = true,
    ) : NoteSaveProblem
}

enum class NoteLeave { Idle, Saving, Blocked, Ready }

data class ImageInsertion(val snippet: String, val token: Long)

data class RenameNotice(val linksUpdated: Int, val linksFailed: Int)
data class NoteRename(val from: NoteId, val to: NoteId)

sealed interface NoteImageError {
    data object NotConfigured : NoteImageError
    data object UnsupportedImage : NoteImageError
    data object ImageTooLarge : NoteImageError
    data object Storage : NoteImageError
    data object Network : NoteImageError
    data class Rejected(val detail: String) : NoteImageError
    data object Unknown : NoteImageError
}

sealed interface NoteTitleError {
    data object InvalidName : NoteTitleError
    data object NameTaken : NoteTitleError
    data object Stalled : NoteTitleError
    data object Blocked : NoteTitleError
    data object Failed : NoteTitleError
}

data class NoteUiState(
    val saveProblem: NoteSaveProblem? = null,
    val leave: NoteLeave = NoteLeave.Idle,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val readOnlyPreview: String? = null,
    val exceedsSizeLimit: Boolean = false,
    val keepEmptyNotes: Boolean = true,
    val isNewNote: Boolean = false,
    val fileName: String = "",
    val wiki: WikiContext = WikiContext.Empty,
    val imageContext: ImageContext? = null,
    val conflictCopy: NotePath? = null,
    val showInlineTitle: Boolean = true,
    val toolbarOrder: List<ToolbarItem> = ToolbarItem.DEFAULT_ORDER,
    val isUploadingImage: Boolean = false,
    val imageInsertion: ImageInsertion? = null,
    val imageUploadError: NoteImageError? = null,
    val titleError: NoteTitleError? = null,
    val renameNotice: RenameNotice? = null,
    val rename: NoteRename? = null,
    val isRenaming: Boolean = false,
) {
    val isConflict: Boolean get() = saveProblem is NoteSaveProblem.Conflict

    val showSaveBanner: Boolean
        get() = when (val problem = saveProblem) {
            null -> false
            NoteSaveProblem.WriteFailed -> true
            is NoteSaveProblem.Conflict -> problem.showBanner
        }

    internal fun shouldDiscardEmpty(content: CharSequence): Boolean =
        isNewNote && !keepEmptyNotes && !isConflict && content.isBlank()

    internal fun canRefreshDraft(hasEdits: Boolean): Boolean =
        !isLoading && !loadFailed && !hasEdits && saveProblem == null &&
            leave != NoteLeave.Ready && leave != NoteLeave.Blocked

    internal fun afterEditorEdit(): NoteUiState =
        if (saveProblem == NoteSaveProblem.WriteFailed) copy(saveProblem = null) else this

    internal fun whenSaveStarts(): NoteUiState =
        copy(saveProblem = (saveProblem as? NoteSaveProblem.Conflict)?.copy(showBanner = false))

    internal fun afterSaveFailure(actualRevision: NoteRevision? = null): NoteUiState {
        val conflict = saveProblem as? NoteSaveProblem.Conflict
        val revision = actualRevision ?: conflict?.actualRevision
        val problem = revision?.let { NoteSaveProblem.Conflict(it) } ?: NoteSaveProblem.WriteFailed
        return copy(saveProblem = problem)
    }
}
