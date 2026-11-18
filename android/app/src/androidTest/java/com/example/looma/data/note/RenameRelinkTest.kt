package com.example.looma.data.note

import android.os.Environment
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.looma.data.image.resolveAttachmentReference
import com.example.looma.data.index.NoteIndexDatabase
import com.example.looma.data.index.RoomNoteIndex
import com.example.looma.data.vault.VaultPreferences
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.markdown.ImageSource
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenameRelinkTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vaultId = "looma-relink-test"

    private lateinit var root: File
    private var original: Vault? = null
    private lateinit var vaults: VaultRepository
    private lateinit var database: NoteIndexDatabase
    private lateinit var repository: NoteRepository

    @Before
    fun setUp() {
        runBlocking {
            vaults = VaultRepository(context)
            original = vaults.getCurrentVault()
            root = File(Environment.getExternalStorageDirectory(), "looma-relink-${UUID.randomUUID()}")
            assertTrue(root.mkdirs())
            write("Target.md", "# Target\n\n目标笔记。\n")
            write("From.md", "# From\n\n见 [[Target]] 与 [[Target|别名]]。\n")
            write("Other.md", "# Other\n\n[[Unrelated]]\n")
            write("Self.md", "# Self\n\n[[Self]]\n")

            VaultPreferences(context).saveVault(vaultId, "relink", root.path)
            database = Room.inMemoryDatabaseBuilder(context, NoteIndexDatabase::class.java).build()
            repository = NoteRepository(vaults, RoomNoteIndex(database))
            repository.listNotes()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            if (::database.isInitialized) database.close()
            if (::root.isInitialized) root.deleteRecursively()
            if (::vaults.isInitialized) {
                val saved = original
                if (saved == null) {
                    vaults.clearCurrentVault()
                } else {
                    VaultPreferences(context).saveVault(saved.id.value, saved.name, saved.rootPath)
                }
            }
        }
    }

    @Test
    fun renamingRewritesEveryReferrerAndTheIndexFollows() = runBlocking {
        val outcome = repository.renameNote(id("Target.md"), "Renamed")

        assertEquals(2, outcome.linksUpdated)
        assertEquals(0, outcome.linksFailed)
        assertEquals("Renamed", outcome.note.id.path.relativePath.substringAfterLast('/').removeSuffix(".md"))

        assertEquals("# From\n\n见 [[Renamed]] 与 [[Renamed|别名]]。\n", read("From.md"))
        assertEquals("# Other\n\n[[Unrelated]]\n", read("Other.md"))
        assertEquals("# Target\n\n目标笔记。\n", read("Renamed.md"))

        val backlinks = repository.wikiContext(id("Renamed.md")).backlinks
        assertEquals(listOf("From.md"), backlinks.map { it.id.path.relativePath })
    }

    @Test
    fun anExternalEditWithUnchangedMetadataIsRelinkedDespiteTheOldIndex() = runBlocking {
        write("From.md", "plain text")
        val referrer = File(root, "From.md")
        Files.setLastModifiedTime(referrer.toPath(), FileTime.fromMillis(1_750_000_000_000L))
        repository.listNotes()
        assertTrue(repository.wikiContext(id("Target.md")).backlinks.isEmpty())
        val timestamp = referrer.lastModified()
        val length = referrer.length()

        referrer.writeText("[[Target]]")
        Files.setLastModifiedTime(referrer.toPath(), FileTime.fromMillis(timestamp))
        assertEquals(timestamp, referrer.lastModified())
        assertEquals(length, referrer.length())

        assertEquals(1, repository.renameNote(id("Target.md"), "Renamed").linksUpdated)
        assertEquals("[[Renamed]]", referrer.readText())
    }

    @Test
    fun aNoteThatLinksToItselfIsRewrittenToo() = runBlocking {
        repository.renameNote(id("Self.md"), "Myself")

        assertEquals("# Self\n\n[[Myself]]\n", read("Myself.md"))
    }

    @Test
    fun aReferrerThatVanishedIsNotTakenFromTheStaleIndex() = runBlocking {
        File(root, "From.md").delete()

        val outcome = repository.renameNote(id("Target.md"), "Renamed")

        assertEquals(0, outcome.linksUpdated)
        assertEquals(0, outcome.linksFailed)
        assertTrue(File(root, "Renamed.md").isFile)
    }

    @Test
    fun movingToAnotherFolderKeepsBareNamesAndRewritesPathReferences() = runBlocking {
        File(root, "Inbox").mkdirs()
        write("Inbox/Target.md", "# Target\n\n目标笔记。\n")
        File(root, "From.md").writeText("# From\n\n见 [[Target]] 与 [[Inbox/Target]]。\n")
        File(root, "Target.md").delete()
        repository.listNotes()

        val outcome = repository.moveNote(id("Inbox/Target.md"), FolderPath("Archive"))

        assertEquals(1, outcome.linksUpdated)
        assertEquals(
            "# From\n\n见 [[Target]] 与 [[Archive/Target]]。\n",
            read("From.md"),
        )
    }

    @Test
    fun aReferrerCreatedAfterTheLastScanIsRewritten() = runBlocking {
        write("Late.md", "[[Target|late]]\n")

        repository.renameNote(id("Target.md"), "Renamed")

        assertEquals("[[Renamed|late]]\n", read("Late.md"))
        assertTrue(repository.wikiContext(id("Renamed.md")).backlinks.any { it.id.path.relativePath == "Late.md" })
    }

    @Test
    fun folderRenameUpdatesBacklinks() = runBlocking {
        File(root, "Inbox").mkdirs()
        write("Inbox/Nested.md", "# Nested\noriginal\n")
        write("Ref.md", "[[Inbox/Nested]]\n")

        repository.renameFolder(FolderPath("Inbox"), "Archive")

        assertEquals("[[Archive/Nested]]\n", read("Ref.md"))
        val opened = repository.openNote(id("Archive/Nested.md"))
        assertEquals("# Nested\noriginal\n", opened.content)
        val notes = repository.listNotes().items.map { it.summary.id.path.relativePath }
        assertTrue("Archive/Nested.md" in notes)
        assertTrue("Inbox/Nested.md" !in notes)
    }

    @Test
    fun caseOnlyRenameUpdatesFileAndIndexOnSharedStorage() = runBlocking {
        repository.renameNote(id("Target.md"), "target")
        assertTrue("target.md" in root.list().orEmpty())
        assertTrue("Target.md" !in root.list().orEmpty())

        repository.renameNote(id("target.md"), "TARGET")
        assertTrue("TARGET.md" in root.list().orEmpty())
        assertTrue("target.md" !in root.list().orEmpty())

        val notes = repository.listNotes().items.map { it.summary.id.path.relativePath }
        assertTrue("TARGET.md" in notes)
        assertTrue("target.md" !in notes)
        assertTrue("Target.md" !in notes)
        val backlinks = repository.wikiContext(id("TARGET.md")).backlinks
        assertEquals(listOf("From.md"), backlinks.map { it.id.path.relativePath })
    }

    private fun id(path: String): NoteId {
        val vault = runBlocking { vaults.getCurrentVault() }!!
        return NoteId(vault.id, NotePath(path))
    }

    @Test
    fun movingAndUndoingAcrossDistinctCaseFoldedParentsKeepsTheOriginalAttachment() = runBlocking {
        write(".obsidian/app.json", """{"attachmentFolderPath":"./assets","newLinkFormat":"relative"}""")
        write("i/assets/photo.png", "original image")
        write("\u0130/assets/photo.png", "shadow image")
        assumeTrue(File(root, "i").canonicalPath != File(root, "\u0130").canonicalPath)
        val attachment = File(root, "i/assets/photo.png")
        val reference = resolveAttachmentReference(root, NotePath("i/N.md"), attachment)
        write("i/N.md", "![[$reference|320]]")

        val outcome = repository.moveNote(id("i/N.md"), FolderPath("\u0130"))
        assertEquals(NotePath("\u0130/N.md"), outcome.note.id.path)
        assertEquals(1, outcome.linksUpdated)
        val moved = File(root, outcome.note.id.path.relativePath)
        val movedTarget = moved.readText().removePrefix("![[").removeSuffix("]]")
        assertEquals(attachment.canonicalPath, (resolveImageSource(root.path, moved.path, movedTarget) as? ImageSource.Local)?.path)

        repository.moveNoteToOriginalPath(outcome.note.id, NotePath("i/N.md"))
        val restored = File(root, "i/N.md")
        val restoredTarget = restored.readText().removePrefix("![[").removeSuffix("]]")
        assertEquals(attachment.canonicalPath, (resolveImageSource(root.path, restored.path, restoredTarget) as? ImageSource.Local)?.path)
        assertEquals("shadow image", read("\u0130/assets/photo.png"))
    }

    @Test
    fun abandoningLegacyCrossParentHopPreservesTheOriginalThroughTheRepository() = runBlocking {
        write("i/Original.md", "original")
        File(root, "\u0130").mkdir()
        assumeTrue(File(root, "i").canonicalPath != File(root, "\u0130").canonicalPath)
        val relocation = VaultRelocation(vaults.requireCurrentVault())
        relocation.afterIo = { if (it == "intent") throw IOException("interrupted after publishing the move") }
        val stalled = assertThrows(RelocationStalledException::class.java) {
            relocation.moveNoteToOriginalPath(id("i/Original.md"), NotePath("\u0130/Original.md"))
        }
        val journalId = stalled.stalled.journalId
        val hop = File(root, "i/.looma-case-$journalId")
        Files.move(File(root, "i/Original.md").toPath(), hop.toPath())
        write("\u0130/Original.md", "external")

        assertEquals(journalId, relocation.recoverPending().stalled?.journalId)
        repository.abandonStalledRelocation(journalId)

        assertFalse(hop.exists())
        assertEquals("original", read(".looma/abandoned-operations/$journalId/recovered/Original.md"))
        assertEquals("external", read("\u0130/Original.md"))
        assertNull(relocation.recoverPending().stalled)
    }

    private fun write(name: String, text: String) {
        File(root, name).apply { parentFile?.mkdirs() }.writeText(text)
    }

    private fun read(name: String): String = File(root, name).readText()
}
