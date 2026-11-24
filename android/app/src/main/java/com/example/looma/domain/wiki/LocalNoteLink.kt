package com.example.looma.domain.wiki

import com.example.looma.domain.model.NotePath
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Paths

internal object LocalNoteLink {
    fun resolve(source: NotePath, destination: String): NotePath? {
        val raw = destination.substringBefore('#')
        val decoded = try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) { return null }
        if (!decoded.endsWith(".md", true) || decoded.startsWith('/') || ':' in decoded ||
            '\\' in decoded || '\u0000' in decoded) return null
        val parent = Paths.get(source.relativePath).parent ?: Paths.get("")
        val path = parent.resolve(decoded).normalize()
        if (path.startsWith("..")) return null
        return NotePath(path.joinToString("/"))
    }

    fun fromRoot(target: NotePath): String = encode(Paths.get(target.relativePath))

    private fun encode(path: java.nio.file.Path) = path.joinToString("/") {
        URLEncoder.encode(it.toString(), "UTF-8").replace("+", "%20")
    }

    fun destination(source: NotePath, target: NotePath): String {
        val parent = Paths.get(source.relativePath).parent ?: Paths.get("")
        return encode(parent.relativize(Paths.get(target.relativePath)))
    }
}
