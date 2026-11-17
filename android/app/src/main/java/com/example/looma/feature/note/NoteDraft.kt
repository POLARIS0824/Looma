package com.example.looma.feature.note

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteRevision

internal class NoteDraft {
    var editor by mutableStateOf(TextFieldState())
        private set
    private var baseline by mutableStateOf("")
    var revision: NoteRevision = NoteRevision.Unknown
        private set

    val content: String get() = editor.text.toString()
    val isDirty: Boolean
        get() {
            val text = editor.text
            val saved = baseline
            return text.length != saved.length || text.toString() != saved
        }

    fun replaceFromDisk(note: Note, selection: TextRange = TextRange(note.content.length)) {
        editor = TextFieldState(note.content, selection)
        markWritten(note.content, note.revision)
    }

    fun markWritten(content: String, revision: NoteRevision) {
        baseline = content
        this.revision = revision
    }

    fun rebaseAfterRename(note: Note): NoteSaveProblem.Conflict? {
        val rewritten = note.content != baseline
        val hadLocalEdits = isDirty
        if (!hadLocalEdits && rewritten) {
            val caret = editor.selection
            editor.edit {
                replace(0, length, note.content)
                selection = TextRange(caret.start.coerceAtMost(length), caret.end.coerceAtMost(length))
            }
        }
        markWritten(note.content, note.revision)
        return if (rewritten && isDirty) NoteSaveProblem.Conflict(note.revision) else null
    }
}
