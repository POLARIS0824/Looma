package com.example.looma.data.index

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.domain.wiki.WikiGraph
import com.example.looma.domain.wiki.WikiNote

internal class WikiIndexSnapshot(val vaultId: VaultId, notes: List<IndexedNote>) {
    private data class Entry(val title: String, val targets: List<String>)

    private val entries = notes.associate { it.id to it.wikiEntry() }
    private val graph by lazy {
        WikiGraph(
            notes = entries.map { (id, entry) -> WikiNote(id, entry.title) },
            linksByNote = entries.mapValues { (_, entry) -> entry.targets },
        )
    }
    private var lastContext: Pair<NoteId, WikiContext>? = null

    fun changesWith(delta: IndexDelta): Boolean =
        delta.removed.any { NoteId(vaultId, it) in entries } ||
            delta.upserts.any { entries[it.id] != it.wikiEntry() }

    @Synchronized
    fun context(id: NoteId): WikiContext {
        val cached = lastContext
        if (cached?.first == id) return cached.second
        return graph.context(id).also { lastContext = id to it }
    }

    private fun IndexedNote.wikiEntry() = Entry(title, links.map { it.target })
}
