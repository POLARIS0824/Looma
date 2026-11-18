package com.example.looma.data.image

import com.example.looma.domain.model.NotePath
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ObsidianAttachmentFolderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = temp.newFolder("vault").canonicalFile
    }

    @Test
    fun `defaults to vault root when obsidian config is absent or slash`() {
        assertEquals(root, resolveAttachmentDirectory(root, NotePath("sub/note.md")))
        writeConfig("""{"attachmentFolderPath": "/"}""")
        assertEquals(root, resolveAttachmentDirectory(root, NotePath("sub/note.md")))
    }

    @Test
    fun `supports vault-relative and note-relative attachment folders`() {
        writeConfig("""{"other": {"attachmentFolderPath": "wrong"}, "attachmentFolderPath": "assets/images"}""")
        assertEquals(File(root, "assets/images"), resolveAttachmentDirectory(root, NotePath("sub/note.md")))

        writeConfig("""{"attachmentFolderPath": "./"}""")
        assertEquals(File(root, "sub"), resolveAttachmentDirectory(root, NotePath("sub/note.md")))
        assertEquals(root, resolveAttachmentDirectory(root, NotePath("top.md")))

        writeConfig("""{"attachmentFolderPath": "./assets"}""")
        assertEquals(File(root, "sub/assets"), resolveAttachmentDirectory(root, NotePath("sub/note.md")))
    }

    @Test
    fun `rejects traversal and hidden dot folders`() {
        for (bad in listOf("../escape", "./../escape", ".looma/attachments", "assets/.hidden", "bad:name")) {
            writeConfig("""{"attachmentFolderPath": "$bad"}""")
            assertEquals(root, resolveAttachmentDirectory(root, NotePath("sub/note.md")))
        }
    }

    @Test
    fun `selects collision-safe attachment names without overwriting distinct content`() {
        val targetDir = File(root, "assets").apply { mkdirs() }
        val bytes = byteArrayOf(1, 2, 3)
        val first = File(targetDir, "abc.png").apply { writeBytes(bytes) }
        val (reused, needsWriteSame) = selectAttachmentFile(root, targetDir, "abc", "png", bytes)
        assertEquals(first.canonicalFile, reused)
        assertFalse(needsWriteSame)

        val differentBytes = byteArrayOf(9, 8, 7)
        val (suffixed, needsWriteDifferent) = selectAttachmentFile(root, targetDir, "abc", "png", differentBytes)
        assertEquals(File(targetDir, "abc 1.png").canonicalFile, suffixed)
        assertTrue(needsWriteDifferent)
    }

    @Test
    fun `disambiguates new attachments when an older file with the same name would shadow them`() {
        val notePath = NotePath("notes/a.md")
        File(root, "notes").mkdirs()
        val assetsDir = File(root, "assets").apply { mkdirs() }
        val newInAssets = File(assetsDir, "pic.png").apply { writeBytes(byteArrayOf(1)) }
        assertEquals("pic.png", resolveAttachmentReference(root, notePath, newInAssets))

        File(root, "notes/pic.png").writeBytes(byteArrayOf(2))
        assertEquals("assets/pic.png", resolveAttachmentReference(root, notePath, newInAssets))

        File(root, "notes/assets").mkdirs()
        File(root, "notes/assets/pic.png").writeBytes(byteArrayOf(3))
        assertEquals("../assets/pic.png", resolveAttachmentReference(root, notePath, newInAssets))

        val rootAttachment = File(root, "pic.png").apply { writeBytes(byteArrayOf(4)) }
        assertEquals("../pic.png", resolveAttachmentReference(root, notePath, rootAttachment))
    }

    @Test
    fun `respects obsidian newLinkFormat when generating attachment references`() {
        val notePath = NotePath("notes/a.md")
        File(root, "notes").mkdirs()
        val assetsDir = File(root, "assets").apply { mkdirs() }
        val inAssets = File(assetsDir, "pic.png").apply { writeBytes(byteArrayOf(1)) }

        writeConfig("""{"attachmentFolderPath": "assets", "newLinkFormat": "relative"}""")
        assertEquals("../assets/pic.png", resolveAttachmentReference(root, notePath, inAssets))

        writeConfig("""{"attachmentFolderPath": "assets", "newLinkFormat": "absolute"}""")
        assertEquals("assets/pic.png", resolveAttachmentReference(root, notePath, inAssets))
    }

    private fun writeConfig(content: String) {
        val dir = File(root, ".obsidian").apply { mkdirs() }
        File(dir, "app.json").writeText(content)
    }
}
