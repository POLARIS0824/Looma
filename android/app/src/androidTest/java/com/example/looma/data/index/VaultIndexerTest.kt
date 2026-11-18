package com.example.looma.data.index

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.looma.data.note.MarkdownParser
import com.example.looma.data.note.NoteFileStore
import com.example.looma.data.note.savedNote
import com.example.looma.data.vault.VaultWriteCoordinator
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteWriteMode
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.search.SearchSignature
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultIndexerTest {

    private lateinit var database: NoteIndexDatabase
    private lateinit var vaultDir: File
    private lateinit var vault: Vault
    private lateinit var index: NoteIndex
    private lateinit var indexer: VaultIndexer

    private val vaultId = VaultId("vault")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, NoteIndexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vaultDir = File(context.cacheDir, "indexer-test-${System.nanoTime()}").apply { mkdirs() }
        vault = Vault(id = vaultId, name = "vault", rootPath = vaultDir.path)
        index = RoomNoteIndex(database)
        indexer = VaultIndexer(vaultDir, vault, index)
    }

    @After
    fun tearDown() {
        database.close()
        vaultDir.deleteRecursively()
    }

    @Test
    fun scanIsIncrementalAndFollowsFileChanges() = runBlocking {
        val tagged = File(vaultDir, "tagged.md").apply {
            writeText("# 标题\n\n#project\n\n链接 [[Target]]\n")
        }
        File(vaultDir, "plain.md").writeText("# 另一篇\n\n正文\n")

        val first = indexer.scan()
        assertEquals(2, first.report.filesRead)
        assertEquals(2, first.notes.size)
        val dao = database.noteIndexDao()
        val taggedTags = dao.tags(vaultId.value).filter { it.notePath == "tagged.md" }
        val taggedLinks = dao.links(vaultId.value).filter { it.notePath == "tagged.md" }
        assertEquals(listOf("project"), taggedTags.map { it.tag })
        assertEquals(listOf("Target"), taggedLinks.map { it.target })
        assertEquals(listOf(false), taggedLinks.map { it.embedded })

        val second = indexer.scan()
        assertEquals(0, second.report.filesRead)
        assertEquals(0, second.report.filesRemoved)
        assertEquals(2, second.notes.size)

        val indexedAtBefore = dao.notes(vaultId.value).associate { it.path to it.indexedAt }

        Thread.sleep(20)
        tagged.setLastModified(tagged.lastModified() + 1_000)
        val third = indexer.scan()
        assertEquals(1, third.report.filesRead)
        assertEquals(2, third.notes.size)

        val indexedAtAfter = dao.notes(vaultId.value).associate { it.path to it.indexedAt }
        assertTrue(indexedAtAfter.getValue("tagged.md") > indexedAtBefore.getValue("tagged.md"))
        assertEquals(indexedAtBefore.getValue("plain.md"), indexedAtAfter.getValue("plain.md"))

        File(vaultDir, "plain.md").delete()
        val fourth = indexer.scan()
        assertEquals(1, fourth.report.filesRemoved)
        assertEquals(1, fourth.notes.size)
        assertEquals(listOf("tagged.md"), fourth.notes.map { it.id.path.relativePath })

        val externalFolder = File(vaultDir, "external").apply { mkdir() }
        File(externalFolder, "new.MD").writeText("# Added externally\n")
        val fifth = indexer.scan()
        assertEquals(1, fifth.report.filesRead)
        assertEquals(1, fifth.report.filesKept)
        assertEquals(setOf("tagged.md", "external/new.MD"), fifth.notes.map { it.id.path.relativePath }.toSet())
        assertEquals(listOf("external"), fifth.folders.map { it.path.relativePath })
    }

    @Test
    fun sameSizeAndMtimeIsTreatedAsUnchanged() = runBlocking {
        val file = File(vaultDir, "note.md")
        file.writeText("# 原标题\n\n旧正文\n")
        indexer.scan()

        val mtime = file.lastModified()
        file.writeText("# 新标题\n\n新正文\n")
        file.setLastModified(mtime)

        val scan = indexer.scan()
        assertEquals(0, scan.report.filesRead)
        assertEquals("原标题", database.noteIndexDao().notes(vaultId.value).single().title)
    }

    @Test
    fun scanRebuildsLegacySignaturesForUnchangedFiles() = runBlocking {
        val file = File(vaultDir, "note.md")
        file.writeText("---\rmetadata\r---\nneedle\n---\n")
        val note = indexer.scan().notes.single()
        index.apply(IndexDelta(vaultId, upserts = listOf(note), signatures = mapOf(note.id.path to ByteArray(512))))

        assertTrue(index.searchSignatures(vaultId).isEmpty())
        assertEquals(1, indexer.scan().report.filesRead)
        val signature = index.searchSignatures(vaultId).getValue(note.id.path)
        assertTrue(SearchSignature.mayContain(signature.bytes, "needle"))
        assertEquals(0, indexer.scan().report.filesRead)
    }

    @Test
    fun scanRefreshesOutdatedSignatureVersionWithoutFileTimestampChange() = runBlocking {
        val file = File(vaultDir, "note.md")
        file.writeText("---\n\"tags\": [refreshed]\n---\n[![alt](cover.png)](https://example.com)\n")
        File(vaultDir, "cover.png").writeBytes(byteArrayOf(1))
        val note = indexer.scan().notes.single()
        val staleNote = note.copy(tags = emptyList(), coverPath = null)
        val legacyVersionSignature = ByteArray(SearchSignature.STORED_BYTE_SIZE).also { it[it.lastIndex] = 1 }
        index.apply(
            IndexDelta(
                vaultId = vaultId,
                upserts = listOf(staleNote),
                signatures = mapOf(note.id.path to legacyVersionSignature),
            ),
        )

        val scan = indexer.scan()
        assertEquals(1, scan.report.filesRead)
        val refreshed = index.load(vaultId).single()
        assertEquals(listOf("refreshed"), refreshed.tags)
        assertEquals(File(vaultDir, "cover.png").canonicalPath, refreshed.coverPath)
        assertEquals(0, indexer.scan().report.filesRead)
    }

    @Test
    fun scanDoesNotReplaceANewerSave() = runBlocking {
        val file = File(vaultDir, "note.md").apply {
            writeText("# Older\n\n#old [[Old]]\n")
        }
        val originalFingerprint = FileFingerprint(file.length(), file.lastModified())
        val scanReady = CompletableDeferred<Unit>()
        val resumeScan = CompletableDeferred<Unit>()
        val scan = async { pausedIndexer(scanReady, resumeScan).scan() }

        withTimeout(10_000) { scanReady.await() }
        try {
            VaultWriteCoordinator.write(vault) {
                val store = NoteFileStore(vault)
                val id = NoteId(vaultId, NotePath(file.name))
                val mode = NoteWriteMode.Checked(store.readNote(id).revision)
                val saved = store.saveNote(id, "# Newer\n\n#new [[New]]\n", mode)
                assertTrue(file.setLastModified(originalFingerprint.modifiedAtMillis))
                val note = MarkdownParser().savedNote(saved).toIndexedNote().copy(
                    modifiedAt = Instant.ofEpochMilli(originalFingerprint.modifiedAtMillis),
                    fingerprint = originalFingerprint,
                )
                NoteIndexUpdater(index).apply(vault, IndexDelta(vaultId, upserts = listOf(note)))
            }
        } finally {
            resumeScan.complete(Unit)
        }

        val result = scan.await()
        val latest = index.load(vaultId).single()
        assertEquals("Newer", latest.title)
        assertEquals(listOf("new"), latest.tags)
        assertEquals(listOf("New"), latest.links.map { it.target })
        assertEquals(latest, result.notes.single())
    }

    @Test
    fun scanDoesNotRemoveARestoredNote() = runBlocking {
        val file = File(vaultDir, "note.md").apply { writeText("# Restored\n") }
        indexer.scan()
        val notes = index.load(vaultId)
        val trash = NoteFileStore(vault).deleteNote(notes.single().id)
        val scanReady = CompletableDeferred<Unit>()
        val resumeScan = CompletableDeferred<Unit>()
        val scan = async { pausedIndexer(scanReady, resumeScan).scan() }

        withTimeout(10_000) { scanReady.await() }
        try {
            VaultWriteCoordinator.write(vault) {
                val restored = NoteFileStore(vault).restoreNote(trash.trashId)
                val note = MarkdownParser().savedNote(restored).toIndexedNote()
                NoteIndexUpdater(index).apply(vault, IndexDelta(vaultId, upserts = listOf(note)))
            }
        } finally {
            resumeScan.complete(Unit)
        }

        val result = scan.await()
        assertEquals(listOf(file.name), index.load(vaultId).map { it.id.path.relativePath })
        assertEquals(listOf(file.name), result.notes.map { it.id.path.relativePath })
        assertEquals(0, result.report.filesRemoved)
    }

    @Test
    fun scanWithoutChangesReturnsANewerIndexUpdate() = runBlocking {
        val file = File(vaultDir, "note.md").apply { writeText("# Older\n") }
        indexer.scan()
        val scanReady = CompletableDeferred<Unit>()
        val resumeScan = CompletableDeferred<Unit>()
        val scan = async { pausedIndexer(scanReady, resumeScan).scan() }

        withTimeout(10_000) { scanReady.await() }
        try {
            VaultWriteCoordinator.write(vault) {
                val store = NoteFileStore(vault)
                val id = NoteId(vaultId, NotePath(file.name))
                val mode = NoteWriteMode.Checked(store.readNote(id).revision)
                val saved = store.saveNote(id, "# Newer and longer\n", mode)
                val note = MarkdownParser().savedNote(saved).toIndexedNote()
                NoteIndexUpdater(index).apply(vault, IndexDelta(vaultId, upserts = listOf(note)))
            }
        } finally {
            resumeScan.complete(Unit)
        }

        assertEquals("Newer and longer", scan.await().notes.single().title)
    }

    private fun pausedIndexer(
        scanReady: CompletableDeferred<Unit>,
        resumeScan: CompletableDeferred<Unit>,
    ): VaultIndexer {
        val pausedIndex = object : NoteIndex by index {
            override suspend fun applyIfVersion(delta: IndexDelta, expectedVersion: Long): Boolean {
                if (scanReady.complete(Unit)) withTimeout(10_000) { resumeScan.await() }
                return index.applyIfVersion(delta, expectedVersion)
            }
        }
        return VaultIndexer(vaultDir, vault, pausedIndex)
    }
}
