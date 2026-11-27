package com.example.looma.data.image

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

data class ImageNamingContext(
    val originalFileName: String? = null,
    val extension: String,
    val sha256Hex: String,
    val md5Hex: String,
    val noteName: String? = null,
    val now: Instant = Instant.now(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val uuid: String = UUID.randomUUID().toString(),
    val randomHex: String = UUID.randomUUID().toString().replace("-", "").take(8),
)

data class NamingPreset(
    val id: String,
    val template: String,
)

object ImageNaming {
    private const val MAX_SEGMENT_LENGTH = 72
    private const val FORBIDDEN_CHARS = "\\/:*?\"<>|"
    private val PLACEHOLDER_REGEX = Regex("\\{[A-Za-z0-9_]+\\}")

    val PRESETS: List<NamingPreset> = listOf(
        NamingPreset("hash", "{sha256}.{extName}"),
        NamingPreset("date-hash", "{year}/{month}/{sha256}.{extName}"),
        NamingPreset("date-name", "{year}/{month}/{day}/{fileName}-{random}.{extName}"),
        NamingPreset("timestamp-name", "{timestamp}-{fileName}.{extName}"),
        NamingPreset("note-folder", "{noteName}/{timestamp}-{random}.{extName}"),
    )

    val VARIABLES: List<String> = listOf(
        "{year}",
        "{month}",
        "{day}",
        "{hour}",
        "{minute}",
        "{second}",
        "{timestamp}",
        "{timestampMs}",
        "{fileName}",
        "{extName}",
        "{md5}",
        "{md5Full}",
        "{sha256}",
        "{uuid}",
        "{random}",
        "{noteName}",
    )

    fun renderRelativePath(template: String, context: ImageNamingContext): String {
        val ext = sanitizeSegment(
            raw = context.extension.trimStart('.').lowercase(Locale.ROOT),
            fallback = "png",
        )
        val fallbackKey = "${context.sha256Hex.take(16)}.$ext"
        if (template.isBlank()) return fallbackKey

        val zoned = context.now.atZone(context.zoneId)
        val rawBaseName = context.originalFileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
        val fileName = sanitizeSegment(rawBaseName.orEmpty(), fallback = "image")
        val noteName = sanitizeSegment(context.noteName.orEmpty(), fallback = "note")

        val placeholders = mapOf(
            "{year}" to zoned.year.toString().padStart(4, '0'),
            "{month}" to zoned.monthValue.toString().padStart(2, '0'),
            "{day}" to zoned.dayOfMonth.toString().padStart(2, '0'),
            "{hour}" to zoned.hour.toString().padStart(2, '0'),
            "{minute}" to zoned.minute.toString().padStart(2, '0'),
            "{second}" to zoned.second.toString().padStart(2, '0'),
            "{timestamp}" to context.now.epochSecond.toString(),
            "{timestampMs}" to context.now.toEpochMilli().toString(),
            "{fileName}" to fileName,
            "{extName}" to ext,
            "{md5}" to context.md5Hex.take(16),
            "{md5Full}" to context.md5Hex,
            "{sha256}" to context.sha256Hex.take(16),
            "{uuid}" to context.uuid,
            "{random}" to context.randomHex,
            "{noteName}" to noteName,
        )
        val expanded = PLACEHOLDER_REGEX.replace(template) { match ->
            placeholders[match.value].orEmpty()
        }

        val cleanedSegments = expanded
            .replace('\\', '/')
            .split('/')
            .mapNotNull { raw ->
                val cleaned = stripInvalidChars(raw).trim()
                if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") null else cleaned
            }
            .toMutableList()

        if (cleanedSegments.isEmpty()) return fallbackKey

        val lastIndex = cleanedSegments.lastIndex
        for (i in 0 until lastIndex) {
            cleanedSegments[i] = cleanedSegments[i].take(MAX_SEGMENT_LENGTH).trim()
        }

        val filteredDirs = cleanedSegments
            .dropLast(1)
            .filter { it.isNotEmpty() && it != "." && it != ".." }

        val rawLast = cleanedSegments[lastIndex]
        val expectedSuffix = ".$ext"
        val maxStemLength = (MAX_SEGMENT_LENGTH - expectedSuffix.length).coerceAtLeast(1)
        val finalFileName = if (rawLast.endsWith(expectedSuffix, ignoreCase = true)) {
            val stem = rawLast.dropLast(expectedSuffix.length).take(maxStemLength).trim()
            if (stem.isEmpty() || stem == "." || stem == "..") {
                "${context.sha256Hex.take(16).take(maxStemLength)}.$ext"
            } else {
                "$stem${rawLast.takeLast(expectedSuffix.length)}"
            }
        } else {
            val stem = rawLast.take(maxStemLength).trim()
            if (stem.isEmpty() || stem == "." || stem == "..") {
                "${context.sha256Hex.take(16).take(maxStemLength)}.$ext"
            } else {
                "$stem.$ext"
            }
        }

        val finalSegments = filteredDirs + finalFileName
        return finalSegments.joinToString("/")
    }

    fun buildObjectKey(
        pathPrefix: String,
        template: String,
        context: ImageNamingContext,
    ): String {
        val prefix = normalizePrefix(pathPrefix)
        val relative = renderRelativePath(template, context)
        return if (prefix.isEmpty()) relative else "$prefix/$relative"
    }

    fun sanitizeSegment(raw: String, fallback: String = "image"): String {
        val safeFallback = stripInvalidChars(fallback)
            .replace("..", "")
            .trim { it.isWhitespace() || it == '.' }
            .take(MAX_SEGMENT_LENGTH)
            .trim { it.isWhitespace() || it == '.' }
            .ifEmpty { "image" }
        val cleaned = stripInvalidChars(raw)
            .replace("..", "")
            .trim { it.isWhitespace() || it == '.' }
        if (cleaned.isEmpty()) {
            return safeFallback
        }
        val truncated = cleaned
            .take(MAX_SEGMENT_LENGTH)
            .trim { it.isWhitespace() || it == '.' }
        return truncated.ifEmpty { safeFallback }
    }

    fun normalizePrefix(raw: String): String =
        raw.replace('\\', '/')
            .split('/')
            .mapNotNull { segment ->
                val cleaned = stripInvalidChars(segment).trim()
                if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
                    null
                } else {
                    cleaned.take(MAX_SEGMENT_LENGTH).trim().takeIf { it.isNotEmpty() && it != "." && it != ".." }
                }
            }
            .joinToString("/")

    fun preview(
        template: String,
        pathPrefix: String = "",
        sampleFileName: String = "photo.png",
        sampleNoteName: String = "Daily Note",
    ): String {
        val ext = sampleFileName.substringAfterLast('.', "png").ifBlank { "png" }
        val context = ImageNamingContext(
            originalFileName = sampleFileName,
            extension = ext,
            sha256Hex = "a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890",
            md5Hex = "0123456789abcdef0123456789abcdef",
            noteName = sampleNoteName,
            uuid = "123e4567-e89b-12d3-a456-426614174000",
            randomHex = "9f8e7d6c",
        )
        return buildObjectKey(pathPrefix, template, context)
    }

    private fun stripInvalidChars(input: String): String = buildString(input.length) {
        for (ch in input) {
            if (!ch.isISOControl() && ch !in FORBIDDEN_CHARS) {
                append(ch)
            }
        }
    }
}
