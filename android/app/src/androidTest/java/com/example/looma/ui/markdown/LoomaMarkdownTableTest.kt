package com.example.looma.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.looma.domain.markdown.PreparedMarkdown
import com.example.looma.domain.markdown.prepareMarkdown
import com.example.looma.ui.theme.LoomaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoomaMarkdownTableTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun longInlineCodeWrapsWithoutLosingAnyText() {
        val value = "POST /api/workouts HTTP/1.1"
        show(
            """
            | Part | Example | Purpose |
            | --- | --- | --- |
            | Request | `$value` | Opens the endpoint |
            """.trimIndent(),
        )

        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(value, substring = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue(layout.lineCount > 1)
        assertFalse(layout.hasVisualOverflow)
        rule.onNodeWithText("Purpose", substring = true).assertIsDisplayed()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .assertDoesNotExist()
    }

    @Test
    fun wideTableCanScrollToTheLastColumn() {
        show(
            """
            | First | Second | Third | Fourth | Final column |
            | --- | --- | --- | --- | --- |
            | Short row | Has empty trailing cells |
            | One | Two | Three | Four | Last value |
            """.trimIndent(),
        )

        val last = rule.onNodeWithText("Last value", substring = true)
        last.assertIsNotDisplayed()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(1000f, 0f) }
        last.assertIsDisplayed()
    }

    private fun show(markdown: String) {
        val preview = runBlocking { prepareMarkdown(markdown) } as PreparedMarkdown.Ready
        rule.setContent {
            LoomaTheme(dynamicColor = false) {
                Box(Modifier.width(300.dp)) {
                    MarkdownBody(preview = preview, onCheckboxToggle = null)
                }
            }
        }
    }
}
