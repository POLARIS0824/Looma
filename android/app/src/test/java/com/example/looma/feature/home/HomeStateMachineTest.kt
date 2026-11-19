package com.example.looma.feature.home

import com.example.looma.data.search.SemanticWorkState
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.VaultFolder
import com.example.looma.domain.model.VaultId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStateMachineTest {

    private val vaultId = VaultId("vault")

    @Test
    fun `topBarMode derives Root, Folder, and Selection exhaustively`() {
        val note = note("Alpha")
        val folder = HomeFolderItem(FolderPath("Work"), "Work")
        val folderFiles = HomeFilesState(path = FolderPath("Work"))

        assertEquals(HomeTopBarMode.Root, HomeUiState().topBarMode)
        assertEquals(HomeTopBarMode.Folder, HomeUiState(files = folderFiles).topBarMode)
        assertEquals(
            HomeTopBarMode.Selection,
            HomeUiState(
                files = folderFiles,
                selection = HomeSelection.Notes.of(setOf(note.listKey)),
            ).topBarMode,
        )
        assertEquals(
            HomeTopBarMode.Selection,
            HomeUiState(selection = HomeSelection.Folder(folder)).topBarMode,
        )
    }

    @Test
    fun `selectedNotes stays derived in sorted order without manual synchronization`() {
        val alpha = note("Alpha", modifiedAt = Instant.parse("2026-10-01T00:00:00Z"))
        val beta = note("Beta", modifiedAt = Instant.parse("2026-10-02T00:00:00Z"))
        val gamma = note("Gamma", modifiedAt = Instant.parse("2026-10-03T00:00:00Z"))
        val state = HomeUiState(
            notes = listOf(gamma, beta, alpha),
            selection = HomeSelection.Notes.of(setOf(alpha.listKey, gamma.listKey)),
            sort = HomeSort.LAST_MODIFIED,
        )

        assertEquals(listOf(gamma, alpha), state.selectedNotes)
        assertEquals(listOf(alpha, gamma), state.copy(sort = HomeSort.TITLE).selectedNotes)
        assertEquals(listOf(gamma, alpha), state.copy(sort = HomeSort.TITLE_DESCENDING).selectedNotes)
    }

    @Test
    fun `new folder dialog error applies only to the matching dialog instance`() {
        val first = HomeFileDialog.NewFolder(id = 1L)
        val second = HomeFileDialog.NewFolder(id = 2L)
        val reopened = HomeUiState(dialog = second)

        val afterStaleError = reopened.applyDialogError(first.id, HomeFileError.NAME_TAKEN)
        assertEquals(second, afterStaleError.dialog)

        val afterCurrentError = reopened.applyDialogError(second.id, HomeFileError.NAME_TAKEN)
        assertEquals(HomeFileError.NAME_TAKEN, (afterCurrentError.dialog as HomeFileDialog.NewFolder).error)
    }

    @Test
    fun `consecutive identical notices carry distinct generations`() {
        val first = HomeMessage(HomeNotice.ActionFailed, generation = 1L)
        val second = HomeMessage(HomeNotice.ActionFailed, generation = 2L)

        assertNotEquals(first, second)
    }

    @Test
    fun `subfolder HomeFilesState skips recents while root populates recents`() {
        val rootNote = note("Root")
        val subNote = note("Sub", folder = "Work")
        val index = HomeVaultIndex.build(
            folders = listOf(VaultFolder(vaultId, FolderPath("Work"), "Work")),
            notes = listOf(rootNote, subNote),
        )

        assertEquals(2, index.files(FolderPath.Root, HomeSort.TITLE).recents.size)
        assertTrue(index.files(FolderPath("Work"), HomeSort.TITLE).recents.isEmpty())
    }

    @Test
    fun `semanticAvailability maps model and index work states`() {
        assertEquals(HomeSemanticSearch.NeedsModel, semanticAvailability(SemanticWorkState.Idle, hasIndex = true))
        assertEquals(HomeSemanticSearch.NeedsModel, semanticAvailability(SemanticWorkState.Running(1, 2), hasIndex = true))
        assertEquals(HomeSemanticSearch.NeedsIndex, semanticAvailability(SemanticWorkState.Ready, hasIndex = false))
        assertEquals(HomeSemanticSearch.Ready, semanticAvailability(SemanticWorkState.Ready, hasIndex = true))
    }

    @Test
    fun `orderedBy produces identical order for HomeNoteItem and HomeSearchResult`() {
        val a = note("Alpha", modifiedAt = Instant.parse("2026-10-01T00:00:00Z"))
        val b = note("beta", modifiedAt = Instant.parse("2026-10-03T00:00:00Z"))
        val c = note("Gamma", modifiedAt = Instant.parse("2026-10-02T00:00:00Z"))
        val items = listOf(a, b, c)
        val results = items.map { HomeSearchResult(it, null, emptyList(), emptyList()) }

        for (sort in HomeSort.entries) {
            assertEquals(
                items.orderedBy(sort).map { it.listKey },
                results.orderedBy(sort).map { it.listKey },
            )
        }
    }

    private fun note(
        title: String,
        folder: String? = null,
        modifiedAt: Instant = Instant.parse("2026-10-31T12:00:00Z"),
    ): HomeNoteItem {
        val path = if (folder == null) "$title.md" else "$folder/$title.md"
        return HomeNoteItem(
            summary = NoteSummary(
                id = NoteId(vaultId, NotePath(path)),
                title = title,
                modifiedAt = modifiedAt,
            ),
        )
    }
}
