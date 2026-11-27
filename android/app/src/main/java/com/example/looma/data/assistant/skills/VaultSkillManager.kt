package com.example.looma.data.assistant.skills

import com.example.looma.data.assistant.tools.AgentSkillDetail
import com.example.looma.data.assistant.tools.AgentSkillSummary
import com.example.looma.data.assistant.tools.SkillCatalog
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.Vault
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class VaultSkillManager(
    private val currentVaultOrNull: suspend () -> Vault?,
) : SkillCatalog {
    constructor(vaults: VaultRepository) : this(currentVaultOrNull = { vaults.getCurrentVault() })

    private val mutex = Mutex()
    private val inMemoryDisabled = mutableSetOf<String>()
    private val mutableSkills = MutableStateFlow(
        buildSummaries(
            builtIns = BuiltInSkills.all,
            customs = emptyMap(),
            disabled = emptySet(),
        ),
    )

    override val skills: StateFlow<List<AgentSkillSummary>> = mutableSkills.asStateFlow()

    override suspend fun refresh() {
        mutex.withLock {
            refreshLocked()
        }
    }

    override suspend fun enabledSkills(): List<AgentSkillSummary> {
        return mutex.withLock {
            refreshLocked()
            mutableSkills.value.filter { it.enabled }
        }
    }

    override suspend fun loadSkill(name: String): AgentSkillDetail? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return mutex.withLock {
            val state = refreshLocked()
            if (trimmed in state.disabled) return@withLock null
            state.byName[trimmed]
        }
    }

    override suspend fun setEnabled(name: String, enabled: Boolean) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        mutex.withLock {
            val vault = currentVaultOrNull()
            val disabled = loadDisabledSet(vault).toMutableSet()
            if (enabled) {
                disabled.remove(trimmed)
            } else {
                disabled.add(trimmed)
            }
            saveDisabledSet(vault, disabled)
            val customs = loadCustomSkills(vault)
            mutableSkills.value = buildSummaries(BuiltInSkills.all, customs, disabled)
        }
    }

    private suspend fun refreshLocked(): LoadedSkills {
        val vault = currentVaultOrNull()
        val customs = loadCustomSkills(vault)
        val disabled = loadDisabledSet(vault)
        mutableSkills.value = buildSummaries(BuiltInSkills.all, customs, disabled)
        val byName = LinkedHashMap<String, AgentSkillDetail>()
        for (skill in BuiltInSkills.all) {
            byName[skill.name] = skill
        }
        for ((name, skill) in customs) {
            byName[name] = skill
        }
        return LoadedSkills(byName = byName, disabled = disabled)
    }

    private suspend fun loadCustomSkills(vault: Vault?): Map<String, AgentSkillDetail> {
        if (vault == null) return emptyMap()
        return withContext(Dispatchers.IO) {
            val skillsDir = File(vault.rootPath, SKILLS_RELATIVE_DIR)
            if (!skillsDir.isDirectory) return@withContext emptyMap()
            val dirs = skillsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') }?.sortedBy { it.name }.orEmpty()
            val result = LinkedHashMap<String, AgentSkillDetail>()
            for (dir in dirs) {
                val file = File(dir, SKILL_FILE_NAME)
                if (!file.isFile || !file.canRead()) continue
                val parsed = runCatching {
                    SkillParser.parse(file.readText(Charsets.UTF_8))
                }.getOrNull() ?: continue
                result[parsed.name] = parsed
            }
            result
        }
    }

    private suspend fun loadDisabledSet(vault: Vault?): Set<String> {
        if (vault == null) return inMemoryDisabled.toSet()
        return withContext(Dispatchers.IO) {
            val file = File(vault.rootPath, DISABLED_FILE_RELATIVE_PATH)
            if (!file.isFile || !file.canRead()) return@withContext emptySet()
            runCatching {
                file.readLines(Charsets.UTF_8)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }.getOrDefault(emptySet())
        }
    }

    private suspend fun saveDisabledSet(vault: Vault?, disabled: Set<String>) {
        inMemoryDisabled.clear()
        inMemoryDisabled.addAll(disabled)
        if (vault == null) return
        withContext(Dispatchers.IO) {
            val file = File(vault.rootPath, DISABLED_FILE_RELATIVE_PATH)
            if (disabled.isEmpty()) {
                if (file.exists()) {
                    file.delete()
                }
                return@withContext
            }
            file.parentFile?.mkdirs()
            file.writeText(disabled.sorted().joinToString("\n"), Charsets.UTF_8)
        }
    }

    private data class LoadedSkills(
        val byName: Map<String, AgentSkillDetail>,
        val disabled: Set<String>,
    )

    private companion object {
        const val SKILLS_RELATIVE_DIR = ".looma/skills"
        const val SKILL_FILE_NAME = "SKILL.md"
        const val DISABLED_FILE_RELATIVE_PATH = ".looma/skills-disabled.txt"

        fun buildSummaries(
            builtIns: List<AgentSkillDetail>,
            customs: Map<String, AgentSkillDetail>,
            disabled: Set<String>,
        ): List<AgentSkillSummary> {
            val ordered = LinkedHashMap<String, AgentSkillSummary>()
            for (skill in builtIns) {
                val custom = customs[skill.name]
                val effective = custom ?: skill
                ordered[skill.name] = AgentSkillSummary(
                    name = effective.name,
                    description = effective.description,
                    isBuiltIn = custom == null,
                    enabled = effective.name !in disabled,
                )
            }
            for ((name, skill) in customs) {
                if (name !in ordered) {
                    ordered[name] = AgentSkillSummary(
                        name = skill.name,
                        description = skill.description,
                        isBuiltIn = false,
                        enabled = skill.name !in disabled,
                    )
                }
            }
            return ordered.values.toList()
        }
    }
}
