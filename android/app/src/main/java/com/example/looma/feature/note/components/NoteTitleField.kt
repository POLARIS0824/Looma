package com.example.looma.feature.note.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import com.example.looma.R
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun NoteTitleField(
    fileName: String,
    isNewNote: Boolean,
    textStyle: TextStyle,
    focusRequester: FocusRequester,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    val buffer = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState(fileName) }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(focused, fileName) {
        if (!focused && buffer.text.toString() != fileName) {
            buffer.setTextAndPlaceCursorAtEnd(fileName)
        }
    }

    LaunchedEffect(Unit) {
        if (isNewNote) {
            focusRequester.requestFocus()
            buffer.edit { selection = TextRange(0, length) }
            keyboard?.show()
        }
    }

    val commitDraft = {
        val draft = buffer.text.toString()
        if (draft.isBlank()) {
            buffer.setTextAndPlaceCursorAtEnd(fileName)
        } else if (draft.trim() != fileName) {
            onCommit(draft)
        }
    }

    NoteTextField(
        state = buffer,
        active = true,
        textStyle = textStyle.copy(color = LoomaTokens.colors.title),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                val wasFocused = focused
                focused = focusState.isFocused
                if (wasFocused && !focusState.isFocused) {
                    commitDraft()
                }
            },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        onKeyboardAction = {
            commitDraft()
            onDone()
        },
        decorator = TextFieldDecorator { innerTextField ->
            Box {
                if (buffer.text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.editor_title_hint),
                        style = textStyle,
                        color = LoomaTokens.colors.muted,
                    )
                }
                innerTextField()
            }
        },
    )
}