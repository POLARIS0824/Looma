package com.example.looma.data.image

import com.example.looma.domain.model.NotePath
import java.io.File
import org.junit.Assert.assertEquals
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

    private fun writeConfig(content: String) {
        val dir = File(root, ".obsidian").apply { mkdirs() }
        File(dir, "app.json").writeText(content)
    }
}
