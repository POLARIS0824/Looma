package com.example.looma.data.note

import com.example.looma.data.index.FileFingerprint
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal const val MAX_MARKDOWN_BYTES = 2L * 1024 * 1024

internal fun readMarkdown(file: File): String = readMarkdownBytes(file).toString(Charsets.UTF_8)

internal fun readMarkdownBytes(file: File): ByteArray = readBytesBounded(file, MAX_MARKDOWN_BYTES)

internal fun openMarkdownReader(file: File, maxBytes: Long = MAX_MARKDOWN_BYTES): BufferedReader {
    if (file.length() > maxBytes) throw NoteTooLargeException(file.name, maxBytes)
    val bounded = BoundedMarkdownInputStream(file.inputStream(), file.name, maxBytes)
    return BufferedReader(InputStreamReader(bounded, Charsets.UTF_8), DEFAULT_BUFFER_SIZE)
}

internal fun readBytesBounded(file: File, maxBytes: Long): ByteArray {
    val knownLength = file.length()
    if (knownLength > maxBytes) throw NoteTooLargeException(file.name, maxBytes)
    return file.inputStream().use { input ->
        val initialCapacity = knownLength.coerceIn(32L, DEFAULT_BUFFER_SIZE.toLong() * 8).toInt()
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total + 1).toInt())
            if (count == -1) break
            total += count
            if (total > maxBytes) throw NoteTooLargeException(file.name, maxBytes)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

private class BoundedMarkdownInputStream(
    private val delegate: InputStream,
    private val fileName: String,
    private val maxBytes: Long,
) : InputStream() {
    private var total = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0 && ++total > maxBytes) {
            throw NoteTooLargeException(fileName, maxBytes)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val maxRead = minOf(len.toLong(), maxBytes - total + 1).toInt()
        val count = delegate.read(b, off, maxRead)
        if (count > 0) {
            total += count
            if (total > maxBytes) throw NoteTooLargeException(fileName, maxBytes)
        }
        return count
    }

    override fun close() = delegate.close()
}

internal fun markdownBytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8).also {
    if (it.size > MAX_MARKDOWN_BYTES) throw NoteTooLargeException("note", MAX_MARKDOWN_BYTES)
}

internal fun writeMarkdown(file: File, text: String, beforeReplace: () -> Unit = {}) {
    writeBytesAtomically(file, markdownBytes(text), beforeReplace)
}

internal fun writeBytesAtomically(
    file: File,
    bytes: ByteArray,
    beforeReplace: () -> Unit = {},
): FileFingerprint {
    val destination = file.absoluteFile.toPath()
    val temporary = Files.createTempFile(destination.parent, ".looma-", ".tmp")
    try {
        FileOutputStream(temporary.toFile()).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        val fingerprint = FileFingerprint(bytes.size.toLong(), temporary.toFile().lastModified())
        beforeReplace()
        Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        return fingerprint
    } catch (failure: Exception) {
        try {
            Files.deleteIfExists(temporary)
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

class NoteTooLargeException(name: String, val maxBytes: Long) :
    IOException("file is too large: $name (limit $maxBytes bytes)")
