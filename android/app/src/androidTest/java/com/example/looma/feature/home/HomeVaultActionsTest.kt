package com.example.looma.feature.home

import android.os.Environment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.looma.data.folder.FolderRepository
import com.example.looma.data.index.NoteIndexDatabase
import com.example.looma.data.index.RoomNoteIndex
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.search.SearchHistoryRepository
import com.example.looma.data.vault.VaultPreferences
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.Vault
import com.example.looma.ui.theme.LoomaTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeVaultActionsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vaults = VaultRepository(context)
    private val root = File(Environment.getExternalStorageDirectory(), "looma-home-actions-${UUID.randomUUID()}")
    private val viewModels = ViewModelStore()
    private var originalSelection: Vault? = null
    private lateinit var database: NoteIndexDatabase
    private lateinit var notes: NoteRepository
    private lateinit var home: HomeViewModel

    @Before
    fun setUp() = runBlocking {
        originalSelection = vaults.getCurrentVault()
        check(root.mkdirs())
        vaults.setCurrentVault(root.path)
        database = Room.inMemoryDatabaseBuilder(context, NoteIndexDatabase::class.java).build()
        notes = NoteRepository(vaults, RoomNoteIndex(database))
        val factory = HomeViewModel.factory(
            vaults,
            notes,
            FolderRepository(notes),
            SearchHistoryRepository(vaults, database.searchHistoryDao()),
        )
        withContext(Dispatchers.Main) {
            home = ViewModelProvider.create(viewModels, factory)[HomeViewModel::class]
        }
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { viewModels.clear() }
        try {
            val original = originalSelection
            if (original == null) {
                vaults.clearCurrentVault()
            } else {
                VaultPreferences(context).saveVault(original.id.value, original.name, original.rootPath)
            }
        } finally {
            if (::database.isInitialized) database.close()
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun aMissingNoteLeavesTheOtherMovesUndoable() = runBlocking {
        writeSelectedNotes()
        selectNotes()
        check(file("B.md").delete())

        val moved = act { moveSelectionTo(FolderPath("Archive")) }

        assertTrue("The first note reached the destination", file("Archive/A.md").isFile)
        val undo = moved.undo
        assertNotNull("A successful move must remain undoable after another move fails", undo)
        assertEquals("# A", file("Archive/A.md").readText())
        assertEquals("# C", file("Archive/C.md").readText())
        assertTrue("The missing note must not appear at the destination", !file("Archive/B.md").exists())
        assertNotEquals(HomeNotice.Moved(3), moved.notice)

        act { undo(requireNotNull(undo)) }
        assertEquals("# A", file("A.md").readText())
        assertEquals("# C", file("C.md").readText())
    }

    @Test
    fun aMissingNoteLeavesTheOtherDeletionsUndoable() = runBlocking {
        writeSelectedNotes()
        selectNotes()
        check(file("B.md").delete())

        val deleted = act { deleteSelected() }

        val trashedPaths = notes.listTrashedNotes().map { it.originalPath.relativePath }.toSet()
        assertTrue("The first note reached the trash", "A.md" in trashedPaths)
        val undo = deleted.undo
        assertNotNull("A note moved to trash must remain undoable after another deletion fails", undo)
        assertEquals(setOf("A.md", "C.md"), trashedPaths)
        assertNotEquals(HomeNotice.Deleted(3), deleted.notice)

        act { undo(requireNotNull(undo)) }
        assertEquals("# A", file("A.md").readText())
        assertEquals("# C", file("C.md").readText())
    }

    @Test
    fun restoringOnlyPartOfABatchReportsAPartialOutcome() = runBlocking {
        writeSelectedNotes()
        selectNotes()
        val deleted = act { deleteSelected() }
        assertEquals(3, notes.listTrashedNotes().size)
        write("B.md", "replacement")

        val undone = act { undo(requireNotNull(deleted.undo)) }

        assertEquals("# A", file("A.md").readText())
        assertEquals("replacement", file("B.md").readText())
        assertEquals("# C", file("C.md").readText())
        assertEquals(listOf("B.md"), notes.listTrashedNotes().map { it.originalPath.relativePath })
        assertNotEquals("Some notes are still in the trash", HomeNotice.Undone, undone.notice)
        assertNotEquals("Two notes were restored", HomeNotice.UndoFailed, undone.notice)
    }

    @Test
    fun undoingMovesReportsAMissingNoteAndReturnsTheOtherNotes() = runBlocking {
        writeSelectedNotes()
        selectNotes()
        val moved = act { moveSelectionTo(FolderPath("Archive")) }
        assertEquals(HomeNotice.Moved(3), moved.notice)
        check(file("Archive/B.md").delete())

        val undone = act { undo(requireNotNull(moved.undo)) }

        assertEquals("# A", file("A.md").readText())
        assertTrue("The last note must return even if a moved note went missing", file("C.md").isFile)
        assertEquals("# C", file("C.md").readText())
        assertTrue("The missing note must not be recreated", !file("B.md").exists())
        assertNotEquals(HomeNotice.Undone, undone.notice)
        assertNotEquals(HomeNotice.UndoFailed, undone.notice)
    }

    @Test
    fun undoingAnAutoRenamedMoveRestoresTheOriginalFilename() = runBlocking {
        write("A.md", "selected note")
        write("Archive/A.md", "existing destination")
        refresh()
        withContext(Dispatchers.Main) {
            val note = home.uiState.value.notes.single { it.id.path.relativePath == "A.md" }
            home.onSelectionEvent(HomeSelectionEvent.NoteLongPressed(note))
        }

        val moved = act { moveSelectionTo(FolderPath("Archive")) }
        assertEquals(HomeNotice.Moved(1), moved.notice)
        assertEquals("existing destination", file("Archive/A.md").readText())
        assertEquals(false, file("A.md").exists())

        val undone = act { undo(requireNotNull(moved.undo)) }

        assertTrue("Undo must put the selected note back at its original path", file("A.md").isFile)
        assertEquals("selected note", file("A.md").readText())
        assertEquals(listOf("A.md"), file("Archive").listFiles().orEmpty().map { it.name })
        assertEquals("existing destination", file("Archive/A.md").readText())
        assertEquals(HomeNotice.Undone, undone.notice)
    }

    @Test
    fun deletingFolderNotesDoesNotClaimAnAttachmentOnlyFolderWasRemoved() = runBlocking {
        write("Assets/photo.png", "attachment")
        refresh()
        val folder = home.uiState.value.files.folders.single()
        withContext(Dispatchers.Main) {
            home.onSelectionEvent(HomeSelectionEvent.FolderLongPressed(folder))
            home.startFolderDelete()
        }
        val confirmation = home.uiState.value
        val deleted = act { confirmDeleteFolder() }
        assertEquals("attachment", file("Assets/photo.png").readText())

        val screenState = mutableStateOf(confirmation)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                LoomaTheme(dynamicColor = false) {
                    HomeScreen(state = screenState.value, actions = HomeActions())
                }
            }
        }
        val shownAsEmpty = compose.onAllNodesWithText("This folder is empty and will be removed.")
            .fetchSemanticsNodes().isNotEmpty()
        compose.runOnIdle {
            screenState.value = home.uiState.value.copy(message = deleted)
        }
        val shownAsDeleted = compose.onAllNodesWithText("Folder deleted")
            .fetchSemanticsNodes().isNotEmpty()
        assertEquals(
            "An attachment-only folder remains present after this action",
            emptyList<String>(),
            listOfNotNull("shown as empty".takeIf { shownAsEmpty }, "shown as deleted".takeIf { shownAsDeleted }),
        )
    }

    @Test
    fun searchFindsAOneCharacterQueryBeyondTheCardSnippet() = runBlocking {
        write("Long.md", "# Long\n\n${"ordinary text ".repeat(120)}末尾")
        write("Other.md", "# Other")
        refresh()

        withContext(Dispatchers.Main) { home.setQuery("尾") }

        val expectedPaths = listOf("Long.md")
        val result = withTimeout(10_000) {
            home.uiState.first { state ->
                state.searchResults.map { it.id.path.relativePath } == expectedPaths
            }
        }
        assertEquals("尾", result.query)
    }

    private fun writeSelectedNotes() {
        check(file("Archive").mkdir())
        for (name in listOf("A", "B", "C")) write("$name.md", "# $name")
    }

    private suspend fun selectNotes() {
        refresh()
        withContext(Dispatchers.Main) {
            home.setSort(HomeSort.TITLE)
            home.uiState.value.notes.filter { it.fileName in setOf("A", "B", "C") }.forEachIndexed { index, note ->
                val event = if (index == 0) HomeSelectionEvent.NoteLongPressed(note) else HomeSelectionEvent.NoteTapped(note)
                home.onSelectionEvent(event)
            }
            assertEquals(listOf("A", "B", "C"), home.uiState.value.selectedNotes.map { it.fileName })
        }
    }

    private suspend fun refresh() {
        withContext(Dispatchers.Main) { home.refresh() }.join()
        assertEquals(null, home.uiState.value.error)
    }

    private suspend fun act(action: HomeViewModel.() -> Unit): HomeMessage {
        withContext(Dispatchers.Main) {
            home.onMessageShown()
            home.action()
        }
        return withTimeout(10_000) {
            val result = home.uiState.first { !it.isActionRunning && it.message != null }
            requireNotNull(result.message)
        }
    }

    private fun file(path: String) = File(root, path)

    private fun write(path: String, text: String) {
        file(path).apply { parentFile?.mkdirs() }.writeText(text)
    }
}
