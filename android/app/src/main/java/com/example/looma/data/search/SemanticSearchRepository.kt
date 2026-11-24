package com.example.looma.data.search

import android.content.Context
import com.example.looma.data.index.IndexDelta
import com.example.looma.data.inference.LocalModelSession
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.note.NoteTooLargeException
import com.example.looma.data.note.StoredMarkdown
import com.example.looma.data.note.VaultFileException
import com.example.looma.data.note.VaultFileFailure
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.removeMarkdownSuffix
import com.example.looma.domain.search.PassageAnchor
import com.example.looma.domain.search.SearchableNote
import com.example.looma.embedding.EmbeddingBackend
import com.example.looma.embedding.EmbeddingGemma
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SemanticSearchRepository internal constructor(
    private val context: Context,
    private val notes: NoteRepository,
    private val vaults: VaultRepository = VaultRepository(context),
    private val settings: SettingsRepository = SettingsRepository(context),
    private val session: LocalModelSession = LocalModelSession(),
) {
    private val directory = context.noBackupFilesDir.resolve("semantic-search")
    @Volatile
    private var currentBackend = EmbeddingBackend.CPU
    private val modelFile = EmbeddingModelFile(directory) {
        EmbeddingModelCatalog.resolve(currentBackend)
    }
    private val cache = SemanticVectorCache(directory.resolve("vectors-v1-${EmbeddingModelCatalog.VECTOR_SPACE_ID}"))
    private val store = PassageVectorStore(context, directory.resolve("passages-" + EmbeddingModelCatalog.VECTOR_SPACE_ID + ".db"))
    private val work = SemanticSearchWork(context, vaults, modelFile, cache, store)
    private val indexMutex = Mutex()
    private var model: EmbeddingGemma? = null

    internal val supported: Boolean get() = work.supported
    internal val npuSupported: Boolean get() = EmbeddingModelCatalog.supportsNpu()
    internal val modelState by lazy { work.modelState }
    internal val indexState = work.indexState
    internal val hasIndex = work.hasIndex
    internal val embeddingBackend: Flow<EmbeddingBackend> = settings.settings
        .map { EmbeddingModelCatalog.effectiveBackend(it.embeddingBackend).also { effective -> currentBackend = effective } }
        .distinctUntilChanged()
    internal val modelSizeBytes: Flow<Long> = embeddingBackend
        .map { EmbeddingModelCatalog.resolve(it).bytes }
        .distinctUntilChanged()

    internal suspend fun setEmbeddingBackend(backend: EmbeddingBackend) {
        val target = EmbeddingModelCatalog.effectiveBackend(backend)
        val current = EmbeddingModelCatalog.effectiveBackend(settings.settings.first().embeddingBackend)
        currentBackend = target
        if (current != target) {
            work.cancelDownload()
            releaseModel()
            settings.setEmbeddingBackend(target)
            work.notifyModelChanged()
        }
    }

    internal suspend fun enqueueModelDownload() {
        syncBackend()
        work.enqueueDownload()
    }
    internal suspend fun cancelModelDownload() = work.cancelDownload()
    internal suspend fun deleteModel() {
        work.cancelDownload()
        work.cancelIndex()
        releaseModel()
        withContext(Dispatchers.IO) {
            modelFile.delete()
        }
        work.notifyModelChanged()
    }
    internal suspend fun enqueueIndex() {
        syncBackend()
        work.enqueueIndex()
    }
    internal suspend fun cancelIndex() = work.cancelIndex()

    internal fun recordChanges(delta: IndexDelta) {
        if (!supported || !cache.enabled(delta.vaultId)) return
        store.mark(delta.vaultId, (delta.removed + delta.upserts.map { it.id.path }).distinct())
    }

    internal suspend fun filesChanged(vault: Vault, delta: IndexDelta) {
        if (supported && (delta.removed.isNotEmpty() || delta.upserts.isNotEmpty()) && store.hasPending(vault.id)) {
            work.enqueueUpdates(vault)
        }
    }

    internal suspend fun resumeUpdates(vault: Vault, partial: Boolean) {
        if (!supported || !cache.enabled(vault.id)) return
        if (cache.markScanPartial(vault.id, partial)) work.cacheChanged()
        val needsMigration = !store.initialized(vault.id) && cache.hasSearchableIndex(vault.id)
        if (needsMigration || store.hasPending(vault.id) || cache.hasPending(vault.id)) work.enqueueUpdates(vault)
    }

    internal suspend fun downloadModel(onProgress: suspend (Int) -> Unit) = withContext(Dispatchers.IO) {
        val backend = syncBackend()
        modelFile.download(onProgress, EmbeddingModelCatalog.resolve(backend))
    }

    internal suspend fun releaseModel() = withContext(Dispatchers.IO) {
        session.release(LocalModelSession.Model.Embedding)
    }

    private fun closeModel() {
        val closing = model
        model = null
        closing?.close()
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
                        if (incremental && store.initialized(vault.id)) updatePending(vault, onProgress)
                        else rebuildIndex(vault, onProgress)
                    } finally {
                        withContext(NonCancellable) { session.release(LocalModelSession.Model.Embedding) }
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
    }

    private suspend fun rebuildIndex(vault: Vault, onProgress: suspend (Int, Int) -> Unit) {
        val backend = syncBackend()
        if (!modelFile.exists(EmbeddingModelCatalog.resolve(backend))) throw SemanticModelMissingException()
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
        if (scan.complete && scan.stalledRelocation == null) {
            val keep = candidates.toSet()
            cache.prune(vault.id, keep, checkActive)
            store.prune(vault.id, keep, checkActive)
        }
        cache.markScanPartial(vault.id, !scan.complete || scan.unreadableCount > 0 || scan.stalledRelocation != null)
        updatePending(vault, onProgress)
        cache.complete(vault.id, checkActive)
        store.finish(vault.id)
    }

    private suspend fun updatePending(vault: Vault, onProgress: suspend (Int, Int) -> Unit) {
        val pending = cache.pending(vault.id)
        val checkActive = coroutineContext::ensureActive
        val dirty = store.pending(vault.id)
        for ((index, entry) in dirty.entries.withIndex()) {
            checkActive()
            val id = NoteId(vault.id, entry.key)
            updateNote(id, vault, checkActive)
            store.acknowledge(id, entry.value)
            onProgress(index + 1, dirty.size + pending.size)
        }
        for ((index, entry) in pending.entries.withIndex()) {
            checkActive()
            val path = NotePath(entry.value.substringAfter('\n'))
            updateNote(NoteId(vault.id, path), vault, checkActive)
            cache.acknowledge(entry.key, entry.value)
            onProgress(dirty.size + index + 1, pending.size + dirty.size)
        }
    }

    private suspend fun updateNote(id: NoteId, vault: Vault, checkActive: () -> Unit) {
        val note = readForIndex(id, vault)
        val revision = note?.revision as? NoteRevision.Known
        if (note == null || revision == null) {
            store.remove(id)
            cache.forNote(id).delete()
            cache.markSkipped(id, java.io.File(vault.rootPath, id.path.relativePath).exists())
            return
        }
        val vectors = cache.forNote(id)
        if (!store.current(id, revision)) {
            val chunks = semanticChunks(note.content, checkActive)
            val title = id.path.relativePath.substringAfterLast('/').removeMarkdownSuffix().cappedForModel(128)
            fun prompt(chunk: SemanticChunk): String {
                val text = note.content.substring(chunk.start, chunk.end)
                return if (chunk.section.isEmpty()) text else "${chunk.section}\n$text"
            }
            val hasher = java.security.MessageDigest.getInstance("SHA-256")
            vectors.reusable(checkActive).use { legacy ->
                store.replace(id, revision, chunks, key = { chunk ->
                    val input = EmbeddingGemma.documentInput(prompt(chunk), title)
                    hasher.digest(input.toByteArray(Charsets.UTF_8)).toHexString()
                }, vector = { chunk, _ ->
                    legacy?.get(chunk.let {
                        val input = EmbeddingGemma.documentInput(prompt(it), title)
                        hasher.digest(input.toByteArray(Charsets.UTF_8)).toHexString()
                    }) ?: withModel { it.document(prompt(chunk), title) }
                }, checkActive = checkActive, verify = {
                    checkActive()
                    if (readForIndex(id, vault)?.revision != revision) throw SemanticIndexIncompleteException()
                })
            }
        }
        vectors.delete()
        cache.markSkipped(id, false)
    }

    internal suspend fun search(
        query: String,
        candidates: List<SearchableNote>,
        relatedDocumentTitle: String? = null,
    ): List<SemanticSearchHit> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptyList()
        val vault = vaults.requireCurrentVault()
        if (!store.hasDocuments(vault.id) && !cache.hasSearchableIndex(vault.id)) throw SemanticIndexMissingException()
        val ids = candidates.asSequence().map { it.id }.filter { it.vaultId == vault.id }.toSet()
        val queryVector = withModel(releaseWhenIdle = true) { embedder ->
            val text = query.cappedForModel(1024)
            if (relatedDocumentTitle == null) embedder.query(text)
            else embedder.document(text, relatedDocumentTitle.cappedForModel(128))
        }
        check(queryVector.size == EmbeddingGemma.DIMENSIONS && queryVector.all { it.isFinite() })

        val ranked = mutableListOf<RankedHit>()
        val checkActive = coroutineContext::ensureActive
        val current = store.rank(vault.id, ids, queryVector, checkActive)
        current.forEach { (id, passage) -> ranked += RankedHit(id, passage) }
        val stale = mutableListOf<NoteId>()
        if (store.initialized(vault.id)) {
            stale += store.missing(vault.id, ids - current.keys, checkActive).filterNot(cache::isSkipped)
        } else {
            for (id in ids) {
                checkActive()
                if (id in current) continue
                cache.forNote(id).rank(queryVector, checkActive)?.let { ranked += RankedHit(id, it) }
            }
        }
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
                    similarity = passage.score,
                    excerpt = note.content.substring(passage.anchor.start, passage.anchor.end),
                ))
            }
        }
        if (!SemanticVaultTarget(vault).matches(vaults.getCurrentVault())) return@withContext emptyList()
        if (stale.isNotEmpty()) {
            store.mark(vault.id, stale.map { it.path }, replaceTicket = false)
            work.enqueueUpdates(vault)
        }
        results
    }

    private suspend fun <T> withModel(releaseWhenIdle: Boolean = false, block: (EmbeddingGemma) -> T): T =
        session.use(LocalModelSession.Model.Embedding, if (releaseWhenIdle) 15_000 else null, ::closeModel) {
            val active = model ?: openEmbeddingModel().also { model = it }
            coroutineContext.ensureActive()
            block(active)
        }

    private suspend fun readForIndex(id: NoteId, vault: Vault): StoredMarkdown? = try {
        notes.readSearchSource(id, vault.rootPath)
    } catch (_: NoteTooLargeException) {
        null
    } catch (error: VaultFileException) {
        if (error.failure != VaultFileFailure.NOT_FOUND) throw error
        if (!SemanticVaultTarget(vault).matches(vaults.getCurrentVault())) throw SemanticVaultChangedException()
        null
    }

    private suspend fun syncBackend(): EmbeddingBackend =
        EmbeddingModelCatalog.effectiveBackend(settings.settings.first().embeddingBackend).also {
            currentBackend = it
        }

    private suspend fun openEmbeddingModel(): EmbeddingGemma {
        val backend = syncBackend()
        if (backend == EmbeddingBackend.NPU) {
            val npuFile = modelFile.open(EmbeddingModelCatalog.resolve(EmbeddingBackend.NPU))
            if (npuFile != null) {
                try {
                    return EmbeddingGemma(context, npuFile, EmbeddingBackend.NPU)
                } catch (error: Exception) {
                    val cpuFile = modelFile.open(EmbeddingModelCatalog.CPU_MODEL) ?: throw error
                    return EmbeddingGemma(context, cpuFile, EmbeddingBackend.CPU)
                }
            }
        }
        val cpuFile = modelFile.open(EmbeddingModelCatalog.CPU_MODEL) ?: throw SemanticModelMissingException()
        return EmbeddingGemma(context, cpuFile, EmbeddingBackend.CPU)
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
    val similarity: Double = 0.0,
)

internal class SemanticModelMissingException : IOException()
internal class SemanticIndexMissingException : IOException()
internal class SemanticIndexIncompleteException : IOException()
internal class SemanticVaultChangedException : IOException()
