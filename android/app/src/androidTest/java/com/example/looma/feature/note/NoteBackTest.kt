package com.example.looma.feature.note

import android.os.Environment
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.looma.data.image.ImageUploader
import com.example.looma.data.index.NoteIndexDatabase
import com.example.looma.data.index.RoomNoteIndex
import com.example.looma.data.note.MAX_MARKDOWN_BYTES
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.data.vault.VaultPreferences
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteBackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vaults = VaultRepository(context)
    private val root = File(Environment.getExternalStorageDirectory(), "looma-note-back-${UUID.randomUUID()}")
    private val viewModels = ViewModelStore()
    private var originalSelection: Vault? = null
    private lateinit var database: NoteIndexDatabase
    private lateinit var viewModel: NoteViewModel
    private lateinit var noteId: NoteId

    @Before
    fun setUp() = runBlocking {
        originalSelection = vaults.getCurrentVault()
        check(root.mkdirs())
        val vault = vaults.setCurrentVault(root.path)
        File(root, "Source.md").writeText("source")
        File(root, "Taken.md").writeText("taken")
        noteId = NoteId(vault.id, NotePath("Source.md"))
        database = Room.inMemoryDatabaseBuilder(context, NoteIndexDatabase::class.java).build()
        val factory = viewModelFactory {
            initializer {
                NoteViewModel(
                    repository = NoteRepository(vaults, RoomNoteIndex(database)),
                    settingsRepository = SettingsRepository(context),
                    imageUploader = ImageUploader(context, vaults),
                    initialId = noteId,
                    isNewNote = false,
                    savedStateHandle = SavedStateHandle(),
                )
            }
        }
        withContext(Dispatchers.Main) {
            viewModel = ViewModelProvider.create(viewModels, factory)[NoteViewModel::class]
        }
        assertFalse(awaitState { !it.isLoading }.loadFailed)
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
    fun rejectedTitleStopsThatBackButTheNextBackCanLeave() = runBlocking {
        withContext(Dispatchers.Main) {
            viewModel.requestLeave(clearFocus = { viewModel.commitTitle("Taken") })
        }
        val rejected = awaitState { it.titleError != null && it.leave != NoteLeave.Saving }
        assertEquals(NoteTitleError.NameTaken, rejected.titleError)
        assertEquals(NoteLeave.Idle, rejected.leave)
        assertEquals("source", File(root, "Source.md").readText())
        assertEquals("taken", File(root, "Taken.md").readText())

        withContext(Dispatchers.Main) {
            viewModel.requestLeave(clearFocus = {})
        }
        awaitState { it.leave == NoteLeave.Ready }
        Unit
    }

    @Test
    fun backWaitsForAnAlreadyQueuedRename() = runBlocking {
        withContext(Dispatchers.Main) {
            viewModel.commitTitle("Renamed")
            viewModel.requestLeave(clearFocus = {})
        }
        val saved = awaitState { it.leave == NoteLeave.Ready }
        assertEquals("Renamed", saved.fileName)
        assertEquals(NotePath("Renamed.md"), saved.rename?.to?.path)
        assertFalse(File(root, "Source.md").exists())
        assertEquals("source", File(root, "Renamed.md").readText())
    }

    @Test
    fun backWithPendingTitleOffersConflictActionsAndCanOverwriteTheOriginalNote() = runBlocking {
        val source = File(root, "Source.md")
        source.writeText("external edit")
        withContext(Dispatchers.Main) {
            viewModel.editor.edit { replace(0, length, "local draft") }
            viewModel.retrySave()
        }
        awaitState { it.isConflict && it.conflictCopy != null }

        withContext(Dispatchers.Main) {
            viewModel.requestLeave(clearFocus = { viewModel.commitTitle("Renamed") })
        }
        val blocked = awaitState { it.leave == NoteLeave.Blocked }
        assertTrue(blocked.isConflict)
        assertEquals("local draft", File(root, checkNotNull(blocked.conflictCopy).relativePath).readText())
        assertEquals("external edit", source.readText())
        assertFalse(File(root, "Renamed.md").exists())

        withContext(Dispatchers.Main) { viewModel.saveThenLeave(force = true) }
        awaitState { it.leave == NoteLeave.Ready }
        assertEquals("local draft", source.readText())
    }

    @Test
    fun backWithPendingTitleOffersRetryAfterWriteFailure() = runBlocking {
        val oversizedDraft = "x".repeat(MAX_MARKDOWN_BYTES.toInt() + 1)
        withContext(Dispatchers.Main) {
            viewModel.editor.edit { replace(0, length, oversizedDraft) }
            viewModel.retrySave()
        }
        awaitState { it.saveProblem == NoteSaveProblem.WriteFailed }

        withContext(Dispatchers.Main) {
            viewModel.requestLeave(clearFocus = { viewModel.commitTitle("Renamed") })
        }
        val blocked = awaitState { it.leave == NoteLeave.Blocked }
        assertEquals(NoteSaveProblem.WriteFailed, blocked.saveProblem)
        assertEquals("source", File(root, "Source.md").readText())
        assertFalse(File(root, "Renamed.md").exists())
        withContext(Dispatchers.Main) {
            assertEquals(oversizedDraft, viewModel.editor.text.toString())
            viewModel.editor.edit { replace(0, length, "smaller draft") }
            viewModel.saveThenLeave()
        }
        awaitState { it.leave == NoteLeave.Ready }
        assertEquals("smaller draft", File(root, "Source.md").readText())
    }

    @Test
    fun foregroundRefreshMakesACleanNoteWaitForBack() = runBlocking {
        withContext(Dispatchers.Main) {
            assertFalse(viewModel.requiresSaveBeforeLeaving(viewModel.uiState.value))
            viewModel.onRouteStarted(noteId, routeVersion = 0)
            assertTrue(viewModel.requiresSaveBeforeLeaving(viewModel.uiState.value))
            viewModel.requestLeave(clearFocus = {}, onAlreadySaved = { error("Refresh is pending") })
        }
        awaitState { it.leave == NoteLeave.Ready }
        Unit
    }

    @Test
    fun newlyTypedWikiLinkResolvesLiveBeforeAndAfterAutosave() = runBlocking {
        val repo = NoteRepository(vaults, RoomNoteIndex(database))
        repo.listNotes()
        withContext(Dispatchers.Main) {
            viewModel.refreshWiki()
        }
        val ready = awaitState { it.wiki.linkables.isNotEmpty() }
        withContext(Dispatchers.Main) {
            viewModel.editor.edit { replace(0, length, "See [[Taken]]") }
        }
        assertEquals("Taken.md", ready.wiki.resolve("Taken")?.path?.relativePath)

        withContext(Dispatchers.Main) {
            viewModel.retrySave()
            viewModel.requestLeave(clearFocus = {})
        }
        awaitState { it.leave == NoteLeave.Ready }
        val targetContext = repo.wikiContext(NoteId(noteId.vaultId, NotePath("Taken.md")))
        assertTrue(targetContext.backlinks.any { it.id == noteId })
    }

    private suspend fun awaitState(predicate: (NoteUiState) -> Boolean): NoteUiState =
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
}
