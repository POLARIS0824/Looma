package com.example.looma.data.vault

import android.os.Environment
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.looma.data.index.NoteIndexDatabase
import com.example.looma.data.search.SearchHistoryRepository
import com.example.looma.domain.model.Vault
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultIdentityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vaults = VaultRepository(context)
    private val directory = File(Environment.getExternalStorageDirectory(), "looma-identity-${UUID.randomUUID()}")
    private val firstVault = File(directory, "A/Notes")
    private val secondVault = File(directory, "B/Notes")
    private var originalSelection: Vault? = null

    @Before
    fun setUp() = runBlocking {
        originalSelection = vaults.getCurrentVault()
        check(firstVault.mkdirs() && secondVault.mkdirs())
    }

    @After
    fun tearDown() = runBlocking {
        try {
            val original = originalSelection
            if (original == null) {
                vaults.clearCurrentVault()
            } else {
                VaultPreferences(context).saveVault(original.id.value, original.name, original.rootPath)
            }
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test
    fun switchingBackToAKnownDirectoryKeepsItsIdAndSearchHistory() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, NoteIndexDatabase::class.java).build()
        try {
            val first = vaults.setCurrentVault(firstVault.path)
            val history = SearchHistoryRepository(vaults, database.searchHistoryDao())
            history.record("query in first vault")

            val second = vaults.setCurrentVault(secondVault.path)
            assertNotEquals(first.id, second.id)
            assertEquals(emptyList<String>(), history.recent())
            history.record("query in second vault")

            val reopenedRepository = VaultRepository(context)
            val reopened = reopenedRepository.setCurrentVault(File(firstVault, ".").path)
            val reopenedHistory = SearchHistoryRepository(reopenedRepository, database.searchHistoryDao())
            assertEquals(first.id, reopened.id)
            assertEquals(listOf("query in first vault"), reopenedHistory.recent())
        } finally {
            database.close()
        }
    }

    @Test
    fun clearingTheActiveVaultKeepsTheIdentityOfItsDirectory() = runBlocking {
        val first = vaults.setCurrentVault(firstVault.path)

        vaults.clearCurrentVault()
        val reopenedRepository = VaultRepository(context)

        assertEquals(null, reopenedRepository.getCurrentVault())
        assertEquals(first.id, reopenedRepository.setCurrentVault(firstVault.path).id)
    }
}
