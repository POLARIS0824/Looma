@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.example.looma.feature.home.components

import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.VaultId
import com.example.looma.feature.home.HomeNoteItem
import com.example.looma.feature.home.HomeSearchResult
import com.example.looma.feature.home.HomeUiState
import com.example.looma.ui.theme.LoomaTheme
import java.time.Instant
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
                HomeSearchBar(
                    state = HomeUiState(
                        vaultId = VaultId("vault"),
                        vaultName = "Vault",
                        query = query,
                        searchResults = results,
                        isLoading = false,
                    ),
                    scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior(),
                    folderScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
                    onNavigateUp = {},
                    onQueryChange = { query = it },
                    onToggleLayout = {},
                    onSortClick = {},
                    onSearchSubmit = {},
                    onSearchResultClick = { homeVisible = false },
                    onMenuClick = {},
                    onAvatarClick = {},
                    homeVisible = homeVisible,
                )
            }
        }

        rule.onNode(hasSetTextAction()).performClick()
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
}
