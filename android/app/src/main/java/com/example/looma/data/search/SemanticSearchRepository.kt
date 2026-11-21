package com.example.looma.data.search

import android.content.Context
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.note.StoredMarkdown
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.removeMarkdownSuffix
import com.example.looma.domain.search.PassageAnchor
import com.example.looma.domain.search.SearchableNote
import com.example.looma.embedding.EmbeddingGemma
import java.io.IOException
import com.example.looma.data.index.IndexDelta
import com.example.looma.domain.model.NotePath
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SemanticSearchRepository internal constructor(
    private val context: Context,
    private val notes: NoteRepository,
    private val vaults: VaultRepository = VaultRepository(context),
) {
    private val directory = context.noBackupFilesDir.resolve("semantic-search")
    private val modelFile = EmbeddingModelFile(directory)
    private val cache = SemanticVectorCache(directory.resolve("vectors-v1-${EmbeddingModelFile.MODEL_SHA256}"))
    private val work = SemanticSearchWork(context, vaults, modelFile, cache)
    private val modelMutex = Mutex()
    private val indexMutex = Mutex()
    private var model: EmbeddingGemma? = null

    internal val supported: Boolean get() = work.supported
    internal val modelState by lazy { work.modelState }
    internal val indexState = work.indexState
    internal val hasIndex = work.hasIndex

    internal suspend fun enqueueModelDownload() = work.enqueueDownload()
    internal suspend fun cancelModelDownload() = work.cancelDownload()
    internal suspend fun enqueueIndex() = work.enqueueIndex()
    internal suspend fun cancelIndex() = work.cancelIndex()

    internal suspend fun filesChanged(vault: Vault, delta: IndexDelta) {
        if (!supported || !cache.enabled(vault.id)) return
        val paths = delta.removed + delta.upserts.map { it.id.path }
        for (path in paths.distinct()) cache.markDirty(NoteId(vault.id, path))
        if (paths.isNotEmpty()) work.enqueueUpdates(vault)
    }

    internal suspend fun resumeUpdates(vault: Vault, partial: Boolean) {
        if (!supported || !cache.enabled(vault.id)) return
        if (cache.markScanPartial(vault.id, partial)) work.cacheChanged()
        if (cache.hasPending(vault.id)) work.enqueueUpdates(vault)
    }

    internal suspend fun downloadModel(onProgress: suspend (Int) -> Unit) = withContext(Dispatchers.IO) {
        modelFile.download(onProgress)
    }

    internal suspend fun releaseModel() = withContext(Dispatchers.IO) {
        modelMutex.withLock {
            val closing = model
            model = null
            closing?.close()
        }
    }

    internal suspend fun rebuildIndex(
        target: SemanticVaultTarget,
        incremental: Boolean = false,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        coroutineScope {
            val watcher = launch {
                vaults.currentVaultFlow.first { !target.matches(it) }
                throw SemanticVaultChangedException()
            }
            try {
                indexMutex.withLock {
                    val vault = vaults.getCurrentVault()?.takeIf(target::matches) ?: throw SemanticVaultChangedException()
                    try {
                        if (incremental) updatePending(vault, onProgress) else rebuildIndex(vault, onProgress)
                    } finally {
                        withContext(NonCancellable) { releaseModel() }
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    private suspend fun rebuildIndex(vault: Vault, onProgress: suspend (Int, Int) -> Unit) {
        if (!modelFile.exists()) throw SemanticModelMissingException()
        val scan = notes.listNotes(vault)
        val candidates = scan.items.map { it.summary.id }
        val checkActive = coroutineContext::ensureActive
        cache.enable(vault.id)
        onProgress(0, candidates.size)
        for ((index, id) in candidates.withIndex()) {
            checkActive()
            updateNote(id, vault, checkActive)
            onProgress(index + 1, candidates.size)
        }
        if (!SemanticVaultTarget(vault).matches(vaults.getCurrentVault())) throw SemanticVaultChangedException()
        checkActive()
        if (scan.complete && scan.stalledRelocation == null) cache.prune(vault.id, candidates.toSet(), checkActive)
        cache.markScanPartial(vault.id, !scan.complete || scan.unreadableCount > 0 || scan.stalledRelocation != null)
        updatePending(vault, onProgress)
        cache.complete(vault.id, checkActive)
    }

    private suspend fun updatePending(vault: Vault, onProgress: suspend (Int, Int) -> Unit) {
        val pending = cache.pending(vault.id)
        val checkActive = coroutineContext::ensureActive
        for ((index, entry) in pending.entries.withIndex()) {
            checkActive()
            val path = NotePath(entry.value.substringAfter('\n'))
            updateNote(NoteId(vault.id, path), vault, checkActive)
            cache.acknowledge(entry.key, entry.value)
            onProgress(index + 1, pending.size)
        }
    }

    private suspend fun updateNote(id: NoteId, vault: Vault, checkActive: () -> Unit) {
        val note = read(id, vault)
        val revision = note?.revision as? NoteRevision.Known
        if (note == null || revision == null) {
            cache.forNote(id).delete()
            cache.markSkipped(id, java.io.File(vault.rootPath, id.path.relativePath).exists())
            return
        }
        val vectors = cache.forNote(id)
        if (!vectors.isCurrent(revision, note.content, checkActive)) {
            val chunks = semanticChunks(note.content, checkActive)
            val title = id.path.relativePath.substringAfterLast('/').removeMarkdownSuffix().cappedForModel(128)
            vectors.replace(revision, chunks, beforeReplace = {
                checkActive()
                if (read(id, vault)?.revision != revision) throw SemanticIndexIncompleteException()
            }) { chunk ->
                checkActive()
                val text = note.content.substring(chunk.start, chunk.end)
                val prompt = if (chunk.section.isEmpty()) text else "${chunk.section}\n$text"
                withModel { it.document(prompt, title) }.also { checkActive() }
            }
        }
        cache.markSkipped(id, false)
    }

    internal suspend fun search(
        query: String,
        candidates: List<SearchableNote>,
    ): List<SemanticSearchHit> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptyList()
        val vault = vaults.requireCurrentVault()
        if (!cache.hasSearchableIndex(vault.id)) throw SemanticIndexMissingException()
        val ids = candidates.asSequence().map { it.id }.filter { it.vaultId == vault.id }.distinct()
        val queryVector = withModel { it.query(query.cappedForModel(1024)) }
        check(queryVector.size == EmbeddingGemma.DIMENSIONS && queryVector.all { it.isFinite() })

        val ranked = mutableListOf<RankedHit>()
        val checkActive = coroutineContext::ensureActive
        for (id in ids) {
            checkActive()
            val passage = cache.forNote(id).rank(queryVector, checkActive) ?: continue
            ranked += RankedHit(id, passage)
        }
        val stale = mutableListOf<NoteId>()
        val results = buildList {
            for ((id, passage) in ranked.sortedWith(RANKING)) {
                if (size == MAX_RESULTS) break
                checkActive()
                val note = read(id, vault) ?: continue
                if (!passage.anchor.matches(note.revision, note.content)) {
                    stale += id
                    continue
                }
                add(SemanticSearchHit(
                    id = id,
                    anchor = passage.anchor,
                    section = passage.section,
                    excerpt = note.content.substring(passage.anchor.start, passage.anchor.end),
                ))
            }
        }
        if (!SemanticVaultTarget(vault).matches(vaults.getCurrentVault())) return@withContext emptyList()
        if (stale.isNotEmpty()) {
            stale.forEach(cache::markDirty)
            work.enqueueUpdates(vault)
        }
        results
    }

    private suspend fun <T> withModel(block: (EmbeddingGemma) -> T): T = modelMutex.withLock {
        val active = model ?: EmbeddingGemma(
            context,
            modelFile.open() ?: throw SemanticModelMissingException(),
        ).also { model = it }
        coroutineContext.ensureActive()
        block(active)
    }

    private suspend fun read(id: NoteId, vault: Vault): StoredMarkdown? = try {
        notes.readSearchSource(id, vault.rootPath)
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
internal class SemanticIndexMissingException : IOException()
internal class SemanticIndexIncompleteException : IOException()
internal class SemanticVaultChangedException : IOException()
