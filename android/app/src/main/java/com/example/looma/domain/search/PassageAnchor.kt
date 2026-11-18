package com.example.looma.domain.search

import com.example.looma.domain.model.NoteRevision
import kotlinx.serialization.Serializable

@Serializable
data class PassageAnchor(val revision: String, val start: Int, val end: Int) {
    fun matches(actualRevision: NoteRevision, source: String): Boolean =
        actualRevision is NoteRevision.Known
            && actualRevision.sha256 == revision
            && start >= 0
            && start < end
            && end <= source.length
            && !source[start].isLowSurrogate()
            && !source[end - 1].isHighSurrogate()
}
