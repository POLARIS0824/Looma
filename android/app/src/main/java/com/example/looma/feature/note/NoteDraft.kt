package com.example.looma.feature.note

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteRevision

internal class NoteDraft {
    var editor by mutableStateOf(NoteEditorState())
        private set
    private var baseline by mutableStateOf("")
    private var baselineVersion by mutableLongStateOf(0L)
    var revision: NoteRevision = NoteRevision.Unknown
        private set

    val content: String get() = editor.snapshot()
    val contentVersion: Long get() = editor.contentVersion
    val isDirty: Boolean
        get() {
            if (editor.contentVersion == baselineVersion) return false
            if (editor.length != baseline.length) return true
            return editor.snapshot() != baseline
        }

    fun replaceFromDisk(note: Note, selection: TextRange = TextRange.Zero) {
        replaceFromDisk(note.content, note.revision, selection)
    }

    fun replaceFromDisk(content: String, revision: NoteRevision, selection: TextRange = TextRange.Zero) {
        editor = NoteEditorState(content, selection)
        markWritten(content, revision)
    }

    fun markWritten(content: String, revision: NoteRevision) {
        baseline = content
        this.revision = revision
        baselineVersion = if (editor.length == content.length && editor.snapshot() == content) {
            editor.contentVersion
        } else {
            -1L
        }
    }

    fun rebaseAfterRename(note: Note): NoteSaveProblem.Conflict? {
        val rewritten = note.content != baseline
        val hadLocalEdits = isDirty
        if (!hadLocalEdits && rewritten) {
            val caret = editor.selection
            val clamped = TextRange(
                caret.start.coerceAtMost(note.content.length),
                caret.end.coerceAtMost(note.content.length),
            )
            editor.replaceRange(0, editor.length, note.content, clamped)
        }
        markWritten(note.content, note.revision)
        return if (rewritten && isDirty) NoteSaveProblem.Conflict(note.revision) else null
    }
}
