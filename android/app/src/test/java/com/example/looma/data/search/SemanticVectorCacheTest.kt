package com.example.looma.data.search

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.search.SemanticChunk
import com.example.looma.embedding.EmbeddingGemma
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SemanticVectorCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun anotherCacheInstanceRanksTheSavedPassagesAndRejectsEditedNotes() {
        val root = temporary.newFolder()
        val cache = SemanticVectorCache(root)
        val note = NoteId(VaultId("vault"), NotePath("folder/notes.md"))
        val otherVault = note.copy(vaultId = VaultId("other"))
        val source = "traffic\nsealing"
        val revision = revisionOf(source)
        val chunks = listOf(SemanticChunk(0, 7, "Commute"), SemanticChunk(8, 15, "Repairs"))
        cache.forNote(note).replace(revision, chunks) { chunk -> axis(chunks.indexOf(chunk)) }
        cache.forNote(otherVault).replace(revision, chunks) { axis(1) }

        val reopened = SemanticVectorCache(root)
        val best = checkNotNull(reopened.forNote(note).best(revision, source, axis(1)) {}?.best)
        assertEquals("Repairs", best.section)
        assertEquals("sealing", source.substring(best.anchor.start, best.anchor.end))
        val newSource = "traffic\npatched"
        assertNull(reopened.forNote(note).best(revisionOf(newSource), newSource, axis(1)) {})

        reopened.prune(note.vaultId, emptySet())
        assertNull(reopened.forNote(note).best(revision, source, axis(1)) {})
        assertEquals(1.0, reopened.forNote(otherVault).best(revision, source, axis(1)) {}?.best?.score)
    }

    @Test
    fun anInterruptedReplacementKeepsThePreviousCompleteVectors() {
        val path = File(temporary.newFolder(), "note.vec")
        val vectors = NoteVectors(path)
        val passage = listOf(SemanticChunk(0, 3, ""))
        vectors.replace(revisionOf("old"), passage) { axis(0) }

        assertThrows(IOException::class.java) {
            vectors.replace(revisionOf("new"), passage) { throw IOException("interrupted embedding") }
        }

        assertEquals(1.0, NoteVectors(path).best(revisionOf("old"), "old", axis(0)) {}?.best?.score)
        assertNull(NoteVectors(path).best(revisionOf("new"), "new", axis(0)) {})
    }

    private fun revisionOf(source: String) = NoteRevision.of(source.toByteArray()) as NoteRevision.Known

    private fun axis(index: Int) = FloatArray(EmbeddingGemma.DIMENSIONS).apply { this[index] = 1f }
}
