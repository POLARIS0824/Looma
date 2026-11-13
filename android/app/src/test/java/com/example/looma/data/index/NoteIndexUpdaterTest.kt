package com.example.looma.data.index

import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteIndexUpdaterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a failed update rebuilds from the files on disk`() = runBlocking {
        val root = temporaryFolder.newFolder("vault")
        File(root, "note.md").writeText("# 标题\n")
        val vault = Vault(VaultId("vault"), "vault", root.path)
        val index = RecordingIndex(failures = 1)

        NoteIndexUpdater(index).apply(vault, IndexDelta(vault.id))

        assertEquals(listOf(NotePath("note.md")), index.upserts)
    }

    @Test
    fun `a failed rebuild is swallowed`() = runBlocking {
        val root = temporaryFolder.newFolder("vault")
        File(root, "note.md").writeText("# 标题\n")
        val vault = Vault(VaultId("vault"), "vault", root.path)
        val index = RecordingIndex(failures = 2)

        NoteIndexUpdater(index).apply(vault, IndexDelta(vault.id))

        assertEquals(emptyList<NotePath>(), index.upserts)
    }

    private class RecordingIndex(private var failures: Int) : NoteIndex {
        val upserts = mutableListOf<NotePath>()

        override suspend fun load(vaultId: VaultId): List<IndexedNote> = emptyList()

        override suspend fun apply(delta: IndexDelta) {
            if (failures > 0) {
                failures--
                throw IOException("index unavailable")
            }
            upserts += delta.upserts.map { it.id.path }
        }
    }
}
