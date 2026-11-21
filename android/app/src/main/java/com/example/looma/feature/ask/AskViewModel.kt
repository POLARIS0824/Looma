package com.example.looma.feature.ask

import android.net.Uri
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

internal enum class AskIssue { Import, Run, Timeout, Changed }
internal data class AskState(
    val hasModel: Boolean,
    val busy: Boolean = false,
    val step: String = "",
    val answer: VaultAnswer? = null,
    val issue: AskIssue? = null,
)

internal class AskViewModel(
    private val vault: Vault,
    private val agent: VaultAgent,
    private val evidence: VaultEvidence,
    private val generator: LocalGenerator,
) : ViewModel() {
    private val mutable = MutableStateFlow(AskState(generator.installed))
    val state = mutable.asStateFlow()
    private var task: Job? = null

    fun install(uri: Uri) = start(AskIssue.Import) {
        generator.install(uri)
        mutable.update { it.copy(hasModel = generator.installed) }
    }

    fun ask(question: String, investigate: Boolean) = start(AskIssue.Run) {
        mutable.update { it.copy(answer = null) }
        val answer = agent.answer(vault, question.trim(), investigate) { step -> mutable.update { it.copy(step = step) } }
        mutable.update { it.copy(answer = answer) }
    }

    fun open(hit: Evidence, navigate: (Evidence) -> Unit) = start(AskIssue.Changed) {
        val current = evidence.resolve(vault, hit)
        if (current == null) mutable.update { it.copy(issue = AskIssue.Changed) } else navigate(current)
    }

    fun cancel() { task?.cancel() }

    private fun start(failure: AskIssue, action: suspend () -> Unit) {
        if (task?.isActive == true) return
        task = viewModelScope.launch {
            mutable.update { it.copy(busy = true, issue = null, step = "") }
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
                mutable.update { it.copy(busy = false, step = "") }
            }
        }
    }
}
