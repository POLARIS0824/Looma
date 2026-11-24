package com.example.looma.feature.note

import androidx.compose.ui.text.TextRange
import com.example.looma.domain.markdown.MarkdownCommand
import com.example.looma.domain.markdown.MarkdownEditing
import com.example.looma.domain.markdown.TextSelection

internal fun applyMarkdownCommand(buffer: NoteEditorState, command: MarkdownCommand) {
    val caret = buffer.selection
    val edit = MarkdownEditing.replacement(command, buffer.snapshot(), TextSelection(caret.start, caret.end))
    buffer.replaceRange(
        start = edit.from,
        end = edit.to,
        replacement = edit.replacement,
        newSelection = TextRange(edit.selection.start, edit.selection.end),
    )
}
