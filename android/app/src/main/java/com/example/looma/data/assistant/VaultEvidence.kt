package com.example.looma.data.assistant

import com.example.looma.data.note.NoteListEntry
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.search.SemanticSearchRepository
import com.example.looma.data.search.semanticChunks
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.markdown.NotePassage
import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.Vault
import com.example.looma.domain.search.PassageAnchor
import com.example.looma.domain.search.SearchableNote
import com.example.looma.domain.wiki.wikiPathOf
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import com.example.looma.data.search.SemanticWorkState
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class Evidence(val id: NoteId, val anchor: PassageAnchor, val text: String, val section: String, val similarity: Double? = null)
internal data class EvidenceSet(val hits: List<Evidence>, val semantic: Boolean, val partial: Boolean)
internal data class Connection(val source: NoteId, val evidence: Evidence, val reference: String, val tags: Set<String>, val keywords: List<String>, val passage: NotePassage?)
internal data class EvidenceScope(val vault: Vault, val notes: List<NoteListEntry>, val partial: Boolean)

internal class VaultEvidence(
    private val vaults: VaultRepository,
    private val notes: NoteRepository,
    private val vectors: SemanticSearchRepository,
) {
    suspend fun scope(vault: Vault): EvidenceScope {
        val scan = notes.listNotes(vault)
        return EvidenceScope(vault, scan.items, !scan.complete || scan.unreadableCount > 0 || scan.stalledRelocation != null)
    }

    suspend fun checkVault(vault: Vault) {
        val current = vaults.requireCurrentVault()
        check(current.id == vault.id && current.rootPath == vault.rootPath)
    }

    suspend fun search(
        scope: EvidenceScope,
        query: String,
        limit: Int = 6,
        relatedDocumentTitle: String? = null,
    ): EvidenceSet = withContext(Dispatchers.IO) {
        checkVault(scope.vault)
        val question = query.trim().take(1024)
        if (question.isEmpty()) return@withContext EvidenceSet(emptyList(), false, scope.partial)
        val terms = terms(question)
        val candidates = scope.notes.map { SearchableNote(it.summary.id, it.summary.title, it.summary.tags) }
        val semantic = try {
            if (vectors.supported) vectors.search(question, candidates, relatedDocumentTitle) else null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
        val dense = semantic.orEmpty().map { Evidence(it.id, it.anchor, it.excerpt, it.section, it.similarity) }
        val exact = if (dense.isEmpty()) emptySet() else {
            notes.search(candidates, question).mapTo(mutableSetOf()) { it.id }
        }
        val shortlist = mutableListOf<Pair<Int, NoteListEntry>>()
        val lexical = mutableListOf<Pair<Int, Evidence>>()
        var skipped = false
        val checkActive = coroutineContext::ensureActive
        for (entry in scope.notes) {
            checkActive()
            val summary = entry.summary
            val meta = score(summary.title + " " + summary.id.path.relativePath + " " + summary.tags.joinToString(" "), terms)
            if (!semantic.isNullOrEmpty() && summary.id !in exact && meta == 0 &&
                score(entry.markdownSnippet, terms) == 0) continue
            val hint = score(entry.markdownSnippet, terms) + meta
            val rank = if (dense.isEmpty()) {
                val note = try {
                    notes.readSearchSource(summary.id, scope.vault.rootPath, requireUtf8 = true)
                } catch (_: IOException) {
                    skipped = true
                    continue
                }
                score(note.content, terms) + meta + if (note.content.contains(question, true)) 1000 else 0
            } else hint + if (summary.id in exact) 1000 else 0
            if (rank > 0) shortlist += rank to entry
        }
        for ((_, entry) in shortlist.sortedByDescending { it.first }.take(limit * 3)) {
            checkActive()
            val summary = entry.summary
            val meta = score(summary.title + " " + summary.id.path.relativePath + " " + summary.tags.joinToString(" "), terms)
            val note = try {
                notes.readSearchSource(summary.id, scope.vault.rootPath, requireUtf8 = true)
            } catch (_: IOException) {
                skipped = true
                continue
            }
            val revision = note.revision as? NoteRevision.Known ?: continue
            val passages = semanticChunks(note.content, checkActive).mapNotNull { chunk ->
                val text = note.content.substring(chunk.start, chunk.end)
                val direct = text.contains(question, ignoreCase = true)
                val rank = score(text, terms) + meta + if (direct) 100 else 0
                if (rank == 0) null else rank to Evidence(summary.id,
                    PassageAnchor(revision.sha256, chunk.start, chunk.end), text, chunk.section)
            }.sortedByDescending { it.first }.take(2)
            lexical += passages
            if (lexical.size > limit * 4) {
                lexical.sortByDescending { it.first }
                lexical.subList(limit * 2, lexical.size).clear()
            }
        }
        val byScore = lexical.sortedByDescending { it.first }
        val ordered = byScore.filter { it.second.text.contains(question, true) }.map { it.second } +
            dense + byScore.map { it.second }
        val perNote = mutableMapOf<NoteId, Int>()
        val hits = ordered.distinctBy { it.id to it.anchor }.filter {
            val count = perNote.getOrDefault(it.id, 0)
            if (count < 2) perNote[it.id] = count + 1
            count < 2
        }.take(limit)
        checkVault(scope.vault)
        val partialIndex = dense.isNotEmpty() && vectors.indexState.first() != SemanticWorkState.Ready
        EvidenceSet(hits, semantic != null, scope.partial || skipped || partialIndex)
    }

    suspend fun resolve(vault: Vault, hit: Evidence, expand: Boolean = false): Evidence? = withContext(Dispatchers.IO) {
        if (hit.id.vaultId != vault.id) return@withContext null
        val note = try {
            notes.readSearchSource(hit.id, vault.rootPath, requireUtf8 = true)
        } catch (_: IOException) {
            return@withContext null
        }
        if (!hit.anchor.matches(note.revision, note.content)) return@withContext null
        var start = hit.anchor.start
        var end = hit.anchor.end
        if (expand) {
            start = (start - 220).coerceAtLeast(0)
            end = (end + 220).coerceAtMost(note.content.length)
            if (note.content[start].isLowSurrogate()) start++
            if (note.content[end - 1].isHighSurrogate()) end--
        }
        hit.copy(anchor = hit.anchor.copy(start = start, end = end), text = note.content.substring(start, end))
    }

    suspend fun related(vault: Vault, source: NoteId, buffer: String, passage: NotePassage? = null): List<Connection> = withContext(Dispatchers.IO) {
        val scope = scope(vault)
        val current = scope.notes.firstOrNull { it.summary.id == source }?.summary ?: return@withContext emptyList()
        val wiki = notes.wikiContext(source)
        val linked = MarkdownIndex.index(buffer, current.title).links.mapNotNull { wiki.resolve(it.target) }.toSet() +
            wiki.backlinks.map { it.id } + source
        val eligible = scope.notes.filter { it.summary.id !in linked }
        val metadata = eligible.associate { it.summary.id to it.summary }
        val query = passage?.let { it.excerpt(1024).trim() } ?: (current.title + " " + current.tags.joinToString(" ") + " " + buffer.take(640)).trim()
        val sourceTerms = terms(query)
        search(scope.copy(notes = eligible), query, 12, relatedDocumentTitle = current.title).hits.distinctBy { it.id }.mapNotNull { hit ->
            val path = wikiPathOf(hit.id.path)
            if (wiki.resolve(path) != hit.id || path.any { it in "[]#|\r\n" }) return@mapNotNull null
            val target = metadata.getValue(hit.id)
            val sharedTerms = sourceTerms.filter { hit.text.contains(it, true) || target.title.contains(it, true) }
                .sortedByDescending { it.length }.take(3)
            Connection(source, hit, path, current.tags.intersect(target.tags), sharedTerms, passage)
        }.take(5)
    }

    suspend fun linkStillValid(vault: Vault, suggestion: Connection, buffer: String): Boolean = withContext(Dispatchers.IO) {
        if (resolve(vault, suggestion.evidence) == null) return@withContext false
        val wiki = notes.wikiContext(suggestion.source)
        val target = suggestion.evidence.id
        if (wiki.resolve(suggestion.reference) != target || wiki.backlinks.any { it.id == target }) return@withContext false
        val passage = suggestion.passage
        if (passage != null && passage.source != buffer) return@withContext false
        val at = passage?.insertAt ?: buffer.length
        val addition = linkAddition(buffer, suggestion.reference, at)
        val links = MarkdownIndex.index(buffer.replaceRange(at, at, addition), "").links
        val inserted = links.singleOrNull { it.range.first in at until (at + addition.length) }
        inserted != null && wiki.resolve(inserted.target) == target &&
            links.none { it !== inserted && wiki.resolve(it.target) == target }
    }

    private fun score(text: String, terms: List<String>) = terms.sumOf { term ->
        if (text.contains(term, ignoreCase = true)) minOf(term.length, 12) else 0
    }

    private fun terms(text: String): List<String> = WORDS
        .findAll(text).flatMap { match ->
            val word = match.value
            if (word.length > 2 && Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
                sequenceOf(word) + word.windowedSequence(2)
            } else sequenceOf(word)
        }.filter { (it.length > 1 && it.lowercase() !in STOPWORDS) || text.trim().length == 1 }
        .distinct().take(32).toList()

    private companion object {
        val STOPWORDS = setOf("the", "and", "for", "from", "with", "this", "that", "what", "how", "在这", "什么", "如何", "这个", "的是")
        val WORDS = Regex("""[\p{IsHan}]+|[\p{L}\p{N}_#./-]+""")
    }
}

internal fun linkAddition(buffer: String, path: String, at: Int = buffer.length): String {
    val newline = if ("\r\n" in buffer) "\r\n" else "\n"
    val separator = when {
        at == 0 || buffer.regionMatches(at - 2 * newline.length, newline + newline, 0, 2 * newline.length) -> ""
        buffer.regionMatches(at - newline.length, newline, 0, newline.length) -> newline
        else -> newline + newline
    }
    return separator + "[[" + path + "]]" + newline
}
