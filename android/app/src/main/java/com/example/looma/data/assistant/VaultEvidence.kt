package com.example.looma.data.assistant

import com.example.looma.data.index.SearchSignature
import com.example.looma.data.note.StoredMarkdown
import com.example.looma.data.note.NoteListEntry
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.search.SemanticIndexMissingException
import com.example.looma.data.search.SemanticModelMissingException
import com.example.looma.data.search.SemanticSearchRepository
import android.util.LruCache
import com.example.looma.data.search.SemanticChunk
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

internal enum class EvidenceMatchSource { Semantic, Lexical, Hybrid }
internal enum class WeaveSemanticStatus { Ready, Partial, ModelMissing, ModelDownloading, IndexMissing, Indexing, Unavailable, Failed }

internal data class Evidence(
    val id: NoteId,
    val anchor: PassageAnchor,
    val text: String,
    val section: String,
    val similarity: Double? = null,
    val source: EvidenceMatchSource = if (similarity != null) EvidenceMatchSource.Semantic else EvidenceMatchSource.Lexical,
)
internal data class EvidenceSet(
    val hits: List<Evidence>,
    val semantic: Boolean,
    val partial: Boolean,
    val semanticStatus: WeaveSemanticStatus = if (semantic) WeaveSemanticStatus.Ready else WeaveSemanticStatus.Unavailable,
)
internal data class Connection(
    val source: NoteId,
    val title: String,
    val evidence: Evidence,
    val reference: String,
    val tags: Set<String>,
    val keywords: List<String>,
    val matchedTitle: Boolean,
    val matchSource: EvidenceMatchSource,
    val passage: NotePassage?,
)
internal data class WeaveSuggestions(
    val items: List<Connection>,
    val semanticStatus: WeaveSemanticStatus,
    val partial: Boolean,
)
internal class EvidenceDocument(val source: StoredMarkdown) {
    var chunks: List<SemanticChunk>? = null
}

internal data class EvidenceScope(val vault: Vault, val notes: List<NoteListEntry>, val partial: Boolean,
    val documents: LruCache<NoteId, EvidenceDocument> = object : LruCache<NoteId, EvidenceDocument>(4 * 1024 * 1024) {
        override fun sizeOf(key: NoteId, value: EvidenceDocument) = value.source.content.length.coerceAtLeast(1)
    })

internal class VaultEvidence(
    private val vaults: VaultRepository,
    private val notes: NoteRepository,
    private val vectors: SemanticSearchRepository,
) {
    suspend fun scope(vault: Vault): EvidenceScope {
        val scan = notes.evidenceNotes(vault)
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
        var semanticStatus = WeaveSemanticStatus.Unavailable
        val semantic = if (!vectors.supported) {
            null
        } else {
            try {
                vectors.search(question, candidates, relatedDocumentTitle).also {
                    semanticStatus = when (vectors.indexState.first()) {
                        SemanticWorkState.Queued, is SemanticWorkState.Running -> WeaveSemanticStatus.Indexing
                        SemanticWorkState.Ready -> WeaveSemanticStatus.Ready
                        else -> WeaveSemanticStatus.Partial
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: SemanticModelMissingException) {
                semanticStatus = when (vectors.modelState.first()) {
                    SemanticWorkState.Queued, is SemanticWorkState.Running -> WeaveSemanticStatus.ModelDownloading
                    else -> WeaveSemanticStatus.ModelMissing
                }
                null
            } catch (_: SemanticIndexMissingException) {
                val modelState = vectors.modelState.first()
                semanticStatus = when {
                    modelState == SemanticWorkState.Queued || modelState is SemanticWorkState.Running -> WeaveSemanticStatus.ModelDownloading
                    modelState != SemanticWorkState.Ready -> WeaveSemanticStatus.ModelMissing
                    else -> when (vectors.indexState.first()) {
                        SemanticWorkState.Queued, is SemanticWorkState.Running -> WeaveSemanticStatus.Indexing
                        else -> WeaveSemanticStatus.IndexMissing
                    }
                }
                null
            } catch (_: Exception) {
                semanticStatus = WeaveSemanticStatus.Failed
                null
            } catch (_: LinkageError) {
                semanticStatus = WeaveSemanticStatus.Failed
                null
            }
        }
        val dense = semantic.orEmpty().map {
            Evidence(it.id, it.anchor, it.excerpt, it.section, it.similarity, EvidenceMatchSource.Semantic)
        }
        val tests = (terms + question).distinct().map(SearchSignature::prepare)
        val reader = notes.lexicalReader(scope.vault)
        val shortlist = mutableListOf<Pair<Int, NoteListEntry>>()
        val lexical = mutableListOf<Pair<Int, Evidence>>()
        var skipped = false
        val checkActive = coroutineContext::ensureActive
        suspend fun document(entry: NoteListEntry, meta: Int): EvidenceDocument? {
            val id = entry.summary.id
            scope.documents.get(id)?.let { return it }
            return try {
                reader.read(id, tests, meta > 0)?.let { source ->
                    EvidenceDocument(source).also { scope.documents.put(id, it) }
                }
            } catch (_: IOException) {
                skipped = true
                null
            }
        }
        fun meta(entry: NoteListEntry) = score(with(entry.summary) {
            title + " " + id.path.relativePath + " " + tags.joinToString(" ")
        }, terms)
        for (entry in scope.notes) {
            checkActive()
            val metadata = meta(entry)
            val source = document(entry, metadata)?.source ?: continue
            val rank = score(source.content, terms) + metadata + if (source.content.contains(question, true)) 1000 else 0
            if (rank > 0) shortlist += rank to entry
        }
        for ((_, entry) in shortlist.sortedByDescending { it.first }.take(limit * 3)) {
            checkActive()
            val summary = entry.summary
            val meta = meta(entry)
            val document = document(entry, meta) ?: continue
            val note = document.source
            val revision = note.revision as? NoteRevision.Known ?: continue
            val chunks = document.chunks ?: semanticChunks(note.content, checkActive).also { document.chunks = it }
            val passages = chunks.mapNotNull { chunk ->
                val text = note.content.substring(chunk.start, chunk.end)
                val direct = text.contains(question, ignoreCase = true)
                val rank = score(text, terms) + meta + if (direct) 100 else 0
                if (rank == 0) null else rank to Evidence(summary.id,
                    PassageAnchor(revision.sha256, chunk.start, chunk.end), text, chunk.section, source = EvidenceMatchSource.Lexical)
            }.sortedByDescending { it.first }.take(2)
            lexical += passages
            if (lexical.size > limit * 4) {
                lexical.sortByDescending { it.first }
                lexical.subList(limit * 2, lexical.size).clear()
            }
        }
        val byScore = lexical.sortedByDescending { it.first }
        val keywords = byScore.map { it.second }
        val ordered = buildList {
            addAll(keywords.filter { it.text.contains(question, true) })
            for (i in 0 until maxOf(keywords.size, dense.size)) {
                keywords.getOrNull(i)?.let(::add)
                dense.getOrNull(i)?.let(::add)
            }
        }
        val denseByKey = dense.associateBy { it.id to it.anchor }
        val lexicalByKey = keywords.associateBy { it.id to it.anchor }
        val perNote = mutableMapOf<NoteId, Int>()
        val hits = ordered.distinctBy { it.id to it.anchor }.map { hit ->
            val key = hit.id to hit.anchor
            val denseHit = denseByKey[key]
            if (denseHit != null && key in lexicalByKey) {
                hit.copy(similarity = denseHit.similarity, source = EvidenceMatchSource.Hybrid)
            } else {
                hit
            }
        }.filter {
            val count = perNote.getOrDefault(it.id, 0)
            if (count < 2) perNote[it.id] = count + 1
            count < 2
        }.take(limit)
        checkVault(scope.vault)
        val partialIndex = semantic != null && vectors.indexState.first() != SemanticWorkState.Ready
        EvidenceSet(hits, semantic != null, scope.partial || skipped || partialIndex, semanticStatus)
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

    suspend fun related(vault: Vault, source: NoteId, buffer: String, passage: NotePassage? = null): WeaveSuggestions = withContext(Dispatchers.IO) {
        val scope = scope(vault)
        val current = scope.notes.firstOrNull { it.summary.id == source }?.summary
            ?: return@withContext WeaveSuggestions(emptyList(), WeaveSemanticStatus.Unavailable, scope.partial)
        val wiki = notes.wikiContext(source)
        val linked = MarkdownIndex.index(buffer, current.title).links.mapNotNull { wiki.resolve(it.target) }.toSet() +
            wiki.backlinks.map { it.id } + source
        val eligible = scope.notes.filter { it.summary.id !in linked }
        val metadata = eligible.associate { it.summary.id to it.summary }
        val query = passage?.let { it.excerpt(1024).trim() } ?: (current.title + " " + current.tags.joinToString(" ") + " " + buffer.take(640)).trim()
        val sourceTerms = terms(query)
        val result = search(scope.copy(notes = eligible), query, 12, relatedDocumentTitle = current.title)
        val items = result.hits.distinctBy { it.id }.mapNotNull { hit ->
            val path = wikiPathOf(hit.id.path)
            if (wiki.resolve(path) != hit.id || path.any { it in "[]#|\r\n" }) return@mapNotNull null
            val target = metadata.getValue(hit.id)
            val sharedTags = current.tags.intersect(target.tags)
            val sharedTerms = sourceTerms.filter { hit.text.contains(it, true) || target.title.contains(it, true) }
                .sortedByDescending { it.length }.take(3)
            val matchedTitle = sourceTerms.any { target.title.contains(it, true) }
            val hasSemantic = hit.similarity != null
            val hasExplainable = hit.source != EvidenceMatchSource.Semantic || sharedTerms.isNotEmpty() || sharedTags.isNotEmpty() || matchedTitle
            val matchSource = when {
                hasSemantic && hasExplainable -> EvidenceMatchSource.Hybrid
                hasSemantic -> EvidenceMatchSource.Semantic
                else -> EvidenceMatchSource.Lexical
            }
            Connection(source, target.title, hit, path, sharedTags, sharedTerms, matchedTitle, matchSource, passage)
        }.take(5)
        WeaveSuggestions(items, result.semanticStatus, result.partial)
    }

    suspend fun linkStillValid(vault: Vault, suggestion: Connection, buffer: String): Boolean = withContext(Dispatchers.IO) {
        if (resolve(vault, suggestion.evidence) == null) return@withContext false
        val wiki = notes.wikiContext(suggestion.source)
        val target = suggestion.evidence.id
        if (wiki.resolve(suggestion.reference) != target || wiki.backlinks.any { it.id == target }) return@withContext false
        val passage = suggestion.passage
        if (passage != null && passage.source != buffer) return@withContext false
        val at = passage?.insertAt ?: buffer.length
        val addition = suggestion.addition(buffer)
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

internal fun Connection.addition(buffer: String): String =
    if (passage?.inline == true) " [[$reference]]" else linkAddition(buffer, reference, passage?.insertAt ?: buffer.length)

internal fun linkAddition(buffer: String, path: String, at: Int = buffer.length): String {
    val newline = if ("\r\n" in buffer) "\r\n" else "\n"
    val separator = when {
        at == 0 || buffer.regionMatches(at - 2 * newline.length, newline + newline, 0, 2 * newline.length) -> ""
        buffer.regionMatches(at - newline.length, newline, 0, newline.length) -> newline
        else -> newline + newline
    }
    return separator + "[[" + path + "]]" + newline
}
