package com.example.looma.feature.ask

import android.net.Uri
import com.example.looma.data.note.NoteRepository
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.wiki.LocalNoteLink
import com.example.looma.domain.wiki.wikiPathOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.looma.data.assistant.Evidence
import com.example.looma.data.assistant.LocalGenerator
import com.example.looma.data.assistant.VaultAgent
import com.example.looma.data.assistant.VaultAnswer
import com.example.looma.data.assistant.VaultEvidence
import com.example.looma.domain.model.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class AskIssue { Import, Run, Timeout, Changed, Save }
internal sealed interface AskPhase {
    data object Idle : AskPhase
    data class Running(val step: String = "") : AskPhase
    data object Saving : AskPhase
}

internal data class AskState(
    val hasModel: Boolean,
    val phase: AskPhase = AskPhase.Idle,
    val answer: VaultAnswer? = null,
    val question: String = "",
    val saved: NoteId? = null,
    val issue: AskIssue? = null,
) {
    val busy: Boolean get() = phase != AskPhase.Idle
    val saving: Boolean get() = phase is AskPhase.Saving
}

internal class AskViewModel(
    private val vault: Vault,
    private val agent: VaultAgent,
    private val evidence: VaultEvidence,
    private val generator: LocalGenerator,
    private val notes: NoteRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(AskState(generator.installed))
    val state = mutable.asStateFlow()
    private var task: Job? = null

    fun install(uri: Uri) = start(AskPhase.Running(), AskIssue.Import) {
        generator.install(uri)
        mutable.update { it.copy(hasModel = generator.installed) }
    }

    fun ask(question: String, investigate: Boolean) = start(AskPhase.Running(), AskIssue.Run) {
        mutable.update { it.copy(answer = null, question = question, saved = null) }
        val answer = agent.answer(vault, question.trim(), investigate) { step ->
            mutable.update { it.copy(phase = AskPhase.Running(step)) }
        }
        mutable.update { it.copy(answer = answer) }
    }

    fun open(hit: Evidence, navigate: (Evidence) -> Unit) = start(AskPhase.Running(), AskIssue.Changed) {
        val current = evidence.resolve(vault, hit)
        if (current == null) mutable.update { it.copy(issue = AskIssue.Changed) } else navigate(current)
    }

    fun save(title: String, markdown: String) = start(AskPhase.Saving, AskIssue.Save) {
        val current = mutable.value
        if (current.saved != null || title.isBlank()) return@start
        val answer = current.answer ?: return@start
        val cited = answer.claims.flatMap { it.citations }.distinct()
        if (cited.isEmpty() || cited.any { evidence.resolve(vault, answer.sources[it - 1]) == null }) {
            mutable.update { it.copy(issue = AskIssue.Changed) }
            return@start
        }
        val note = notes.createWithContent(vault, title, markdown)
        mutable.update { it.copy(saved = note.id) }
    }

    fun cancel() { if (!mutable.value.saving) task?.cancel() }

    private fun start(phase: AskPhase, failure: AskIssue, action: suspend () -> Unit) {
        if (task?.isActive == true) return
        task = viewModelScope.launch {
            mutable.update { it.copy(phase = phase, issue = null) }
            try {
                action()
            } catch (_: TimeoutCancellationException) {
                mutable.update { it.copy(issue = AskIssue.Timeout) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                mutable.update { it.copy(issue = failure) }
            } catch (_: LinkageError) {
                mutable.update { it.copy(issue = failure) }
            } finally {
                mutable.update { it.copy(phase = AskPhase.Idle) }
            }
        }
    }
}

internal fun VaultAnswer.asMarkdown(question: String, sourcesTitle: String): String = buildString {
    appendLine("# " + escapeMarkdown(question))
    for (claim in claims) {
        appendLine()
        append(escapeMarkdown(claim.text))
        appendLine(claim.citations.joinToString(" ", prefix = " ") { "[$it]" })
    }
    appendLine()
    appendLine("## $sourcesTitle")
    claims.flatMap { it.citations }.distinct().sorted().forEach { number ->
        val source = sources[number - 1]
        val path = wikiPathOf(source.id.path)
        val link = if (path.none { it in "[]#|\r\n" }) "[[$path]]" else {
            "[" + escapeMarkdown(path) + "](<" + LocalNoteLink.fromRoot(source.id.path) + ">)"
        }
        appendLine()
        appendLine("[$number] $link")
        source.text.lineSequence().forEach { appendLine("> " + escapeMarkdown(it)) }
    }
}

private fun escapeMarkdown(text: String): String = buildString {
    text.forEach { ch ->
        if (ch in "\\`*_{}[]<>()#+-.!|~>") append('\\')
        append(ch)
    }
}
