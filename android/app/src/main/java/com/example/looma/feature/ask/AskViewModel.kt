package com.example.looma.feature.ask

import com.example.looma.data.note.NoteRepository
import com.example.looma.data.settings.SettingsRepository
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.wiki.LocalNoteLink
import com.example.looma.domain.wiki.wikiPathOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.AnswerModels
import com.example.looma.data.assistant.Evidence
import com.example.looma.data.assistant.GeneratorAvailability
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.ModelChoice
import com.example.looma.data.assistant.ThinkingEffort
import com.example.looma.data.assistant.VaultAgent
import com.example.looma.data.assistant.VaultAnswer
import com.example.looma.data.assistant.VaultEvidence
import com.example.looma.domain.model.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class AskIssue { Run, Timeout, Changed, Save }
internal sealed interface AskPhase {
    data object Idle : AskPhase
    data class Running(val step: String = "") : AskPhase
    data object Saving : AskPhase
}

internal data class AskState(
    val vaultName: String,
    val models: List<AnswerModel>,
    val choice: ModelChoice?,
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
    private val models: AnswerModels,
    private val notes: NoteRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(models.installed().let { installed ->
        AskState(vault.name, installed, restoredChoice(installed, null, null, null))
    })
    val state = mutable.asStateFlow()
    private var task: Job? = null
    private var picked = false

    init {
        viewModelScope.launch {
            val stored = settings.settings.first()
            mutable.update { current ->
                if (picked) current else current.copy(choice = restoredChoice(current.models, stored.askModelId, stored.askEffort, stored.askRoute))
            }
            models.models.collect { installed ->
                mutable.update { current ->
                    val next = if (picked) {
                        current.choice?.let { prev ->
                            installed.firstOrNull { it.id == prev.model.id }?.let { updated ->
                                prev.copy(model = updated)
                            }
                        } ?: installed.firstOrNull()?.let { ModelChoice(it, it.defaultEffort, it.defaultRoute) }
                    } else {
                        restoredChoice(installed, stored.askModelId, stored.askEffort, stored.askRoute)
                    }
                    current.copy(models = installed, choice = next)
                }
            }
        }
    }

    fun refreshModels() {
        models.refresh()
        val installed = models.installed()
        mutable.update { current ->
            if (current.models == installed) return@update current
            val kept = current.choice?.let { prev ->
                installed.firstOrNull { it.id == prev.model.id }?.let { updated ->
                    prev.copy(model = updated)
                }
            }
            current.copy(
                models = installed,
                choice = kept ?: installed.firstOrNull()?.let { ModelChoice(it, it.defaultEffort, it.defaultRoute) },
            )
        }
        if (mutable.value.choice?.model?.availability is GeneratorAvailability.Downloadable) {
            prepareSelectedModel()
        }
    }

    fun select(model: AnswerModel) = pick { current ->
        if (model !in current.models) return@pick current
        val effort = current.choice?.effort?.takeIf { it in model.efforts } ?: model.defaultEffort
        current.copy(choice = ModelChoice(model, effort, model.defaultRoute))
    }

    fun setEffort(effort: ThinkingEffort) = pick { current ->
        val choice = current.choice ?: return@pick current
        if (effort !in choice.model.efforts) current else current.copy(choice = choice.copy(effort = effort))
    }

    fun setRoute(route: InferenceRoute) = pick { current ->
        val choice = current.choice ?: return@pick current
        if (route !in choice.model.supportedRoutes) current else current.copy(choice = choice.copy(route = route))
    }

    fun prepareSelectedModel() {
        val model = mutable.value.choice?.model ?: return
        viewModelScope.launch { models.prepare(model) }
    }

    private fun pick(change: (AskState) -> AskState) {
        val before = mutable.value.choice
        mutable.update(change)
        val choice = mutable.value.choice ?: return
        if (choice == before) return
        picked = true
        viewModelScope.launch { settings.setAskModel(choice) }
    }

    fun ask(question: String) = start(AskPhase.Running(), AskIssue.Run) {
        mutable.update { it.copy(answer = null, question = question, saved = null) }
        val choice = mutable.value.choice
        val answer = agent.answer(
            vault,
            question.trim(),
            choice?.let { models.generator(it.model) },
            choice?.effort ?: ThinkingEffort.Off,
            choice?.route,
        ) { step ->
            mutable.update { it.copy(phase = AskPhase.Running(step)) }
        }
        mutable.update { it.copy(answer = answer) }
    }

    fun retry() {
        val question = mutable.value.question
        if (question.isNotBlank()) ask(question)
    }

    fun newChat() = mutable.update {
        if (it.busy) it else it.copy(question = "", answer = null, saved = null, issue = null)
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

private fun restoredChoice(
    installed: List<AnswerModel>,
    modelId: String?,
    effortName: String?,
    routeName: String?,
): ModelChoice? {
    val model = installed.firstOrNull { it.id == modelId }
        ?: return installed.firstOrNull()?.let { ModelChoice(it, it.defaultEffort, it.defaultRoute) }
    val storedEffort = ThinkingEffort.entries.firstOrNull { it.name == effortName }
    val effort = storedEffort?.let { s ->
        model.efforts.lastOrNull { it <= s } ?: model.efforts.firstOrNull()
    } ?: model.defaultEffort
    val storedRoute = InferenceRoute.entries.firstOrNull { it.name == routeName }
    val route = storedRoute?.takeIf { it in model.supportedRoutes } ?: model.defaultRoute
    return ModelChoice(model, effort, route)
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
