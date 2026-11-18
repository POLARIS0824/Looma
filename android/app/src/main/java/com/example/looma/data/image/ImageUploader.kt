package com.example.looma.data.image

import android.content.Context
import android.net.Uri
import com.example.looma.data.note.encodeWikiImagePath
import com.example.looma.data.note.hasOtherAttachmentNamed
import com.example.looma.data.note.invalidateAttachmentIndex
import com.example.looma.data.note.resolveImageSource
import com.example.looma.data.note.writeBytesAtomically
import com.example.looma.data.settings.AppSettings
import com.example.looma.data.settings.ImageUploadTarget
import com.example.looma.data.settings.S3ImageBedConfig
import com.example.looma.data.vault.VaultRepository
import com.example.looma.data.vault.VaultWriteCoordinator
import com.example.looma.domain.markdown.ImageSource
import com.example.looma.domain.model.NotePath
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ImageUpload {
    data class Attachment(val fileName: String) : ImageUpload
    data class Remote(val url: String) : ImageUpload
}

enum class ImageUploadFailure {
    NotConfigured,
    UnsupportedImage,
    ImageTooLarge,
    ReadFailed,
    StorageFailed,
    Rejected,
    Network,
}

class ImageUploadException(
    val failure: ImageUploadFailure,
    val detail: String? = null,
    cause: Throwable? = null,
) : IOException(detail, cause)

class ImageUploader(
    private val context: Context,
    private val vaultRepository: VaultRepository,
) {

    suspend fun upload(uri: Uri, settings: AppSettings, notePath: NotePath? = null): ImageUpload = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri).orEmpty().lowercase()
        val extension = extensionFor(mimeType)
            ?: throw ImageUploadException(ImageUploadFailure.UnsupportedImage)
        val bytes = readBytes(uri)
        val stem = sha256Hex(bytes).take(16)
        val fileName = "$stem.$extension"
        when (settings.imageUploadTarget) {
            ImageUploadTarget.LocalAttachment -> saveAttachment(stem, extension, bytes, notePath)
            ImageUploadTarget.ImageBed -> uploadToImageBed(settings.imageBed, fileName, mimeType, bytes)
        }
    }

    private suspend fun saveAttachment(
        stem: String,
        extension: String,
        bytes: ByteArray,
        notePath: NotePath?,
    ): ImageUpload {
        val vault = vaultRepository.requireCurrentVault()
        return VaultWriteCoordinator.write(vault) {
            val root = File(vault.rootPath).canonicalFile
            val targetDir = resolveAttachmentDirectory(root, notePath)
            if (!targetDir.isDirectory && !targetDir.mkdirs()) {
                throw ImageUploadException(ImageUploadFailure.StorageFailed)
            }
            val (file, needsWrite) = selectAttachmentFile(root, targetDir, stem, extension, bytes)
            if (needsWrite) {
                runCatching { writeBytesAtomically(file, bytes) }
                    .onFailure { throw ImageUploadException(ImageUploadFailure.StorageFailed, it.message, it) }
            }
            invalidateAttachmentIndex(root)
            ImageUpload.Attachment(resolveAttachmentReference(root, notePath, file))
        }
    }

    private fun uploadToImageBed(
        config: S3ImageBedConfig,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ImageUpload {
        if (!config.isConfigured) throw ImageUploadException(ImageUploadFailure.NotConfigured)
        val key = listOf(config.pathPrefix.trim().trim('/'), fileName)
            .filter { it.isNotEmpty() }
            .joinToString("/")
        val signed = runCatching { S3Signer.signPut(config, key, mimeType, bytes, Instant.now()) }
            .getOrElse { throw ImageUploadException(ImageUploadFailure.NotConfigured, it.message, it) }
        put(signed, bytes)
        return ImageUpload.Remote(S3Signer.objectUrl(config, key))
    }

    private fun put(signed: SignedS3Put, bytes: ByteArray) {
        val connection = try {
            URL(signed.url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ImageUploadException(ImageUploadFailure.NotConfigured, e.message, e)
        }
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            signed.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = (connection.errorStream ?: connection.inputStream)
                    ?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8)
                    .orEmpty()
                    .take(300)
                throw ImageUploadException(
                    ImageUploadFailure.Rejected,
                    "HTTP $status $body".trim(),
                )
            }
        } catch (e: ImageUploadException) {
            throw e
        } catch (e: IOException) {
            throw ImageUploadException(ImageUploadFailure.Network, e.message, e)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBytes(uri: Uri): ByteArray {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw ImageUploadException(ImageUploadFailure.ReadFailed, e.message, e)
        } ?: throw ImageUploadException(ImageUploadFailure.ReadFailed)
        return stream.use {
            val buffer = ByteArray(16 * 1024)
            val output = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = it.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_IMAGE_BYTES) {
                    throw ImageUploadException(ImageUploadFailure.ImageTooLarge)
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun extensionFor(mimeType: String): String? = when (mimeType) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp", "image/x-ms-bmp" -> "bmp"
        else -> null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}

private const val MAX_OBSIDIAN_APP_JSON_BYTES = 256L * 1024L
private const val MAX_FOLDER_SEGMENT_LENGTH = 72
private const val FORBIDDEN_FOLDER_CHARS = "\\/:*?\"<>|"

internal fun resolveAttachmentDirectory(root: File, notePath: NotePath?): File {
    val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { return root }
    val raw = readAttachmentFolderPath(canonicalRoot)?.trim()?.replace('\\', '/')
    if (raw.isNullOrEmpty() || raw == "/") return canonicalRoot

    val noteFolder = notePath?.relativePath
        ?.substringBeforeLast('/', missingDelimiterValue = "")
        ?.takeIf { it.isNotEmpty() }
        ?.let { File(canonicalRoot, it) }
        ?: canonicalRoot

    val baseDir: File
    val subpath: String
    when {
        raw == "." || raw == "./" -> {
            baseDir = noteFolder
            subpath = ""
        }
        raw.startsWith("./") -> {
            baseDir = noteFolder
            subpath = raw.removePrefix("./").trim('/')
        }
        else -> {
            baseDir = canonicalRoot
            subpath = raw.trim('/')
        }
    }

    val segments = if (subpath.isEmpty()) emptyList() else subpath.split('/')
    if (segments.any { !isValidFolderSegment(it) }) return canonicalRoot

    var current = baseDir
    if (Files.isSymbolicLink(current.toPath())) return canonicalRoot
    for (segment in segments) {
        current = File(current, segment)
        if (Files.isSymbolicLink(current.toPath())) return canonicalRoot
    }

    val canonicalTarget = runCatching { current.canonicalFile }.getOrElse { return canonicalRoot }
    val rootPath = canonicalRoot.toPath()
    val targetPath = canonicalTarget.toPath()
    if (!targetPath.startsWith(rootPath)) return canonicalRoot
    if (targetPath != rootPath) {
        val relative = rootPath.relativize(targetPath)
        if ((0 until relative.nameCount).any { relative.getName(it).toString().startsWith('.') }) {
            return canonicalRoot
        }
    }
    if (canonicalTarget.exists() && !canonicalTarget.isDirectory) return canonicalRoot
    return canonicalTarget
}

private fun isValidFolderSegment(segment: String): Boolean =
    segment.isNotEmpty() &&
        segment != "." &&
        segment != ".." &&
        !segment.startsWith('.') &&
        segment.length <= MAX_FOLDER_SEGMENT_LENGTH &&
        segment.none { it in FORBIDDEN_FOLDER_CHARS || it.isISOControl() }

private fun readAttachmentFolderPath(root: File): String? =
    readObsidianAppJsonString(root, "attachmentFolderPath")

private fun readObsidianAppJsonString(root: File, key: String): String? {
    val config = File(root, ".obsidian/app.json")
    if (!config.isFile || Files.isSymbolicLink(config.toPath()) || config.length() > MAX_OBSIDIAN_APP_JSON_BYTES) {
        return null
    }
    val text = runCatching { config.readText(Charsets.UTF_8) }.getOrNull() ?: return null
    return extractTopLevelJsonString(text, key)
}

internal fun selectAttachmentFile(
    root: File,
    targetDir: File,
    stem: String,
    extension: String,
    bytes: ByteArray,
): Pair<File, Boolean> {
    val canonicalRoot = root.canonicalFile
    val rootPath = canonicalRoot.toPath()
    for (suffixIndex in 0..10_000) {
        val candidateName = if (suffixIndex == 0) "$stem.$extension" else "$stem $suffixIndex.$extension"
        val raw = File(targetDir, candidateName)
        if (Files.isSymbolicLink(raw.toPath())) continue
        val canonical = raw.canonicalFile
        if (!canonical.toPath().startsWith(rootPath)) {
            throw ImageUploadException(ImageUploadFailure.StorageFailed)
        }
        if (!Files.exists(raw.toPath(), NOFOLLOW_LINKS)) {
            return canonical to true
        }
        if (raw.isFile &&
            raw.length() == bytes.size.toLong() &&
            runCatching { raw.readBytes().contentEquals(bytes) }.getOrDefault(false)
        ) {
            return canonical to false
        }
    }
    throw ImageUploadException(ImageUploadFailure.StorageFailed)
}

internal fun resolveAttachmentReference(
    root: File,
    notePath: NotePath?,
    attachmentFile: File,
): String {
    val canonicalRoot = root.canonicalFile
    val canonicalFile = attachmentFile.canonicalFile
    val noteFile = notePath?.let { File(canonicalRoot, it.relativePath) }
    val probeNoteFile = noteFile ?: File(canonicalRoot, "__probe__.md")
    fun resolvesToTarget(ref: String): Boolean =
        (resolveImageSource(canonicalRoot.path, probeNoteFile.path, ref) as? ImageSource.Local)?.path == canonicalFile.path

    val bare = encodeWikiImagePath(canonicalFile.name)
    val vaultRel = encodeWikiImagePath(canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath)
    val noteDir = noteFile?.parentFile?.canonicalFile
    val rawNoteRel = noteDir?.let { canonicalFile.relativeTo(it).invariantSeparatorsPath }
    val noteRel = rawNoteRel?.let(::encodeWikiImagePath)
    val explicitNoteRel = rawNoteRel?.let { encodeWikiImagePath(if ('/' !in it && !it.startsWith('.')) "./$it" else it) }
    return when (readObsidianAppJsonString(canonicalRoot, "newLinkFormat")?.trim()?.lowercase()) {
        "relative" -> when {
            noteRel != null && resolvesToTarget(noteRel) -> noteRel
            explicitNoteRel != null && resolvesToTarget(explicitNoteRel) -> explicitNoteRel
            resolvesToTarget(vaultRel) -> vaultRel
            else -> bare
        }
        "absolute" -> when {
            resolvesToTarget(vaultRel) -> vaultRel
            explicitNoteRel != null && resolvesToTarget(explicitNoteRel) -> explicitNoteRel
            noteRel != null && resolvesToTarget(noteRel) -> noteRel
            else -> vaultRel
        }
        else -> when {
            !hasOtherAttachmentNamed(canonicalRoot, canonicalFile) && resolvesToTarget(bare) -> bare
            resolvesToTarget(vaultRel) -> vaultRel
            noteRel != null && resolvesToTarget(noteRel) -> noteRel
            explicitNoteRel != null && resolvesToTarget(explicitNoteRel) -> explicitNoteRel
            else -> vaultRel
        }
    }
}

internal fun extractTopLevelJsonString(json: String, targetKey: String): String? {
    var pos = 0
    fun skipWs() {
        while (pos < json.length && json[pos] in " \t\r\n") pos++
    }
    fun readString(): String? {
        if (pos >= json.length || json[pos] != '"') return null
        pos++
        val sb = StringBuilder()
        while (pos < json.length) {
            val ch = json[pos++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= json.length) return null
                    when (val esc = json[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > json.length) return null
                            val hex = json.substring(pos, pos + 4).toIntOrNull(16) ?: return null
                            sb.append(hex.toChar())
                            pos += 4
                        }
                        else -> return null
                    }
                }
                else -> {
                    if (ch.code < 0x20) return null
                    sb.append(ch)
                }
            }
        }
        return null
    }
    fun skipValue(): Boolean {
        skipWs()
        if (pos >= json.length) return false
        when (json[pos]) {
            '"' -> return readString() != null
            '{' -> {
                pos++
                skipWs()
                if (pos < json.length && json[pos] == '}') {
                    pos++
                    return true
                }
                while (true) {
                    skipWs()
                    if (readString() == null) return false
                    skipWs()
                    if (pos >= json.length || json[pos] != ':') return false
                    pos++
                    if (!skipValue()) return false
                    skipWs()
                    if (pos >= json.length) return false
                    when (json[pos]) {
                        ',' -> pos++
                        '}' -> {
                            pos++
                            return true
                        }
                        else -> return false
                    }
                }
            }
            '[' -> {
                pos++
                skipWs()
                if (pos < json.length && json[pos] == ']') {
                    pos++
                    return true
                }
                while (true) {
                    if (!skipValue()) return false
                    skipWs()
                    if (pos >= json.length) return false
                    when (json[pos]) {
                        ',' -> pos++
                        ']' -> {
                            pos++
                            return true
                        }
                        else -> return false
                    }
                }
            }
            else -> {
                val start = pos
                while (pos < json.length && json[pos] !in ",}] \t\r\n") pos++
                return pos > start
            }
        }
    }

    skipWs()
    if (pos >= json.length || json[pos] != '{') return null
    pos++
    skipWs()
    if (pos < json.length && json[pos] == '}') return null
    while (pos < json.length) {
        skipWs()
        val key = readString() ?: return null
        skipWs()
        if (pos >= json.length || json[pos] != ':') return null
        pos++
        skipWs()
        if (key == targetKey) {
            return if (pos < json.length && json[pos] == '"') readString() else null
        }
        if (!skipValue()) return null
        skipWs()
        if (pos >= json.length) return null
        when (json[pos]) {
            ',' -> pos++
            '}' -> return null
            else -> return null
        }
    }
    return null
}
