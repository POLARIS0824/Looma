package com.example.looma.data.sync

import com.example.looma.data.s3.S3RequestSigner
import com.example.looma.domain.sync.SyncFileFilter
import com.example.looma.domain.sync.SyncRelativePath
import com.example.looma.domain.sync.SyncSkipReason
import com.example.looma.domain.sync.SyncSkippedItem
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncLocalStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("vault")
    }

    @Test
    fun `scanLocalFiles skips hidden dirs, flags junk as InvalidPath, and marks FileTooLarge`() {
        writeVaultFile(".looma/trash/deleted.md", "trashed")
        writeVaultFile(".obsidian/workspace.json", "{}")
        writeVaultFile(".hidden-note.md", "secret")
        writeVaultFile("draft.tmp", "temp")
        writeVaultFile("~\$doc.md", "lock")
        writeVaultFile("sub/Thumbs.db", "thumb")
        val valid = writeVaultFile("notes/hello.md", "# Hello\n")

        val huge = File(root, "huge.bin")
        RandomAccessFile(huge, "rw").use { it.setLength(SyncFileFilter.MAX_SYNC_FILE_BYTES + 1L) }

        val result = SyncLocalStore.scanLocalFiles(root) {}

        assertTrue(result.complete)
        assertEquals(setOf(SyncRelativePath("notes/hello.md")), result.files.keys)
        assertEquals(valid.length(), result.files.getValue(SyncRelativePath("notes/hello.md")).sizeBytes)
        assertTrue(result.skipped.contains(SyncSkippedItem("draft.tmp", SyncSkipReason.InvalidPath)))
        assertTrue(result.skipped.contains(SyncSkippedItem("~\$doc.md", SyncSkipReason.InvalidPath)))
        assertTrue(result.skipped.contains(SyncSkippedItem("sub/Thumbs.db", SyncSkipReason.InvalidPath)))
        assertTrue(result.skipped.contains(SyncSkippedItem("huge.bin", SyncSkipReason.FileTooLarge)))
    }

    @Test
    fun `scanLocalFiles sets complete false when directory depth reaches MAX_SYNC_DEPTH`() {
        var dir = root
        for (i in 1..SyncFileFilter.MAX_SYNC_DEPTH) {
            dir = File(dir, "d$i")
        }
        assertTrue(dir.mkdirs())
        File(dir.parentFile, "at-limit.md").writeText("ok")
        File(dir, "too-deep.md").writeText("deep")

        val result = SyncLocalStore.scanLocalFiles(root) {}

        assertFalse(result.complete)
        val expectedPath = SyncRelativePath((1 until SyncFileFilter.MAX_SYNC_DEPTH).joinToString("/") { "d$it" } + "/at-limit.md")
        assertEquals(setOf(expectedPath), result.files.keys)
    }

    @Test
    fun `commitDownloadedNew creates nested file and handles AlreadyIdentical`() {
        val path = SyncRelativePath("folder/sub/note.md")
        val content = "remote body"
        val staged1 = stageText(content)
        val sha = sha256Of(content)

        val first = SyncLocalStore.commitDownloadedNew(root, path, staged1, sha)
        assertTrue(first is LocalCommitResult.Committed)
        assertEquals(content, File(root, path.value).readText())
        assertFalse(staged1.exists())

        val staged2 = stageText(content)
        val second = SyncLocalStore.commitDownloadedNew(root, path, staged2, sha)
        assertTrue(second is LocalCommitResult.AlreadyIdentical)
        assertFalse(staged2.exists())
    }

    @Test
    fun `commitDownloadedUpdate overwrites when sha matches and creates conflict copy when modified`() {
        val path = SyncRelativePath("note.md")
        val target = writeVaultFile(path.value, "v1")
        val shaV1 = sha256Of("v1")

        val stagedV2 = stageText("v2")
        val overwritten = SyncLocalStore.commitDownloadedUpdate(root, path, stagedV2, sha256Of("v2"), shaV1)
        assertTrue(overwritten is LocalCommitResult.Committed)
        assertEquals("v2", target.readText())

        target.writeText("local-edit")
        val stagedV3 = stageText("remote-v3")
        val conflicted = SyncLocalStore.commitDownloadedUpdate(root, path, stagedV3, sha256Of("remote-v3"), sha256Of("v2"))
        assertTrue(conflicted is LocalCommitResult.CommittedWithConflictCopy)
        conflicted as LocalCommitResult.CommittedWithConflictCopy
        assertEquals("remote-v3", target.readText())
        assertEquals("local-edit", conflicted.conflictFile.readText())
    }

    @Test
    fun `commitConflictResolution truncates CJK conflict filenames to 255 UTF-8 bytes and increments suffix`() {
        val longCjkStem = "笔".repeat(83)
        val path = SyncRelativePath("$longCjkStem.md")
        writeVaultFile(path.value, "local-1")

        val first = SyncLocalStore.commitConflictResolution(
            root = root,
            path = path,
            stagedRemoteFile = stageText("remote-1"),
            downloadedSha256 = sha256Of("remote-1"),
        )
        assertTrue(first is LocalCommitResult.CommittedWithConflictCopy)
        first as LocalCommitResult.CommittedWithConflictCopy
        assertTrue(first.conflictFile.name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(first.conflictFile.name.endsWith(".md"))
        assertEquals("local-1", first.conflictFile.readText())
        assertEquals("remote-1", first.file.readText())

        first.file.writeText("local-2")
        val second = SyncLocalStore.commitConflictResolution(
            root = root,
            path = path,
            stagedRemoteFile = stageText("remote-2"),
            downloadedSha256 = sha256Of("remote-2"),
        )
        assertTrue(second is LocalCommitResult.CommittedWithConflictCopy)
        second as LocalCommitResult.CommittedWithConflictCopy
        assertTrue(second.conflictFile.name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(first.conflictFile.exists())
        assertTrue(second.conflictFile.exists())
        assertTrue(first.conflictFile != second.conflictFile)
        assertEquals("local-2", second.conflictFile.readText())
    }

    @Test
    fun `deleteLocalIfUnchanged trashes markdown, deletes non-markdown, prunes parents, and checks sha`() {
        val mdPath = SyncRelativePath("a/b/note.md")
        val mdFile = writeVaultFile(mdPath.value, "hello")
        assertEquals(
            LocalDeleteResult.LocalModifiedAbort,
            SyncLocalStore.deleteLocalIfUnchanged(root, mdPath, "0".repeat(64)),
        )
        assertTrue(mdFile.exists())

        assertEquals(
            LocalDeleteResult.Deleted,
            SyncLocalStore.deleteLocalIfUnchanged(root, mdPath, sha256Of("hello")),
        )
        assertFalse(mdFile.exists())
        assertFalse(File(root, "a").exists())
        val trashDir = File(root, ".looma/trash")
        assertTrue(trashDir.isDirectory)
        assertEquals(1, trashDir.listFiles().orEmpty().count { it.name.endsWith(".md") })

        assertEquals(
            LocalDeleteResult.AlreadyDeleted,
            SyncLocalStore.deleteLocalIfUnchanged(root, mdPath, sha256Of("hello")),
        )

        val imgPath = SyncRelativePath("assets/nested/pic.png")
        val imgFile = writeVaultFile(imgPath.value, "png-bytes")
        assertEquals(
            LocalDeleteResult.Deleted,
            SyncLocalStore.deleteLocalIfUnchanged(root, imgPath, sha256Of("png-bytes")),
        )
        assertFalse(imgFile.exists())
        assertFalse(File(root, "assets").exists())
        assertTrue(root.isDirectory)
        assertTrue(File(root, ".looma").isDirectory)
    }

    @Test
    fun `cleanStaleTempFiles removes staging entries without touching trash`() {
        val staged = SyncLocalStore.createStagingFile(root)
        val nestedDir = File(staged.parentFile, "nested").apply { mkdirs() }
        val nestedFile = File(nestedDir, "leftover.tmp").apply { writeText("x") }
        val trashKeep = writeVaultFile(".looma/trash/keep.md", "keep")

        SyncLocalStore.cleanStaleTempFiles(root)

        assertFalse(staged.exists())
        assertFalse(nestedFile.exists())
        assertFalse(nestedDir.exists())
        assertTrue(trashKeep.exists())
    }

    @Test
    fun `resolveSafeTarget rejects dot segments and hidden paths`() {
        val valid = SyncLocalStore.resolveSafeTarget(root, SyncRelativePath("dir/note.md"))
        assertEquals(File(root.canonicalFile, "dir/note.md"), valid)

        val unsafe = listOf(
            SyncRelativePath(".."),
            SyncRelativePath("."),
            SyncRelativePath("dir/../escape.md"),
            SyncRelativePath(".looma/secret.md"),
            SyncRelativePath("dir/.hidden/note.md"),
            SyncRelativePath(""),
        )
        for (path in unsafe) {
            assertThrows(IOException::class.java) {
                SyncLocalStore.resolveSafeTarget(root, path)
            }
        }
    }

    private fun writeVaultFile(relativePath: String, content: String): File {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    private fun stageText(content: String): File =
        SyncLocalStore.createStagingFile(root).apply { writeText(content) }

    private fun sha256Of(content: String): String =
        S3RequestSigner.sha256Hex(content.toByteArray(Charsets.UTF_8))
}
