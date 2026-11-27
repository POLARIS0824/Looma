package com.example.looma.data.image

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageNamingTest {

    private val fixedInstant = Instant.parse("2026-11-27T06:05:09Z")
    private val sampleSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val sampleMd5 = "fedcba9876543210fedcba9876543210"
    private val sampleUuid = "11111111-2222-3333-4444-555555555555"
    private val sampleRandom = "a1b2c3d4"

    private fun baseContext(
        originalFileName: String? = "vacation photo.png",
        extension: String = "png",
        noteName: String? = "Daily Note",
    ) = ImageNamingContext(
        originalFileName = originalFileName,
        extension = extension,
        sha256Hex = sampleSha256,
        md5Hex = sampleMd5,
        noteName = noteName,
        now = fixedInstant,
        zoneId = ZoneOffset.UTC,
        uuid = sampleUuid,
        randomHex = sampleRandom,
    )

    @Test
    fun `renderRelativePath substitutes all supported placeholders`() {
        val template = "{year}/{month}/{day}/{hour}-{minute}-{second}/" +
            "{timestamp}_{timestampMs}/{md5}_{md5Full}/{sha256}_{random}/{uuid}/{fileName}_{noteName}.{extName}"
        val rendered = ImageNaming.renderRelativePath(template, baseContext())
        val expected = "2026/11/27/06-05-09/" +
            "${fixedInstant.epochSecond}_${fixedInstant.toEpochMilli()}/" +
            "${sampleMd5.take(16)}_${sampleMd5}/" +
            "${sampleSha256.take(16)}_${sampleRandom}/" +
            "${sampleUuid}/" +
            "vacation photo_Daily Note.png"
        assertEquals(expected, rendered)
    }

    @Test
    fun `renderRelativePath sanitizes traversal and forbidden characters`() {
        val ctx = baseContext(
            originalFileName = "../bad:name*?\"<>|\u0000\\test.png",
            noteName = "./folder/../my:note",
        )
        val rendered = ImageNaming.renderRelativePath(
            "../{noteName}//./{fileName}.{extName}",
            ctx,
        )
        assertFalse(rendered.contains(".."))
        assertFalse(rendered.contains("\\"))
        assertFalse(rendered.contains("//"))
        assertFalse(rendered.startsWith("/"))
        assertFalse(rendered.endsWith("/"))
        for (ch in ":*?\"<>|\u0000") {
            assertFalse("Unexpected forbidden char $ch in $rendered", ch in rendered)
        }
        assertTrue(rendered.endsWith(".png"))
    }

    @Test
    fun `renderRelativePath handles blank template, missing extension, and unknown placeholders`() {
        val ctx = baseContext()
        assertEquals("${sampleSha256.take(16)}.png", ImageNaming.renderRelativePath("   ", ctx))
        assertEquals("${sampleSha256.take(16)}.png", ImageNaming.renderRelativePath("", ctx))
        assertEquals("2026/vacation photo.png", ImageNaming.renderRelativePath("{year}/{fileName}", ctx))

        val unknown = ImageNaming.renderRelativePath("{unknownVar}/{fileName}.{extName}", ctx)
        assertFalse(unknown.contains("{unknownVar}"))
        assertTrue(unknown.endsWith("vacation photo.png"))
    }

    @Test
    fun `renderRelativePath falls back when originalFileName or noteName is null and truncates long segments`() {
        val nullCtx = baseContext(originalFileName = null, noteName = null)
        val renderedNull = ImageNaming.renderRelativePath("{noteName}/{fileName}.{extName}", nullCtx)
        assertFalse(renderedNull.contains("null", ignoreCase = true))
        assertTrue(renderedNull.endsWith(".png"))
        assertTrue(renderedNull.contains("/"))

        val longName = "a".repeat(120) + ".png"
        val longCtx = baseContext(originalFileName = longName)
        val renderedLong = ImageNaming.renderRelativePath("{fileName}.{extName}", longCtx)
        val stem = renderedLong.removeSuffix(".png")
        assertTrue("Expected stem <= 72 chars, got ${stem.length}", stem.length <= 72)
    }

    @Test
    fun `normalizePrefix normalizes slashes, backslashes, and traversal segments`() {
        assertEquals("", ImageNaming.normalizePrefix(""))
        assertEquals("", ImageNaming.normalizePrefix("   "))
        assertEquals("", ImageNaming.normalizePrefix("/"))
        assertEquals("test", ImageNaming.normalizePrefix("/test/"))
        assertEquals("test/sub", ImageNaming.normalizePrefix("test//sub"))
        assertEquals("test/sub", ImageNaming.normalizePrefix("test\\sub"))
        assertEquals("bad", ImageNaming.normalizePrefix("../bad"))
    }

    @Test
    fun `buildObjectKey combines normalized prefix and rendered relative path`() {
        val key = ImageNaming.buildObjectKey(
            pathPrefix = "/images/blog/",
            template = "{year}/{fileName}.{extName}",
            context = baseContext(originalFileName = "pic.png"),
        )
        assertEquals("images/blog/2026/pic.png", key)
    }

    @Test
    fun `preview produces non-empty path with extension`() {
        val preview = ImageNaming.preview(
            template = "{year}/{noteName}/{fileName}.{extName}",
            pathPrefix = "/uploads/",
            sampleFileName = "photo.png",
            sampleNoteName = "Daily Note",
        )
        assertTrue(preview.startsWith("uploads/"))
        assertTrue(preview.endsWith(".png"))
        assertTrue(preview.contains("Daily Note"))
    }

    @Test
    fun `renderRelativePath preserves literal placeholders inside fileName and noteName`() {
        val ctx = baseContext(
            originalFileName = "shot_{md5}_{foo}.png",
            noteName = "note_{sha256}_{foo}",
        )
        val rendered = ImageNaming.renderRelativePath("{noteName}/{fileName}.{extName}", ctx)
        assertEquals("note_{sha256}_{foo}/shot_{md5}_{foo}.png", rendered)
    }
}
