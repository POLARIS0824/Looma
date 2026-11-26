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
import com.example.looma.domain.markdown.frontmatterEnd
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.Vault
import com.example.looma.domain.search.PassageAnchor
import com.example.looma.domain.search.SearchableNote
import com.example.looma.domain.wiki.wikiPathOf
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.ln
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
    val alreadyLinked: Boolean = false,
    val pinned: Boolean = false,
)
internal data class WeaveSuggestions(
    val items: List<Connection>,
    val semanticStatus: WeaveSemanticStatus,
    val partial: Boolean,
    val hiddenCount: Int = 0,
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
    private val feedback: WeaveFeedbackStore? = null,
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
                    semanticStatus = readySemanticStatus()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: SemanticModelMissingException) {
                semanticStatus = modelMissingStatus()
                null
            } catch (_: SemanticIndexMissingException) {
                semanticStatus = indexMissingStatus()
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

    suspend fun togglePin(source: NoteId, target: NoteId): Boolean = withContext(Dispatchers.IO) {
        val store = feedback ?: return@withContext false
        val current = store.statesFor(source)[target.path]
        if (current == WeaveFeedbackState.Pinned) {
            store.clearState(source, target.path)
            false
        } else {
            store.setState(source, target.path, WeaveFeedbackState.Pinned)
            true
        }
    }

    suspend fun hideConnection(source: NoteId, target: NoteId) {
        withContext(Dispatchers.IO) {
            val store = feedback ?: return@withContext
            store.setState(source, target.path, WeaveFeedbackState.Hidden)
        }
    }

    suspend fun unhideAllConnections(source: NoteId): Int = withContext(Dispatchers.IO) {
        val store = feedback ?: return@withContext 0
        store.clearHidden(source)
    }

    suspend fun related(
        vault: Vault,
        source: NoteId,
        buffer: String,
        passage: NotePassage? = null,
        excludeLinked: Boolean = true,
    ): WeaveSuggestions = withContext(Dispatchers.IO) {
        val checkActive = coroutineContext::ensureActive
        val scope = scope(vault)
        val current = scope.notes.firstOrNull { it.summary.id == source }?.summary
            ?: return@withContext WeaveSuggestions(emptyList(), WeaveSemanticStatus.Unavailable, scope.partial)
        val wiki = notes.wikiContext(source)
        val linkedOutAndBack = MarkdownIndex.index(buffer, current.title).links.mapNotNull { wiki.resolve(it.target) }.toSet() +
            wiki.backlinks.map { it.id }
        val feedbackStates = feedback?.statesFor(source).orEmpty()
        val hiddenPaths = feedbackStates.filterValues { it == WeaveFeedbackState.Hidden }.keys
        val pinnedPaths = feedbackStates.filterValues { it == WeaveFeedbackState.Pinned }.keys
        val hiddenCount = scope.notes.count { it.summary.id != source && it.summary.id.path in hiddenPaths }
        val eligible = scope.notes.filter { entry ->
            val id = entry.summary.id
            id != source && id.path !in hiddenPaths && (!excludeLinked || id !in linkedOutAndBack)
        }
        if (eligible.isEmpty()) {
            val status = if (vectors.supported) currentSemanticStatus() else WeaveSemanticStatus.Unavailable
            return@withContext WeaveSuggestions(emptyList(), status, scope.partial, hiddenCount)
        }
        val metadata = eligible.associate { it.summary.id to it.summary }
        val candidates = eligible.map { SearchableNote(it.summary.id, it.summary.title, it.summary.tags) }
        val bodyStart = frontmatterEnd(buffer)
        val sourceBody = if (bodyStart in 0..buffer.length) buffer.substring(bodyStart) else buffer
        val focusedPassage = passage?.excerpt(1024)?.trim()?.takeIf { it.isNotEmpty() }
        val sourceRevision = NoteRevision.of(buffer.toByteArray(Charsets.UTF_8)) as? NoteRevision.Known

        var semanticStatus = WeaveSemanticStatus.Unavailable
        val semantic = if (!vectors.supported) {
            null
        } else {
            try {
                vectors.searchRelated(
                    sourceId = source,
                    sourceRevision = sourceRevision,
                    sourceTitle = current.title,
                    sourceBody = sourceBody,
                    focusedPassage = focusedPassage,
                    candidates = candidates,
                ).also {
                    semanticStatus = readySemanticStatus()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: SemanticModelMissingException) {
                semanticStatus = modelMissingStatus()
                null
            } catch (_: SemanticIndexMissingException) {
                semanticStatus = indexMissingStatus()
                null
            } catch (_: Exception) {
                semanticStatus = WeaveSemanticStatus.Failed
                null
            } catch (_: LinkageError) {
                semanticStatus = WeaveSemanticStatus.Failed
                null
            }
        }

        val lexicalQuery = focusedPassage?.let { current.title + " " + cleanForLexical(it) }
            ?: (current.title + " " + current.tags.joinToString(" ") + " " + cleanForLexical(sourceBody).take(2400))
        val sourceTerms = terms(lexicalQuery)
        val lexicalCandidates = findLexicalConnections(
            scope = scope,
            eligible = eligible,
            currentTags = current.tags,
            sourceTerms = sourceTerms,
            checkActive = checkActive,
        )
        val lexicalByNote = lexicalCandidates.associateBy { it.evidence.id }

        val validWikiPath: (NoteId) -> String? = { id ->
            val path = wikiPathOf(id.path)
            if (wiki.resolve(path) == id && path.none { it in "[]#|\r\n" }) path else null
        }

        val semanticConnections = semantic.orEmpty().mapNotNull { hit ->
            val path = validWikiPath(hit.id) ?: return@mapNotNull null
            val target = metadata.getValue(hit.id)
            val lex = lexicalByNote[hit.id]
            val sharedTags = current.tags.intersect(target.tags)
            val sharedTerms = lex?.matchedTerms ?: sourceTerms
                .filter { hit.excerpt.contains(it, true) || target.title.contains(it, true) }
                .sortedByDescending { it.length }
                .take(3)
            val matchedTitle = lex?.matchedTitle ?: sourceTerms.any { target.title.contains(it, true) }
            val hasExplainable = lex != null || sharedTerms.isNotEmpty() || sharedTags.isNotEmpty() || matchedTitle
            val matchSource = if (hasExplainable) EvidenceMatchSource.Hybrid else EvidenceMatchSource.Semantic
            val evidence = Evidence(hit.id, hit.anchor, hit.excerpt, hit.section, hit.similarity, matchSource)
            val hybridBonus = (if (sharedTags.isNotEmpty()) 0.06 else 0.0) + (if (sharedTerms.size >= 2) 0.04 else 0.0)
            val rank = hit.similarity + hybridBonus
            rank to Connection(
                source = source,
                title = target.title,
                evidence = evidence,
                reference = path,
                tags = sharedTags,
                keywords = sharedTerms,
                matchedTitle = matchedTitle,
                matchSource = matchSource,
                passage = passage,
                alreadyLinked = hit.id in linkedOutAndBack,
                pinned = hit.id.path in pinnedPaths,
            )
        }.sortedByDescending { it.first }.map { it.second }

        val items = if (semantic != null) {
            val seen = semanticConnections.mapTo(HashSet()) { it.evidence.id }
            val extraLexical = lexicalCandidates.mapNotNull { lex ->
                if (lex.evidence.id in seen) return@mapNotNull null
                val path = validWikiPath(lex.evidence.id) ?: return@mapNotNull null
                val target = metadata.getValue(lex.evidence.id)
                Connection(
                    source = source,
                    title = target.title,
                    evidence = lex.evidence,
                    reference = path,
                    tags = lex.sharedTags,
                    keywords = lex.matchedTerms,
                    matchedTitle = lex.matchedTitle,
                    matchSource = EvidenceMatchSource.Lexical,
                    passage = passage,
                    alreadyLinked = lex.evidence.id in linkedOutAndBack,
                    pinned = lex.evidence.id.path in pinnedPaths,
                )
            }
            (semanticConnections + extraLexical).take(10)
        } else {
            lexicalCandidates.mapNotNull { lex ->
                val path = validWikiPath(lex.evidence.id) ?: return@mapNotNull null
                val target = metadata.getValue(lex.evidence.id)
                Connection(
                    source = source,
                    title = target.title,
                    evidence = lex.evidence,
                    reference = path,
                    tags = lex.sharedTags,
                    keywords = lex.matchedTerms,
                    matchedTitle = lex.matchedTitle,
                    matchSource = EvidenceMatchSource.Lexical,
                    passage = passage,
                    alreadyLinked = lex.evidence.id in linkedOutAndBack,
                    pinned = lex.evidence.id.path in pinnedPaths,
                )
            }.take(10)
        }

        val orderedItems = items.partition { it.pinned }.let { (pinned, unpinned) -> pinned + unpinned }
        val partialIndex = semantic != null && vectors.indexState.first() != SemanticWorkState.Ready
        WeaveSuggestions(orderedItems, semanticStatus, scope.partial || partialIndex, hiddenCount)
    }

    private suspend fun readySemanticStatus(): WeaveSemanticStatus = when (vectors.indexState.first()) {
        SemanticWorkState.Queued, is SemanticWorkState.Running -> WeaveSemanticStatus.Indexing
        SemanticWorkState.Ready -> WeaveSemanticStatus.Ready
        else -> WeaveSemanticStatus.Partial
    }

    private suspend fun modelMissingStatus(): WeaveSemanticStatus = when (vectors.modelState.first()) {
        SemanticWorkState.Queued, is SemanticWorkState.Running -> WeaveSemanticStatus.ModelDownloading
        else -> WeaveSemanticStatus.ModelMissing
    }

    private suspend fun indexMissingStatus(): WeaveSemanticStatus {
        val modelState = vectors.modelState.first()
        return when {
            modelState == SemanticWorkState.Queued || modelState is SemanticWorkState.Running -> WeaveSemanticStatus.ModelDownloading
            modelState != SemanticWorkState.Ready -> WeaveSemanticStatus.ModelMissing
            else -> when (vectors.indexState.first()) {
                SemanticWorkState.Queued, is SemanticWorkState.Running -> WeaveSemanticStatus.Indexing
                else -> WeaveSemanticStatus.IndexMissing
            }
        }
    }

    private suspend fun currentSemanticStatus(): WeaveSemanticStatus {
        val modelState = vectors.modelState.first()
        return when {
            modelState == SemanticWorkState.Queued || modelState is SemanticWorkState.Running -> WeaveSemanticStatus.ModelDownloading
            modelState != SemanticWorkState.Ready -> WeaveSemanticStatus.ModelMissing
            else -> when (vectors.indexState.first()) {
                SemanticWorkState.Ready -> WeaveSemanticStatus.Ready
                SemanticWorkState.Queued, is SemanticWorkState.Running -> WeaveSemanticStatus.Indexing
                else -> WeaveSemanticStatus.IndexMissing
            }
        }
    }

    private data class LexicalCandidate(
        val score: Double,
        val evidence: Evidence,
        val sharedTags: Set<String>,
        val matchedTerms: List<String>,
        val matchedTitle: Boolean,
    )

    private suspend fun findLexicalConnections(
        scope: EvidenceScope,
        eligible: List<NoteListEntry>,
        currentTags: Set<String>,
        sourceTerms: List<String>,
        checkActive: () -> Unit,
    ): List<LexicalCandidate> {
        if (eligible.isEmpty()) return emptyList()
        if (sourceTerms.isEmpty() && currentTags.isEmpty()) return emptyList()
        val tests = sourceTerms.map(SearchSignature::prepare)
        val reader = notes.lexicalReader(scope.vault)
        val df = HashMap<String, Int>(sourceTerms.size)
        val loaded = ArrayList<Pair<NoteListEntry, EvidenceDocument?>>(eligible.size)
        for (entry in eligible) {
            checkActive()
            val id = entry.summary.id
            val hasSharedTag = currentTags.isNotEmpty() && currentTags.any { it in entry.summary.tags }
            val cached = scope.documents.get(id)
            val doc = cached ?: if (tests.isEmpty()) null else try {
                reader.read(id, tests, hasSharedTag || sourceTerms.any { entry.summary.title.contains(it, true) })
                    ?.let { stored -> EvidenceDocument(stored).also { scope.documents.put(id, it) } }
            } catch (_: IOException) {
                null
            }
            val metaText = entry.summary.title + " " + entry.summary.tags.joinToString(" ")
            val bodyText = doc?.source?.content?.let(::cleanForLexical) ?: ""
            for (term in sourceTerms) {
                if (metaText.contains(term, true) || bodyText.contains(term, true)) {
                    df[term] = (df[term] ?: 0) + 1
                }
            }
            if (doc != null || hasSharedTag) loaded += entry to doc
        }
        val n = eligible.size.toDouble()
        fun idf(term: String): Double = ln((n + 1.0) / ((df[term] ?: 0) + 1.0)) + 1.0

        val results = mutableListOf<LexicalCandidate>()
        for ((entry, doc) in loaded) {
            checkActive()
            val summary = entry.summary
            val sharedTags = currentTags.intersect(summary.tags)
            val titleMatches = sourceTerms.filter { summary.title.contains(it, true) }
            val titleScore = titleMatches.sumOf(::idf)
            val note = doc?.source ?: continue
            val revision = note.revision as? NoteRevision.Known ?: continue
            val chunks = doc.chunks ?: semanticChunks(note.content, checkActive).also { doc.chunks = it }
            val pool = chunks.filter { it.end - it.start >= 40 }.ifEmpty { chunks }
            var bestScore = 0.0
            var bestChunk: SemanticChunk? = null
            var bestText = ""
            var bestTerms: List<String> = emptyList()
            var bestIdfSum = 0.0
            for (chunk in pool) {
                val rawText = note.content.substring(chunk.start, chunk.end)
                val cleaned = cleanForLexical(rawText)
                val bodyMatched = sourceTerms.filter { cleaned.contains(it, true) }
                val combinedMatched = (bodyMatched + titleMatches).distinct()
                val idfSum = combinedMatched.sumOf(::idf)
                val passageScore = idfSum + sharedTags.size * 4.0 + titleScore * 2.0
                if (passageScore > bestScore) {
                    bestScore = passageScore
                    bestChunk = chunk
                    bestText = rawText
                    bestTerms = combinedMatched.sortedByDescending(::idf).take(3)
                    bestIdfSum = idfSum
                }
            }
            val chunk = bestChunk ?: continue
            val passesThreshold = sharedTags.isNotEmpty() ||
                (bestTerms.size >= 2 && bestIdfSum >= 4.5) ||
                titleScore >= 3.0
            if (!passesThreshold) continue
            val evidence = Evidence(
                id = summary.id,
                anchor = PassageAnchor(revision.sha256, chunk.start, chunk.end),
                text = bestText,
                section = chunk.section,
                similarity = null,
                source = EvidenceMatchSource.Lexical,
            )
            results += LexicalCandidate(bestScore, evidence, sharedTags, bestTerms, titleMatches.isNotEmpty())
        }
        return results.sortedByDescending { it.score }
    }

    private fun cleanForLexical(text: String): String {
        val bodyStart = frontmatterEnd(text)
        val body = if (bodyStart in 0..text.length) text.substring(bodyStart) else text
        return body
            .replace(CODE_FENCE, " ")
            .replace(URL_PATTERN, " ")
            .replace(WIKI_EMBED, " ")
            .replace(MARKDOWN_URL, " ")
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
        val STOPWORDS = setOf(
            "the", "and", "for", "from", "with", "this", "that", "what", "how",
            "are", "was", "were", "have", "has", "had", "will", "would", "could", "should",
            "can", "not", "but", "you", "your", "our", "their", "they", "them", "its",
            "into", "about", "when", "where", "which", "while", "then", "than", "also",
            "just", "only", "more", "most", "some", "such", "very", "each", "other",
            "use", "using", "used", "note", "notes",
            "在这", "什么", "如何", "这个", "的是", "一个", "可以", "使用", "问题",
            "因为", "如果", "没有", "需要", "通过", "进行", "或者", "以及", "已经",
            "可能", "应该", "这样", "这里", "那里", "我们", "他们", "它们", "自己",
            "时候", "方式", "方法", "内容", "信息", "相关", "对应", "当前", "默认", "支持",
        )
        val WORDS = Regex("""[\p{IsHan}]+|[\p{L}\p{N}_#./-]+""")
        val CODE_FENCE = Regex("""```[\s\S]*?```""")
        val URL_PATTERN = Regex("""https?://\S+""")
        val WIKI_EMBED = Regex("""!\[\[[^\]]+]]""")
        val MARKDOWN_URL = Regex("""\]\([^)]+\)""")
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
