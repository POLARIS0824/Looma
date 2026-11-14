package com.example.looma.data.note

import com.example.looma.data.index.FileFingerprint
import com.example.looma.domain.model.NoteId

internal class NoteTextCache {

    private data class Entry(val fingerprint: FileFingerprint, val text: String)

    private val entries = LinkedHashMap<NoteId, Entry>(16, 0.75f, true)
    private var characters = 0L

    @Synchronized
    fun get(id: NoteId, fingerprint: FileFingerprint): String? =
        entries[id]?.takeIf { it.fingerprint == fingerprint }?.text

    @Synchronized
    fun put(id: NoteId, fingerprint: FileFingerprint, text: String) {
        remove(id)
        if (text.length > MAX_CHARACTERS) return
        entries[id] = Entry(fingerprint, text)
        characters += text.length
        while (characters > MAX_CHARACTERS || entries.size > MAX_ENTRIES) {
            remove(entries.keys.first())
        }
    }

    @Synchronized
    fun remove(id: NoteId) {
        characters -= entries.remove(id)?.text?.length ?: 0
    }

    private companion object {
        const val MAX_CHARACTERS = 8 * 1024 * 1024
        const val MAX_ENTRIES = 4096
    }
}
