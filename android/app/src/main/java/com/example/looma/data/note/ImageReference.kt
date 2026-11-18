package com.example.looma.data.note

import com.example.looma.domain.markdown.ImageSource
import com.example.looma.domain.markdown.splitImageSize
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.TreeMap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val LOCAL_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

internal fun resolveImageSource(
    vaultRoot: String,
    noteFile: String,
    reference: String?,
    refreshAttachments: Boolean = false,
): ImageSource? {
    val (target, size) = splitImageSize(reference ?: return null)
    val raw = target.trim().removeSurrounding("<", ">").takeIf { it.isNotBlank() } ?: return null
    if (raw.startsWith("https://", ignoreCase = true)) return ImageSource.Remote(raw, size)
    if (raw.startsWith("http://", ignoreCase = true)) return null
    val src = runCatching {
        URLDecoder.decode(raw.substringBefore('#').replace("+", "%2B"), "UTF-8")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    if (src.startsWith('/') || ':' in src || '\\' in src || '\u0000' in src) return null

    return runCatching {
        val root = File(vaultRoot).canonicalFile
        val rootPath = root.toPath()
        fun local(candidate: File): ImageSource.Local? = runCatching {
            val canonical = candidate.canonicalFile
            val path = canonical.toPath()
            if (!path.startsWith(rootPath) || path == rootPath || !canonical.isFile) return@runCatching null
            if (canonical.extension.lowercase() !in LOCAL_IMAGE_EXTENSIONS) return@runCatching null
            val relative = rootPath.relativize(path)
            if ((0 until relative.nameCount - 1).any { relative.getName(it).toString().startsWith('.') }) {
                return@runCatching null
            }
            ImageSource.Local(canonical.absolutePath, size)
        }.getOrNull()
        val direct = listOf(File(File(noteFile).parentFile, src), File(root, src))
            .firstNotNullOfOrNull(::local)
        if (direct != null || '/' in src || File(src).extension.lowercase() !in LOCAL_IMAGE_EXTENSIONS) {
            return@runCatching direct
        }
        attachmentIndex(root).find(src, refreshAttachments)
            .sortedWith(compareBy({ it.name != src }, { it.relativeTo(root).invariantSeparatorsPath }))
            .firstNotNullOfOrNull(::local)
    }.getOrNull()
}

internal fun invalidateAttachmentIndex(vaultRoot: File) {
    val root = runCatching { vaultRoot.canonicalFile }.getOrElse { vaultRoot }
    synchronized(attachmentIndexes) {
        attachmentIndexes.remove(root)
    }
}

private val attachmentIndexes = LinkedHashMap<File, AttachmentIndex>(4, 0.75f, true)

private fun attachmentIndex(root: File): AttachmentIndex = synchronized(attachmentIndexes) {
    attachmentIndexes.getOrPut(root) { AttachmentIndex(root) }.also {
        if (attachmentIndexes.size > 4) attachmentIndexes.remove(attachmentIndexes.keys.first())
    }
}

private class AttachmentIndex(private val root: File) {
    private var filesByName: Map<String, List<File>> = emptyMap()
    private var scannedAt: TimeMark? = null

    @Synchronized
    fun find(name: String, refresh: Boolean): List<File> {
        if (refresh || scannedAt?.elapsedNow()?.let { it < 30.seconds } != true) {
            filesByName = scan()
            scannedAt = TimeSource.Monotonic.markNow()
        }
        return filesByName[name].orEmpty()
    }

    private fun scan(): Map<String, List<File>> {
        val result = TreeMap<String, MutableList<File>>(String.CASE_INSENSITIVE_ORDER)
        val rootPath = root.toPath()
        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != rootPath && dir.fileName.toString().startsWith('.')) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val candidate = file.toFile()
                if (candidate.extension.lowercase() in LOCAL_IMAGE_EXTENSIONS) {
                    result.getOrPut(candidate.name) { mutableListOf() }.add(candidate)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                FileVisitResult.CONTINUE
        })
        return result
    }
}
