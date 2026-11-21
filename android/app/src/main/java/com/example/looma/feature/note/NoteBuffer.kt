package com.example.looma.feature.note

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import com.example.looma.domain.markdown.MarkdownCommand
import com.example.looma.domain.markdown.MarkdownEditing
import com.example.looma.domain.markdown.TextSelection

internal fun applyMarkdownCommand(buffer: TextFieldState, command: MarkdownCommand) {
    val caret = buffer.selection
    val edit = MarkdownEditing.replacement(command, buffer.text, TextSelection(caret.start, caret.end))
    buffer.edit {
        replace(edit.from, edit.to, edit.replacement)
        selection = TextRange(edit.selection.start, edit.selection.end)
    }
}
