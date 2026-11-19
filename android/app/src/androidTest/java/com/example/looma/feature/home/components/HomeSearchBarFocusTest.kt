package com.example.looma.feature.home.components

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.looma.R
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.VaultId
import com.example.looma.feature.home.HomeActions
import com.example.looma.feature.home.HomeBrowseActions
import com.example.looma.feature.home.HomeCrumb
import com.example.looma.feature.home.HomeFilesState
import com.example.looma.feature.home.HomeFolderItem
import com.example.looma.feature.home.HomeNoteItem
import com.example.looma.feature.home.HomeScreen
import com.example.looma.feature.home.HomeSearchActions
import com.example.looma.feature.home.HomeSearchResult
import com.example.looma.feature.home.HomeSearchUiState
import com.example.looma.feature.home.HomeUiState
import com.example.looma.ui.theme.LoomaTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeSearchBarFocusTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun returningFromSearchResultKeepsQueryWithoutRefocusingInput() {
        val noteId = NoteId(VaultId("vault"), NotePath("Target Note.md"))
        val sampleResult = HomeSearchResult(
            note = HomeNoteItem(
                summary = NoteSummary(
                    id = noteId,
                    title = "Target Note",
                    modifiedAt = Instant.EPOCH,
                    tags = emptySet(),
                ),
                markdownSnippet = "Snippet",
            ),
            titleMatch = null,
            tagMatches = emptyList(),
            matches = emptyList(),
        )
        var homeVisible by mutableStateOf(true)
        var query by mutableStateOf("")

        rule.setContent {
            LoomaTheme(dynamicColor = false) {
                val results = if (query.isBlank()) emptyList() else listOf(sampleResult)
                HomeScreen(
                    state = HomeUiState(
                        vaultId = VaultId("vault"),
                        vaultName = "Vault",
                        search = HomeSearchUiState(
                            query = query,
                            results = results,
                        ),
                        isLoading = false,
                    ),
                    actions = HomeActions(
                        search = HomeSearchActions(
                            onQueryChange = { query = it },
                            onSearchResultClick = { homeVisible = false },
                        ),
                    ),
                    homeVisible = homeVisible,
                )
            }
        }

        rule.onNode(hasSetTextAction()).performTouchInput { click(Offset(18.dp.toPx(), centerY)) }
        rule.waitForIdle()

        val dialogInput = hasSetTextAction() and hasAnyAncestor(isDialog())
        rule.onNode(dialogInput).assertIsFocused()
        rule.onNode(dialogInput).performTextInput("Target")
        rule.waitForIdle()
        rule.onNodeWithText("Target Note").assertIsDisplayed().performClick()
        rule.waitForIdle()

        homeVisible = true
        rule.waitForIdle()

        rule.onNodeWithText("Target Note").assertIsDisplayed()
        rule.onNode(dialogInput).assertIsNotFocused()
        rule.onNode(hasSetTextAction() and !hasAnyAncestor(isDialog())).assertIsNotFocused()

        rule.onNode(dialogInput).performClick()
        rule.waitForIdle()
        rule.onNode(dialogInput).assertIsFocused()
    }

    @Test
    fun folderNavigationEmitsSearchClosedOnlyAfterSearchIsDismissed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = HomeFolderItem(FolderPath("Test folder"), "Test folder")
        val root = HomeFilesState(folders = listOf(folder))
        var files by mutableStateOf(root)
        var query by mutableStateOf("Retained query")
        var searchClosedCount = 0

        rule.setContent {
            LoomaTheme(dynamicColor = false) {
                HomeScreen(
                    state = HomeUiState(
                        vaultName = "Vault",
                        search = HomeSearchUiState(query = query),
                        files = files,
                        isLoading = false,
                    ),
                    actions = HomeActions(
                        search = HomeSearchActions(
                            onQueryChange = { query = it },
                            onSearchClosed = { searchClosedCount++ },
                        ),
                        browse = HomeBrowseActions(
                            onFolderClick = { item ->
                                files = HomeFilesState(
                                    path = item.path,
                                    crumbs = listOf(HomeCrumb(FolderPath.Root, null), HomeCrumb(item.path, item.name)),
                                )
                            },
                            onNavigateUp = { files = root },
                        ),
                    ),
                )
            }
        }

        rule.onNodeWithText(folder.name).performClick()
        rule.onNodeWithText(context.getString(R.string.home_empty_folder)).assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(0, searchClosedCount)
            assertEquals("Retained query", query)
        }

        rule.onNodeWithContentDescription(context.getString(R.string.home_search_placeholder, "Vault")).performClick()
        rule.onNode(
            hasAnyAncestor(isDialog()) and hasSetTextAction(),
        ).assertIsDisplayed()
        rule.onNodeWithContentDescription(context.getString(R.string.home_close_search_description)).performClick()
        rule.onNodeWithContentDescription(context.getString(R.string.home_breadcrumb_back_description)).performClick()

        rule.onNodeWithText(folder.name).assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(1, searchClosedCount)
            assertEquals("", query)
        }
    }
}
