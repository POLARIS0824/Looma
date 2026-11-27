package com.example.looma.data.assistant.tools

import com.example.looma.data.note.NoteListResult
import com.example.looma.data.note.NoteRepository
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.Note
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.Vault
import com.example.looma.domain.search.NoteSearchMatch
import com.example.looma.domain.search.SearchableNote
import com.example.looma.domain.wiki.WikiContext
import kotlinx.coroutines.CancellationException

internal interface VaultToolGateway {
    suspend fun requireCurrentVault(): Vault
    suspend fun readNote(id: NoteId): Note
    suspend fun evidenceNotes(vault: Vault): NoteListResult
    suspend fun search(query: String, limit: Int): List<NoteSearchMatch>
    suspend fun wikiContext(id: NoteId): WikiContext
    suspend fun createWithContent(vault: Vault, title: String, content: String): Note
}

internal class DefaultVaultToolGateway(
    private val vaults: VaultRepository,
    private val notes: NoteRepository,
) : VaultToolGateway {
    override suspend fun requireCurrentVault(): Vault = vaults.requireCurrentVault()

    override suspend fun readNote(id: NoteId): Note = notes.readNote(id)

    override suspend fun evidenceNotes(vault: Vault): NoteListResult = notes.evidenceNotes(vault)

    override suspend fun search(query: String, limit: Int): List<NoteSearchMatch> {
        val vault = vaults.requireCurrentVault()
        val scan = notes.evidenceNotes(vault)
        if (scan.items.isEmpty()) return emptyList()
        val candidates = scan.items.map {
            SearchableNote(it.summary.id, it.summary.title, it.summary.tags)
        }
        return notes.search(candidates, query).take(limit)
    }

    override suspend fun wikiContext(id: NoteId): WikiContext = notes.wikiContext(id)

    override suspend fun createWithContent(vault: Vault, title: String, content: String): Note =
        notes.createWithContent(vault, title, content)
}

internal suspend inline fun executeVaultTool(
    gateway: VaultToolGateway,
    fallbackError: String,
    block: (Vault) -> ToolResult,
): ToolResult {
    val vault = try {
        gateway.requireCurrentVault()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return ToolResult("No vault is currently open.", isError = true)
    }
    return try {
        block(vault)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult(e.message?.takeIf { it.isNotBlank() } ?: fallbackError, isError = true)
    }
}

internal fun validateVisibleNotePath(path: String): String? {
    if (path.isEmpty()) {
        return "Path must not be blank."
    }
    if (path.startsWith('/') || '\\' in path || path.split('/').any { it.isBlank() || it == "." || it == ".." || it.startsWith('.') }) {
        return "Access to hidden or invalid path '$path' is not allowed."
    }
    return null
}
