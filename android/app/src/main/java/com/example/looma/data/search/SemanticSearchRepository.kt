package com.example.looma.data.search

import android.content.Context
import android.os.Process
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.note.StoredMarkdown
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.removeMarkdownSuffix
import com.example.looma.domain.search.PassageAnchor
import com.example.looma.domain.search.SearchableNote
import com.example.looma.embedding.EmbeddingGemma
import java.io.IOException
import java.util.PriorityQueue
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SemanticSearchRepository internal constructor(
    private val context: Context,
    private val notes: NoteRepository,
) {
    private val directory = context.noBackupFilesDir.resolve("semantic-search")
    private val modelFile = EmbeddingModelFile(directory)
    private val cache = SemanticVectorCache(directory.resolve("vectors-v1-${EmbeddingModelFile.MODEL_SHA256}"))
    private val mutex = Mutex()
    private var model: EmbeddingGemma? = null

    internal val supported: Boolean get() = Process.is64Bit()

    internal suspend fun hasModel(): Boolean = withContext(Dispatchers.IO) { modelFile.exists() }

    internal suspend fun downloadModel(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock { modelFile.download(onProgress) }
    }

    internal suspend fun releaseModel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val closing = model
            model = null
            closing?.close()
        }
    }

    internal suspend fun search(
        query: String,
        candidates: List<SearchableNote>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): List<SemanticSearchHit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (candidates.isEmpty()) return@withLock emptyList()
            val model = model ?: EmbeddingGemma(
                context,
                modelFile.open() ?: throw SemanticModelMissingException(),
            ).also { model = it }
            val queryVector = model.query(query.cappedForModel(1024))
            check(queryVector.size == EmbeddingGemma.DIMENSIONS && queryVector.all { it.isFinite() })
            ensureActive()

            val vaultId = candidates.first().id.vaultId
            val ids = candidates.mapTo(HashSet()) { it.id }
            cache.prune(vaultId, ids)
            val top = PriorityQueue(RANKING.reversed())
            val checkActive = coroutineContext::ensureActive
            for ((index, candidate) in candidates.withIndex()) {
                checkActive()
                val note = read(candidate.id) ?: continue
                val revision = note.revision as? NoteRevision.Known ?: continue
                val noteCache = cache.forNote(candidate.id)
                var ranking = noteCache.best(revision, note.content, queryVector, checkActive)
                if (ranking == null) {
                    onProgress(index, candidates.size)
                    val chunks = semanticChunks(note.content, checkActive)
                    val title = candidate.id.path.relativePath.substringAfterLast('/').removeMarkdownSuffix().cappedForModel(128)
                    noteCache.replace(revision, chunks) { chunk ->
                        checkActive()
                        val text = note.content.substring(chunk.start, chunk.end)
                        val prompt = if (chunk.section.isEmpty()) text else "${chunk.section}\n$text"
                        model.document(prompt, title).also { checkActive() }
                    }
                    ranking = noteCache.best(revision, note.content, queryVector, checkActive)
                }
                val passage = ranking?.best ?: continue
                top.add(RankedHit(candidate.id, passage))
                if (top.size > MAX_RESULTS * 2) top.poll()
            }

            buildList {
                for ((id, passage) in top.sortedWith(RANKING)) {
                    if (size == MAX_RESULTS) break
                    checkActive()
                    val note = read(id) ?: continue
                    if (!passage.anchor.matches(note.revision, note.content)) continue
                    add(SemanticSearchHit(
                        id = id,
                        anchor = passage.anchor,
                        section = passage.section,
                        excerpt = note.content.substring(passage.anchor.start, passage.anchor.end),
                    ))
                }
            }
        }
    }

    private suspend fun read(id: NoteId): StoredMarkdown? = try {
        notes.readSearchSource(id)
    } catch (_: IOException) {
        null
    }

    companion object {
        private const val MAX_RESULTS = 20
        private val RANKING = compareByDescending<RankedHit> { it.passage.score }.thenBy { it.id.path.relativePath }
    }

    private data class RankedHit(val id: NoteId, val passage: ScoredPassage)
}

internal data class SemanticSearchHit(
    val id: NoteId,
    val anchor: PassageAnchor,
    val section: String,
    val excerpt: String,
)

internal class SemanticModelMissingException : IOException()
