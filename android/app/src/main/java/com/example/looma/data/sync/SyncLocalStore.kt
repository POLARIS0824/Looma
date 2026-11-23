package com.example.looma.data.sync

import com.example.looma.data.note.VaultFileOps
import com.example.looma.data.s3.S3RequestSigner
import com.example.looma.domain.sync.LocalFileSnapshot
import com.example.looma.domain.sync.SyncFileFilter
import com.example.looma.domain.sync.SyncRelativePath
import com.example.looma.domain.sync.SyncSkipReason
import com.example.looma.domain.sync.SyncSkippedItem
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class LocalScanResult(
    val files: Map<SyncRelativePath, LocalFileSnapshot>,
    val skipped: List<SyncSkippedItem>,
    val complete: Boolean,
)

internal data class FileDigests(
    val sha256: String,
    val md5: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

internal sealed interface LocalCommitResult {
    data class Committed(
        val file: File,
        val sizeBytes: Long,
        val modifiedAtMillis: Long,
    ) : LocalCommitResult

    data class AlreadyIdentical(
        val file: File,
        val sizeBytes: Long,
        val modifiedAtMillis: Long,
    ) : LocalCommitResult

    data class CommittedWithConflictCopy(
        val file: File,
        val sizeBytes: Long,
        val modifiedAtMillis: Long,
        val conflictPath: SyncRelativePath,
        val conflictFile: File,
    ) : LocalCommitResult
}

internal enum class LocalDeleteResult {
    Deleted,
    AlreadyDeleted,
    LocalModifiedAbort,
}

internal object SyncLocalStore {
    private const val LOOMA_DIR = ".looma"
    private const val SYNC_TMP_DIR = "sync-tmp"
    private const val STREAM_BUFFER_SIZE = 64 * 1024
    private const val MAX_FILENAME_BYTES = 255
    private val CONFLICT_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss")

    fun cleanStaleTempFiles(root: File) {
        val canonicalRoot = root.canonicalFile
        val loomaDir = File(canonicalRoot, LOOMA_DIR)
        if (Files.isSymbolicLink(loomaDir.toPath())) return
        val tmpDir = File(loomaDir, SYNC_TMP_DIR)
        if (Files.isSymbolicLink(tmpDir.toPath()) || !tmpDir.isDirectory) return
        val canonicalTmp = tmpDir.canonicalFile
        if (!canonicalTmp.toPath().startsWith(canonicalRoot.toPath())) return
        canonicalTmp.listFiles().orEmpty().forEach { child ->
            deleteEntrySafely(canonicalTmp, child)
        }
    }

    fun createStagingFile(root: File): File {
        val canonicalRoot = root.canonicalFile
        if (!canonicalRoot.isDirectory) {
            throw IOException("Vault root is not a directory")
        }
        val loomaDir = ensureChildDirectory(canonicalRoot, LOOMA_DIR)
        val tmpDir = ensureChildDirectory(loomaDir, SYNC_TMP_DIR)
        if (!tmpDir.canonicalFile.toPath().startsWith(canonicalRoot.toPath())) {
            throw IOException("Sync staging directory escaped vault root")
        }
        return Files.createTempFile(tmpDir.toPath(), "sync-", ".tmp").toFile()
    }

    fun scanLocalFiles(root: File, checkActive: () -> Unit): LocalScanResult {
        val canonicalRoot = root.canonicalFile
        if (!canonicalRoot.isDirectory) {
            throw IOException("Vault directory is unavailable")
        }
        val rootPath = canonicalRoot.toPath()
        val files = LinkedHashMap<SyncRelativePath, LocalFileSnapshot>()
        val skipped = mutableListOf<SyncSkippedItem>()
        var complete = true

        val pending = ArrayDeque<Pair<File, Int>>()
        pending += canonicalRoot to 0
        while (pending.isNotEmpty()) {
            checkActive()
            val (directory, depth) = pending.removeFirst()
            val children = directory.listFiles()
            if (children == null) {
                complete = false
                continue
            }
            children.sortBy { it.name }
            for (child in children) {
                checkActive()
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.name.startsWith('.')) continue
                val attributes = try {
                    Files.readAttributes(child.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                } catch (_: java.nio.file.NoSuchFileException) {
                    continue
                } catch (_: IOException) {
                    complete = false
                    continue
                }
                val canonicalChild = runCatching { child.canonicalFile }.getOrNull() ?: continue
                if (!canonicalChild.toPath().startsWith(rootPath)) continue

                if (attributes.isDirectory) {
                    if (depth + 1 < SyncFileFilter.MAX_SYNC_DEPTH) {
                        pending += child to (depth + 1)
                    } else {
                        complete = false
                    }
                    continue
                }
                if (!attributes.isRegularFile) continue

                val rel = child.relativeTo(canonicalRoot).invariantSeparatorsPath
                val syncPath = SyncRelativePath.parseOrNull(rel)
                if (syncPath == null) {
                    skipped += SyncSkippedItem(rel, SyncSkipReason.InvalidPath)
                    continue
                }
                val size = attributes.size()
                if (size > SyncFileFilter.MAX_SYNC_FILE_BYTES) {
                    skipped += SyncSkippedItem(rel, SyncSkipReason.FileTooLarge)
                    continue
                }
                files[syncPath] = LocalFileSnapshot(
                    path = syncPath,
                    sizeBytes = size,
                    modifiedAtMillis = attributes.lastModifiedTime().toMillis(),
                )
            }
        }

        return LocalScanResult(
            files = files,
            skipped = skipped,
            complete = complete,
        )
    }

    fun computeFileDigests(file: File, checkActive: () -> Unit): FileDigests {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile) {
            throw IOException("Not a regular file: ${file.name}")
        }
        val beforeLength = file.length()
        val beforeModified = file.lastModified()
        val sha256 = MessageDigest.getInstance("SHA-256")
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var totalBytes = 0L
        FileInputStream(file).use { input ->
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    sha256.update(buffer, 0, count)
                    md5.update(buffer, 0, count)
                    totalBytes += count
                }
            }
        }
        val afterLength = file.length()
        val afterModified = file.lastModified()
        if (!file.isFile || totalBytes != beforeLength || afterLength != beforeLength || afterModified != beforeModified) {
            throw IOException("File changed while computing digests: ${file.name}")
        }
        return FileDigests(
            sha256 = S3RequestSigner.hex(sha256.digest()),
            md5 = S3RequestSigner.hex(md5.digest()),
            sizeBytes = totalBytes,
            modifiedAtMillis = beforeModified,
        )
    }

    fun resolveSafeTarget(root: File, path: SyncRelativePath): File {
        val canonicalRoot = root.canonicalFile
        val rootPath = canonicalRoot.toPath()
        var current = canonicalRoot
        for (segment in path.value.split('/')) {
            if (segment.isEmpty() || segment == "." || segment == ".." || segment.startsWith('.')) {
                throw IOException("Unsafe sync path: ${path.value}")
            }
            current = File(current, segment)
            if (Files.isSymbolicLink(current.toPath())) {
                throw IOException("Refusing symlink in sync path: ${path.value}")
            }
        }
        val canonicalTarget = current.canonicalFile
        val targetPath = canonicalTarget.toPath()
        if (targetPath == rootPath || !targetPath.startsWith(rootPath)) {
            throw IOException("Target path escaped vault root: ${path.value}")
        }
        return canonicalTarget
    }

    fun commitDownloadedNew(
        root: File,
        path: SyncRelativePath,
        stagedFile: File,
        downloadedSha256: String,
    ): LocalCommitResult = commitStagedFile(
        root = root,
        path = path,
        stagedFile = stagedFile,
        downloadedSha256 = downloadedSha256,
        expectedLocalSha256ForOverwrite = null,
    )

    fun commitDownloadedUpdate(
        root: File,
        path: SyncRelativePath,
        stagedFile: File,
        downloadedSha256: String,
        expectedLocalSha256: String,
    ): LocalCommitResult = commitStagedFile(
        root = root,
        path = path,
        stagedFile = stagedFile,
        downloadedSha256 = downloadedSha256,
        expectedLocalSha256ForOverwrite = expectedLocalSha256,
    )

    fun commitConflictResolution(
        root: File,
        path: SyncRelativePath,
        stagedRemoteFile: File,
        downloadedSha256: String,
    ): LocalCommitResult = commitStagedFile(
        root = root,
        path = path,
        stagedFile = stagedRemoteFile,
        downloadedSha256 = downloadedSha256,
        expectedLocalSha256ForOverwrite = null,
    )

    fun deleteLocalIfUnchanged(
        root: File,
        path: SyncRelativePath,
        expectedLocalSha256: String,
    ): LocalDeleteResult {
        val canonicalRoot = root.canonicalFile
        val target = resolveSafeTarget(canonicalRoot, path)
        val targetPath = target.toPath()
        if (!Files.exists(targetPath, NOFOLLOW_LINKS)) {
            return LocalDeleteResult.AlreadyDeleted
        }
        if (Files.isDirectory(targetPath, NOFOLLOW_LINKS)) {
            return LocalDeleteResult.LocalModifiedAbort
        }
        val digests = computeFileDigests(target) {}
        if (!digests.sha256.equals(expectedLocalSha256, ignoreCase = true)) {
            return LocalDeleteResult.LocalModifiedAbort
        }
        if (SyncFileFilter.isMarkdownPath(path)) {
            VaultFileOps(canonicalRoot).trash(target)
        } else {
            Files.deleteIfExists(targetPath)
        }
        pruneEmptyParents(canonicalRoot, target.parentFile)
        return LocalDeleteResult.Deleted
    }

    private fun commitStagedFile(
        root: File,
        path: SyncRelativePath,
        stagedFile: File,
        downloadedSha256: String,
        expectedLocalSha256ForOverwrite: String?,
    ): LocalCommitResult {
        if (Files.isSymbolicLink(stagedFile.toPath()) || !stagedFile.isFile) {
            throw IOException("Staged sync file is missing")
        }
        val canonicalRoot = root.canonicalFile
        ensureParentDirectories(canonicalRoot, path)
        val target = resolveSafeTarget(canonicalRoot, path)
        val targetPath = target.toPath()
        if (Files.isDirectory(targetPath, NOFOLLOW_LINKS)) {
            throw IOException("Target path is a directory: ${path.value}")
        }
        if (!Files.exists(targetPath, NOFOLLOW_LINKS)) {
            moveAtomically(stagedFile.toPath(), targetPath)
            return LocalCommitResult.Committed(
                file = target,
                sizeBytes = target.length(),
                modifiedAtMillis = target.lastModified(),
            )
        }
        val localDigests = computeFileDigests(target) {}
        if (localDigests.sha256.equals(downloadedSha256, ignoreCase = true)) {
            Files.deleteIfExists(stagedFile.toPath())
            return LocalCommitResult.AlreadyIdentical(
                file = target,
                sizeBytes = localDigests.sizeBytes,
                modifiedAtMillis = localDigests.modifiedAtMillis,
            )
        }
        if (expectedLocalSha256ForOverwrite != null &&
            localDigests.sha256.equals(expectedLocalSha256ForOverwrite, ignoreCase = true)
        ) {
            moveAtomically(stagedFile.toPath(), targetPath)
            return LocalCommitResult.Committed(
                file = target,
                sizeBytes = target.length(),
                modifiedAtMillis = target.lastModified(),
            )
        }
        val (conflictPath, conflictFile) = copyToSiblingConflictFile(canonicalRoot, target)
        moveAtomically(stagedFile.toPath(), targetPath)
        return LocalCommitResult.CommittedWithConflictCopy(
            file = target,
            sizeBytes = target.length(),
            modifiedAtMillis = target.lastModified(),
            conflictPath = conflictPath,
            conflictFile = conflictFile,
        )
    }

    private fun ensureParentDirectories(canonicalRoot: File, path: SyncRelativePath) {
        val segments = path.value.split('/')
        var current = canonicalRoot
        for (segment in segments.dropLast(1)) {
            current = ensureSafeDirectory(File(current, segment), "in sync path: ${path.value}")
        }
    }

    private fun ensureChildDirectory(parent: File, name: String): File =
        ensureSafeDirectory(File(parent, name), ": $name")

    private fun ensureSafeDirectory(directory: File, detail: String): File {
        val dirPath = directory.toPath()
        if (Files.isSymbolicLink(dirPath)) {
            throw IOException("Refusing symlink directory $detail")
        }
        if (Files.exists(dirPath, NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(dirPath, NOFOLLOW_LINKS)) {
                throw IOException("Expected directory $detail")
            }
        } else {
            try {
                Files.createDirectory(dirPath)
            } catch (_: FileAlreadyExistsException) {
                if (Files.isSymbolicLink(dirPath) || !Files.isDirectory(dirPath, NOFOLLOW_LINKS)) {
                    throw IOException("Invalid directory $detail")
                }
            }
        }
        return directory
    }

    private fun copyToSiblingConflictFile(canonicalRoot: File, target: File): Pair<SyncRelativePath, File> {
        val folder = checkNotNull(target.parentFile) { "Target has no parent directory" }
        if (Files.isSymbolicLink(folder.toPath())) {
            throw IOException("Refusing symlink directory for conflict copy")
        }
        val timestamp = LocalDateTime.now().format(CONFLICT_TIMESTAMP)
        for (copy in generateSequence(1) { it + 1 }) {
            val candidate = File(folder, conflictCopyName(target.name, timestamp, copy))
            if (candidate == target || Files.isSymbolicLink(candidate.toPath())) continue
            val channel = try {
                FileChannel.open(candidate.toPath(), CREATE_NEW, WRITE)
            } catch (_: FileAlreadyExistsException) {
                continue
            }
            var completed = false
            try {
                channel.use { outChannel ->
                    FileInputStream(target).use { input ->
                        val buffer = ByteArray(STREAM_BUFFER_SIZE)
                        val byteBuffer = ByteBuffer.wrap(buffer)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) {
                                byteBuffer.limit(count)
                                byteBuffer.position(0)
                                while (byteBuffer.hasRemaining()) {
                                    outChannel.write(byteBuffer)
                                }
                            }
                        }
                    }
                    outChannel.force(true)
                }
                completed = true
            } finally {
                if (!completed) {
                    runCatching { Files.deleteIfExists(candidate.toPath()) }
                }
            }
            val canonicalConflict = candidate.canonicalFile
            if (!canonicalConflict.toPath().startsWith(canonicalRoot.toPath())) {
                runCatching { Files.deleteIfExists(candidate.toPath()) }
                throw IOException("Conflict copy escaped vault root")
            }
            val rel = canonicalConflict.relativeTo(canonicalRoot).invariantSeparatorsPath
            val conflictPath = SyncRelativePath.parseOrNull(rel)
                ?: throw IOException("Invalid conflict copy path: $rel")
            return conflictPath to canonicalConflict
        }
        error("Conflict filename sequence ended")
    }

    private fun conflictCopyName(fileName: String, timestamp: String, copy: Int): String {
        val dotIndex = fileName.lastIndexOf('.')
        val rawStem: String
        val rawExt: String
        if (dotIndex > 0 && dotIndex < fileName.length - 1) {
            rawStem = fileName.substring(0, dotIndex)
            rawExt = fileName.substring(dotIndex)
        } else {
            rawStem = fileName
            rawExt = ""
        }
        val marker = if (copy == 1) " (conflicted copy $timestamp)" else " (conflicted copy $timestamp) $copy"
        val markerBytes = marker.toByteArray(Charsets.UTF_8).size
        val ext = if (markerBytes + rawExt.toByteArray(Charsets.UTF_8).size <= MAX_FILENAME_BYTES) {
            rawExt
        } else {
            rawExt.takeUtf8Bytes((MAX_FILENAME_BYTES - markerBytes).coerceAtLeast(0))
        }
        val suffix = "$marker$ext"
        val stemBudget = (MAX_FILENAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        val stem = rawStem.takeUtf8Bytes(stemBudget).trimEnd()
        return "$stem$suffix"
    }

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < length) {
            val codePoint = codePointAt(end)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return substring(0, end)
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, REPLACE_EXISTING)
        }
    }

    private fun pruneEmptyParents(canonicalRoot: File, startDir: File?) {
        val rootPath = canonicalRoot.toPath()
        var current = startDir
        while (current != null && current != canonicalRoot) {
            val currentPath = current.toPath()
            if (!currentPath.startsWith(rootPath)) break
            if (Files.isSymbolicLink(currentPath) || current.name.startsWith('.')) break
            val children = current.listFiles() ?: break
            if (children.isNotEmpty()) break
            if (!current.delete()) break
            current = current.parentFile
        }
    }

    private fun deleteEntrySafely(boundary: File, entry: File) {
        val entryPath = entry.toPath()
        if (Files.isSymbolicLink(entryPath)) {
            runCatching { Files.deleteIfExists(entryPath) }
            return
        }
        val canonicalEntry = runCatching { entry.canonicalFile }.getOrNull() ?: return
        if (!canonicalEntry.toPath().startsWith(boundary.toPath()) || canonicalEntry == boundary) return
        if (canonicalEntry.isDirectory) {
            canonicalEntry.listFiles().orEmpty().forEach { child ->
                deleteEntrySafely(boundary, child)
            }
        }
        runCatching { Files.deleteIfExists(entryPath) }
    }
}
