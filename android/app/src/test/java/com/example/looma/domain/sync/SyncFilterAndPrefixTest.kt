package com.example.looma.domain.sync

import com.example.looma.data.s3.S3EndpointConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFilterAndPrefixTest {

    @Test
    fun `isValidSyncFile and parseOrNull reject blank, slashes, backslashes, and control chars`() {
        val invalid = listOf(
            "",
            "   ",
            "/",
            "/note.md",
            "note.md/",
            "dir//note.md",
            "dir\\note.md",
            "note\u0000.md",
            "note\n.md",
            "note\u007F.md",
        )
        for (raw in invalid) {
            assertFalse("Expected invalid: $raw", SyncFileFilter.isValidSyncFile(raw))
            assertFalse("Expected excluded: $raw", SyncFileFilter.isIncluded(raw))
            assertNull("Expected null parse: $raw", SyncRelativePath.parseOrNull(raw))
        }
    }

    @Test
    fun `isValidSyncFile rejects dot segments and hidden segments`() {
        val invalid = listOf(
            ".",
            "..",
            "dir/./note.md",
            "dir/../note.md",
            "../note.md",
            ".looma/a.md",
            "dir/.obsidian/x",
            ".DS_Store",
            ".git/config",
            "dir/.hidden",
        )
        for (raw in invalid) {
            assertFalse("Expected invalid: $raw", SyncFileFilter.isValidSyncFile(raw))
            assertNull("Expected null parse: $raw", SyncRelativePath.parseOrNull(raw))
        }
    }

    @Test
    fun `isValidSyncFile rejects forbidden characters`() {
        for (ch in listOf('\\', ':', '*', '?', '"', '<', '>', '|')) {
            val raw = "dir/file${ch}name.md"
            assertFalse("Expected invalid for char $ch", SyncFileFilter.isValidSyncFile(raw))
            assertNull(SyncRelativePath.parseOrNull(raw))
        }
    }

    @Test
    fun `isValidSyncFile enforces max depth 40`() {
        val depth40 = (1..39).joinToString("/") { "d$it" } + "/note.md"
        val depth41 = (1..40).joinToString("/") { "d$it" } + "/note.md"

        assertTrue(SyncFileFilter.isValidSyncFile(depth40))
        assertEquals(SyncRelativePath(depth40), SyncRelativePath.parseOrNull(depth40))

        assertFalse(SyncFileFilter.isValidSyncFile(depth41))
        assertNull(SyncRelativePath.parseOrNull(depth41))
    }

    @Test
    fun `isValidSyncFile enforces 255 UTF-8 byte segment limit including multi-byte CJK`() {
        val ascii255 = "a".repeat(255)
        val ascii256 = "a".repeat(256)
        val cjk255 = "笔".repeat(85)
        val cjk256 = "笔".repeat(85) + "a"
        val cjkMixed255 = "笔".repeat(84) + ".md"
        val cjkMixed256 = "笔".repeat(85) + ".md"

        assertEquals(255, cjk255.toByteArray(Charsets.UTF_8).size)
        assertEquals(256, cjk256.toByteArray(Charsets.UTF_8).size)
        assertEquals(255, cjkMixed255.toByteArray(Charsets.UTF_8).size)
        assertEquals(258, cjkMixed256.toByteArray(Charsets.UTF_8).size)

        assertTrue(SyncFileFilter.isValidSyncFile(ascii255))
        assertFalse(SyncFileFilter.isValidSyncFile(ascii256))
        assertTrue(SyncFileFilter.isValidSyncFile(cjk255))
        assertFalse(SyncFileFilter.isValidSyncFile(cjk256))
        assertTrue(SyncFileFilter.isValidSyncFile("folder/$cjkMixed255"))
        assertFalse(SyncFileFilter.isValidSyncFile("folder/$cjkMixed256"))
    }

    @Test
    fun `isValidSyncFile rejects temp and OS junk files`() {
        val junk = listOf(
            "note.tmp",
            "note.TEMP",
            "download.part",
            "download.partial",
            "file.crdownload",
            "note.swp",
            "note.swo",
            "note.bak",
            "~\$doc.md",
            "dir/~\$Spreadsheet.xlsx",
            "file~",
            "#autosave#",
            "##",
            "Thumbs.db",
            "dir/THUMBS.DB",
            "desktop.ini",
            "dir/Desktop.INI",
        )
        for (raw in junk) {
            assertFalse("Expected junk rejected: $raw", SyncFileFilter.isValidSyncFile(raw))
        }

        assertTrue(SyncFileFilter.isValidSyncFile("#"))
        assertTrue(SyncFileFilter.isValidSyncFile("#heading.md"))
        assertTrue(SyncFileFilter.isValidSyncFile("notes/会议记录.md"))
    }

    @Test
    fun `RemotePrefix normalizeOrNull handles slashes, backslashes, dot-dot, and control chars`() {
        assertEquals(RemotePrefix(""), RemotePrefix.normalizeOrNull(""))
        assertEquals(RemotePrefix(""), RemotePrefix.normalizeOrNull("   "))
        assertEquals(RemotePrefix(""), RemotePrefix.normalizeOrNull("///"))
        assertEquals(RemotePrefix(""), RemotePrefix.normalizeOrNull("\\/\\"))
        assertEquals(RemotePrefix("vault/"), RemotePrefix.normalizeOrNull("vault"))
        assertEquals(RemotePrefix("vault/notes/"), RemotePrefix.normalizeOrNull(" /vault//notes/ "))
        assertEquals(RemotePrefix("vault/notes/"), RemotePrefix.normalizeOrNull("\\vault\\\\notes\\"))

        assertNull(RemotePrefix.normalizeOrNull(".."))
        assertNull(RemotePrefix.normalizeOrNull("vault/../notes"))
        assertNull(RemotePrefix.normalizeOrNull("vault/.."))
        assertNull(RemotePrefix.normalizeOrNull("vault\n"))
        assertNull(RemotePrefix.normalizeOrNull("vault\u0000notes"))
    }

    @Test
    fun `RemotePrefix toObjectKey, fromObjectKey, and isUnderHiddenDirectory`() {
        val emptyPrefix = RemotePrefix("")
        val nestedPrefix = RemotePrefix("vault/notes/")
        val path = SyncRelativePath("sub/note.md")

        assertEquals("sub/note.md", emptyPrefix.toObjectKey(path))
        assertEquals("vault/notes/sub/note.md", nestedPrefix.toObjectKey(path))

        assertEquals(path, emptyPrefix.fromObjectKey("sub/note.md"))
        assertEquals(path, nestedPrefix.fromObjectKey("vault/notes/sub/note.md"))
        assertNull(nestedPrefix.fromObjectKey("other/sub/note.md"))
        assertNull(nestedPrefix.fromObjectKey("vault/notes/"))
        assertNull(nestedPrefix.fromObjectKey("vault/notes/dir/"))
        assertNull(nestedPrefix.fromObjectKey("vault/notes/.looma/a.md"))
        assertNull(nestedPrefix.fromObjectKey("vault/notes/file.tmp"))

        assertTrue(emptyPrefix.isUnderHiddenDirectory(".looma/a.md"))
        assertTrue(nestedPrefix.isUnderHiddenDirectory("vault/notes/.looma/a.md"))
        assertTrue(nestedPrefix.isUnderHiddenDirectory("vault/notes/dir/.obsidian/x"))
        assertTrue(nestedPrefix.isUnderHiddenDirectory("vault/notes/.DS_Store"))
        assertFalse(nestedPrefix.isUnderHiddenDirectory("vault/notes/dir/note.md"))
        assertFalse(nestedPrefix.isUnderHiddenDirectory("vault/notes/"))
        assertFalse(nestedPrefix.isUnderHiddenDirectory("other/.looma/a.md"))
    }

    @Test
    fun `inferContentType maps known and unknown extensions`() {
        val cases = mapOf(
            "a.md" to "text/markdown; charset=utf-8",
            "a.MARKDOWN" to "text/markdown; charset=utf-8",
            "a.txt" to "text/plain; charset=utf-8",
            "a.json" to "application/json; charset=utf-8",
            "a.canvas" to "application/json; charset=utf-8",
            "a.csv" to "text/csv; charset=utf-8",
            "a.html" to "text/html; charset=utf-8",
            "a.htm" to "text/html; charset=utf-8",
            "a.css" to "text/css; charset=utf-8",
            "a.js" to "application/javascript; charset=utf-8",
            "a.mjs" to "application/javascript; charset=utf-8",
            "a.xml" to "application/xml; charset=utf-8",
            "a.yaml" to "application/yaml; charset=utf-8",
            "a.yml" to "application/yaml; charset=utf-8",
            "a.png" to "image/png",
            "a.jpg" to "image/jpeg",
            "a.jpeg" to "image/jpeg",
            "a.webp" to "image/webp",
            "a.gif" to "image/gif",
            "a.bmp" to "image/bmp",
            "a.svg" to "image/svg+xml",
            "a.ico" to "image/x-icon",
            "a.avif" to "image/avif",
            "a.heic" to "image/heic",
            "a.pdf" to "application/pdf",
            "a.mp3" to "audio/mpeg",
            "a.m4a" to "audio/mp4",
            "a.ogg" to "audio/ogg",
            "a.oga" to "audio/ogg",
            "a.wav" to "audio/wav",
            "a.flac" to "audio/flac",
            "a.mp4" to "video/mp4",
            "a.webm" to "video/webm",
            "a.mov" to "video/quicktime",
            "a.zip" to "application/zip",
            "a.woff2" to "font/woff2",
            "a.woff" to "font/woff",
            "a.ttf" to "font/ttf",
            "a.otf" to "font/otf",
            "README" to "application/octet-stream",
            "archive.bin" to "application/octet-stream",
        )
        for ((name, expected) in cases) {
            val path = SyncRelativePath.parseOrNull(name)
            assertNotNull(path)
            assertEquals("Content type mismatch for $name", expected, SyncFileFilter.inferContentType(path!!))
        }
    }

    @Test
    fun `S3EndpointConfig targetKey canonicalizes case, default ports, and trailing slashes`() {
        val prefix = RemotePrefix("vault/")
        val base = S3EndpointConfig(
            endpoint = "https://s3.example.com",
            region = "us-east-1",
            bucket = "my-bucket",
            accessKey = "ak",
            secretKey = "sk",
            pathStyle = true,
        )

        val variants = listOf(
            base.copy(endpoint = "HTTPS://S3.EXAMPLE.COM"),
            base.copy(endpoint = "https://s3.example.com:443"),
            base.copy(endpoint = "https://s3.example.com/"),
            base.copy(endpoint = "HTTPS://S3.Example.COM:443/"),
            base.copy(bucket = " my-bucket "),
            base.copy(region = "eu-west-1", pathStyle = false),
        )
        val expected = base.targetKey(prefix)
        for (variant in variants) {
            assertEquals(expected, variant.targetKey(prefix))
        }

        val httpBase = base.copy(endpoint = "http://s3.example.com/base")
        assertEquals(
            httpBase.targetKey(prefix),
            base.copy(endpoint = "HTTP://S3.Example.com:80/base/").targetKey(prefix),
        )

        assertNotEquals(expected, base.copy(endpoint = "https://s3.example.com:8443").targetKey(prefix))
        assertNotEquals(expected, base.copy(bucket = "other-bucket").targetKey(prefix))
        assertNotEquals(expected, base.targetKey(RemotePrefix("other/")))
    }
}
