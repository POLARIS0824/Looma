package com.example.looma.data.note

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.NoteWriteMode
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var vault: Vault
    private lateinit var store: NoteFileStore
    private lateinit var file: File
    private lateinit var id: NoteId

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("vault")
        vault = Vault(VaultId("pinned"), "Pinned", root.absolutePath)
        store = NoteFileStore(vault)
        file = File(root, "note.md").apply { writeText("alpha") }
        id = NoteId(vault.id, NotePath("note.md"))
    }

    @Test
    fun `stored revision hashes raw bytes and leaves bak untouched`() = runBlocking {
        val bytes = "\uFEFF# 笔记\r\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)
        file.writeBytes(bytes)
        val backup = File(root, "note.md.bak").apply { writeText("backup") }

        val note = store.readNote(id)

        assertEquals(bytes.toString(Charsets.UTF_8), note.content)
        assertEquals(NoteRevision.of(bytes), note.revision)
        assertNotEquals(NoteRevision.of(note.content.toByteArray()), note.revision)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("backup", backup.readText())
    }

    @Test
    fun `equal timestamps and lengths do not hide external changes`() = runBlocking {
        val opened = store.readNote(id)
        file.writeText("bravo")
        assertTrue(file.setLastModified(opened.modifiedAt.toEpochMilli()))

        val failure = expectFailure<NoteChangedExternallyException> {
            store.saveNote(id, "mine", NoteWriteMode.Checked(opened.revision))
        }

        assertEquals(NoteRevision.of("bravo".toByteArray()), failure.actualRevision)
        assertEquals("bravo", file.readText())
    }

    @Test
    fun `same content is only a noop after validating revision`() = runBlocking {
        val opened = store.readNote(id)
        val saved = store.saveNote(id, opened.content, NoteWriteMode.Checked(opened.revision))

        assertEquals(opened.revision, saved.revision)
        assertEquals(opened.modifiedAt, saved.modifiedAt)
        assertFalse(File(root, ".looma/conflicts").exists())
        expectFailure<NoteChangedExternallyException> {
            store.saveNote(id, opened.content, NoteWriteMode.Checked(NoteRevision.Unknown))
        }
        assertEquals(opened.content, file.readText())
    }

    @Test
    fun `checked save returns new raw revision`() = runBlocking {
        val opened = store.readNote(id)

        val saved = store.saveNote(id, "mine", NoteWriteMode.Checked(opened.revision))

        assertEquals("mine", file.readText())
        assertEquals(NoteRevision.of(file.readBytes()), saved.revision)
    }

    @Test
    fun `overwrite backs up exact BOM and undecodable bytes`() = runBlocking {
        val bytes = "\uFEFFexternal\r\n".toByteArray() + byteArrayOf(0xFF.toByte())
        file.writeBytes(bytes)
        val observed = store.readNote(id)

        val saved = store.saveNote(id, "mine", NoteWriteMode.Overwrite(observed.revision))

        val backup = File(root, ".looma/conflicts").listFiles()!!.single()
        assertTrue(CONFLICTED_COPY.matches(backup.name))
        assertTrue(backup.name.startsWith("note (conflicted copy "))
        assertArrayEquals(bytes, backup.readBytes())
        assertEquals("mine", file.readText())
        assertEquals(NoteRevision.of("mine".toByteArray()), saved.revision)
    }

    @Test
    fun `overwrite backups mirror the note folder and never replace each other`() = runBlocking {
        val nestedId = NoteId(vault.id, NotePath("项目/周报.md"))
        val nested = File(root, "项目/周报.md").apply {
            parentFile.mkdirs()
            writeText("first")
        }
        val first = store.saveNote(nestedId, "second", NoteWriteMode.Overwrite(store.readNote(nestedId).revision))
        store.saveNote(nestedId, "third", NoteWriteMode.Overwrite(first.revision))

        val backups = File(root, ".looma/conflicts/项目").listFiles()!!.sortedBy { it.name }
        assertEquals(2, backups.size)
        assertTrue(backups.all { it.name.startsWith("周报 (conflicted copy ") && CONFLICTED_COPY.matches(it.name) })
        assertEquals(setOf("first", "second"), backups.map { it.readText() }.toSet())
        assertEquals("third", nested.readText())
    }

    @Test
    fun `overwrite backups of a long name stay within the filename byte limit`() = runBlocking {
        val longName = "长".repeat(80)
        val longId = NoteId(vault.id, NotePath("$longName.md"))
        File(root, "$longName.md").writeText("first")
        val first = store.saveNote(longId, "second", NoteWriteMode.Overwrite(store.readNote(longId).revision))
        store.saveNote(longId, "third", NoteWriteMode.Overwrite(first.revision))

        val backups = File(root, ".looma/conflicts").listFiles()!!.toList()
        assertEquals(2, backups.size)
        assertTrue(backups.all { CONFLICTED_COPY.matches(it.name) && it.name.startsWith("长") })
        assertTrue(backups.all { it.name.toByteArray(Charsets.UTF_8).size <= 255 })
        assertEquals(setOf("first", "second"), backups.map { it.readText() }.toSet())
    }

    @Test
    fun `overwrite rejects the wrong observed hash before backing up`() = runBlocking {
        val observed = store.readNote(id)
        file.writeText("external")

        val failure = expectFailure<NoteChangedExternallyException> {
            store.saveNote(id, "mine", NoteWriteMode.Overwrite(observed.revision))
        }

        assertEquals(NoteRevision.of(file.readBytes()), failure.actualRevision)
        assertEquals("external", file.readText())
        assertFalse(File(root, ".looma/conflicts").exists())
    }

    @Test
    fun `backup failure prevents overwriting target`() = runBlocking {
        val bytes = "\uFEFFexternal".toByteArray()
        file.writeBytes(bytes)
        val observed = store.readNote(id)
        assertTrue(File(root, ".looma").mkdir())
        val obstruction = File(root, ".looma/conflicts").apply { writeText("not a directory") }

        expectFailure<NoteBackupFailedException> {
            store.saveNote(id, "mine", NoteWriteMode.Overwrite(observed.revision))
        }

        assertArrayEquals(bytes, file.readBytes())
        assertEquals("not a directory", obstruction.readText())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `first conflict copy sits beside the original and leaves it untouched`() = runBlocking {
        val original = file.readBytes()

        val copy = store.writeConflictCopy(id, "unsaved\r\n工作", null)

        assertEquals(root, copy.file.parentFile)
        assertTrue(CONFLICTED_COPY.matches(copy.file.name))
        assertTrue(copy.file.name.startsWith("note (conflicted copy "))
        assertEquals("unsaved\r\n工作", copy.file.readText())
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `an existing conflict copy is overwritten in place`() = runBlocking {
        val first = store.writeConflictCopy(id, "first", null)

        val second = store.writeConflictCopy(id, "second", first.id.path)

        assertEquals(first.file, second.file)
        assertEquals("second", first.file.readText())
        assertEquals(1, conflictFiles().size)
    }

    @Test
    fun `a deleted conflict copy is created again`() = runBlocking {
        val first = store.writeConflictCopy(id, "first", null)
        assertTrue(first.file.delete())

        val second = store.writeConflictCopy(id, "second", first.id.path)

        assertTrue(second.file.isFile)
        assertEquals("second", second.file.readText())
        assertEquals(1, conflictFiles().size)
    }

    @Test
    fun `a taken conflict name gets a numeric suffix`() = runBlocking {
        var suffixed: StoredMarkdown? = null
        repeat(5) {
            if (suffixed != null) return@repeat
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss"))
            val blocker = File(root, "note (conflicted copy $timestamp).md").apply { writeText("blocker") }
            val copy = store.writeConflictCopy(id, "mine", null)
            if (copy.file.name.endsWith(" 2.md")) {
                suffixed = copy
            } else {
                copy.file.delete()
            }
            blocker.delete()
        }

        val copy = requireNotNull(suffixed)
        assertTrue(CONFLICTED_COPY.matches(copy.file.name))
        assertEquals("mine", copy.file.readText())
    }

    @Test
    fun `oversized sources fail before restore`() = runBlocking {
        val bytes = ByteArray(MAX_MARKDOWN_BYTES.toInt() + 1)
        file.writeBytes(bytes)

        val trashed = store.deleteNote(id)
        expectFailure<NoteTooLargeException> { store.restoreNote(trashed.trashId) }
        assertFalse(file.exists())
        val record = VaultFileOps(root).trashed().single()
        assertArrayEquals(bytes, record.file.readBytes())
        assertTrue(record.metaFile.isFile)
    }



    @Test
    fun `restore returns the raw revision`() = runBlocking {
        val bytes = "\uFEFFraw\r\n".toByteArray()
        file.writeBytes(bytes)
        val revision = NoteRevision.of(bytes)

        val trashed = store.deleteNote(id)
        val restored = store.restoreNote(trashed.trashId)
        assertEquals(revision, restored.revision)
        assertArrayEquals(bytes, restored.file.readBytes())
    }

    @Test
    fun `store is pinned and refuses another vault id`() = runBlocking {
        val otherRoot = temporaryFolder.newFolder("other")
        val otherVault = Vault(VaultId("other"), "Other", otherRoot.absolutePath)
        File(otherRoot, "note.md").writeText("other")
        val otherStore = NoteFileStore(otherVault)

        assertEquals("alpha", store.readNote(id).content)
        assertEquals("other", otherStore.readNote(NoteId(otherVault.id, id.path)).content)
        val failure = expectFailure<VaultFileException> {
            store.readNote(NoteId(otherVault.id, id.path))
        }
        assertEquals(VaultFileFailure.NOT_FOUND, failure.failure)
        assertEquals(vault.id, store.createNote("created").id.vaultId)
    }

    private fun conflictFiles(): List<File> =
        root.listFiles().orEmpty().filter { CONFLICTED_COPY.matches(it.name) }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private companion object {
        val CONFLICTED_COPY = Regex(""".+ \(conflicted copy \d{4}-\d{2}-\d{2} \d{6}\)( \d+)?\.md""")
    }
}
