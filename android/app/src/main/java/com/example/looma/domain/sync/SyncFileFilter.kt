package com.example.looma.domain.sync

import java.util.Locale

object SyncFileFilter {
    const val MAX_SYNC_FILE_BYTES = 100L * 1024L * 1024L
    const val MAX_SYNC_DEPTH = 40

    private const val FORBIDDEN_CHARS = "\\:*?\"<>|"
    private val JUNK_SUFFIXES = listOf(
        ".tmp",
        ".temp",
        ".part",
        ".partial",
        ".crdownload",
        ".swp",
        ".swo",
        ".bak",
    )

    fun isIncluded(relativePath: String): Boolean = isValidSyncFile(relativePath)

    fun isValidSyncFile(relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        if (relativePath.startsWith('/') || relativePath.endsWith('/')) return false
        if ('\\' in relativePath) return false
        if (relativePath.any { it.isISOControl() }) return false
        val segments = relativePath.split('/')
        if (segments.isEmpty() || segments.size > MAX_SYNC_DEPTH) return false
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == "..") return false
            if (segment.startsWith('.')) return false
            if (segment.any { it in FORBIDDEN_CHARS || it.isISOControl() }) return false
            if (segment.toByteArray(Charsets.UTF_8).size > 255) return false
        }
        return !isTempOrOsJunk(segments.last())
    }

    fun isMarkdownPath(path: SyncRelativePath): Boolean =
        path.value.endsWith(".md", ignoreCase = true)

    fun inferContentType(path: SyncRelativePath): String {
        val fileName = path.value.substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        return when (extension) {
            "md", "markdown" -> "text/markdown; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "json", "canvas" -> "application/json; charset=utf-8"
            "csv" -> "text/csv; charset=utf-8"
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "xml" -> "application/xml; charset=utf-8"
            "yaml", "yml" -> "application/yaml; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "avif" -> "image/avif"
            "heic" -> "image/heic"
            "pdf" -> "application/pdf"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "zip" -> "application/zip"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            else -> "application/octet-stream"
        }
    }

    private fun isTempOrOsJunk(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.ROOT)
        if (lower == "thumbs.db" || lower == "desktop.ini") return true
        if (lower.startsWith("~$") || lower.endsWith('~')) return true
        if (lower.length >= 2 && lower.startsWith('#') && lower.endsWith('#')) return true
        return JUNK_SUFFIXES.any { lower.endsWith(it) }
    }
}
