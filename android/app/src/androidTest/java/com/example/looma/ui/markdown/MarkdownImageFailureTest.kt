package com.example.looma.ui.markdown

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.looma.domain.markdown.ImageSource
import com.example.looma.ui.theme.LoomaTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownImageFailureTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test
    fun missingImageKeepsAVisibleRetryThatResolvesTheNewFile() {
        val image = File(temp.root, "missing.png")
        show { image.takeIf { it.isFile }?.let { ImageSource.Local(it.absolutePath) } }

        awaitRetry()
        writePng(image)
        compose.onNodeWithText("Retry").performClick()

        assertImageLoaded()
    }

    @Test
    fun undecodableImageKeepsAVisibleRetryThatReloadsTheSamePath() {
        val image = temp.newFile("broken.png").apply { writeText("invalid image") }
        show { ImageSource.Local(image.absolutePath) }

        awaitRetry()
        writePng(image)
        compose.onNodeWithText("Retry").performClick()

        assertImageLoaded()
    }

    private fun show(resolve: (String) -> ImageSource?) {
        val preview = runBlocking {
            prepareMarkdown("Before\n\n![Attachment|120x80](image.png)\n\nAfter")
        } as PreparedMarkdown.Ready
        compose.setContent {
            LoomaTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalImageSourceResolver provides resolve) {
                    MarkdownBody(preview = preview, onCheckboxToggle = { _, _ -> })
                }
            }
        }
    }

    private fun awaitRetry() {
        compose.waitUntil("image failure offers a retry", 5_000) {
            compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    private fun assertImageLoaded() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Attachment").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription("Attachment").assertIsDisplayed()
    }

    private fun writePng(file: File) {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
