package com.tubepilot.skills

import android.content.Context
import com.tubepilot.core.domain.AuditLog
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.domain.SkillStateRepository
import com.tubepilot.core.error.AgentsPausedException
import com.tubepilot.core.error.SkillException
import com.tubepilot.core.error.TubePilotException
import com.tubepilot.core.model.AgentRole
import com.tubepilot.core.model.AuditLevel
import com.tubepilot.core.model.ImportedSkillRecord
import com.tubepilot.core.model.SkillCallRecord
import com.tubepilot.core.util.JsonText
import com.tubepilot.core.util.TimeSource
import com.tubepilot.core.util.TubeJson
import com.tubepilot.llm.ToolSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Source of the skills that ship with the app. */
interface BuiltInSkillSource {
    suspend fun load(): List<Skill>
}

/** Loads assets/skills/<id>/skill.json and its prompt file. */
@Singleton
class AssetSkillSource @Inject constructor(@ApplicationContext private val context: Context) : BuiltInSkillSource {
    override suspend fun load(): List<Skill> = withContext(Dispatchers.IO) {
        val assets = context.assets
        assets.list("skills").orEmpty().sorted().mapNotNull { dir ->
            val manifestJson = runCatching { assets.open("skills/$dir/skill.json").bufferedReader().use { it.readText() } }.getOrNull()
                ?: return@mapNotNull null
            val manifest = SkillManifestParser.parseManifest(manifestJson)
            val prompt = runCatching { assets.open("skills/$dir/${manifest.promptFile}").bufferedReader().use { it.readText() } }.getOrDefault("")
            Skill(manifest, prompt, builtIn = true)
        }
    }
}

data class SkillOutcome(val output: JsonObject, val durationMs: Long)

/**
 * Loads built-in and imported skills, tracks per-agent enablement, exposes enabled skills as LLM
 * tools and executes skill calls with permission checks and an audit record for every call.
 */
@Singleton
class SkillRegistry @Inject constructor(
    private val builtIns: BuiltInSkillSource,
    private val executors: Map<String, @JvmSuppressWildcards SkillExecutor>,
    private val state: SkillStateRepository,
    private val settings: SettingsRepository,
    private val audit: AuditLog,
    private val runtime: SkillRuntime,
    private val time: TimeSource,
) {
    private val lock = Mutex()
    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()
    private var loaded = false

    suspend fun ensureLoaded() {
        if (!loaded) reload()
    }

    suspend fun reload() = lock.withLock {
        val builtIn = builtIns.load()
        val builtInIds = builtIn.map { it.id }.toSet()
        val imported = state.imported().mapNotNull { record ->
            runCatching {
                val manifest = SkillManifestParser.parseManifest(record.manifestJson)
                Skill(manifest, record.promptTemplate, builtIn = false).also { SkillManifestParser.validateImport(it, builtInIds) }
            }.onFailure {
                audit.record("SkillRegistry", "skill.invalid", "Imported skill ${record.id} failed validation: ${it.message}", level = AuditLevel.WARNING)
            }.getOrNull()
        }
        _skills.value = builtIn + imported
        loaded = true
    }

    fun get(id: String): Skill? = _skills.value.firstOrNull { it.id == id }

    private fun isEnabled(skill: Skill, role: AgentRole, overrides: Map<Pair<String, AgentRole>, Boolean>): Boolean =
        role in skill.manifest.agents && (overrides[skill.id to role] ?: skill.manifest.defaultEnabled)

    suspend fun enabledFor(role: AgentRole): List<Skill> {
        ensureLoaded()
        val overrides = state.overrides()
        return _skills.value.filter { isEnabled(it, role, overrides) }
    }

    /** Every (skill, agent) pair a skill declares, with its effective state, for the Skills Store. */
    fun observeAssignments(): Flow<List<SkillAssignment>> = combine(skills, state.observeOverrides()) { list, overrides ->
        list.flatMap { skill -> skill.manifest.agents.map { role -> SkillAssignment(skill, role, isEnabled(skill, role, overrides)) } }
    }

    suspend fun setEnabled(skillId: String, role: AgentRole, enabled: Boolean) {
        val skill = get(skillId) ?: return
        require(role in skill.manifest.agents) { "${skill.manifest.name} cannot be used by ${role.displayName}" }
        state.setEnabled(skillId, role, enabled)
        audit.record("SkillRegistry", "skill.toggle", "${skill.id} ${if (enabled) "enabled" else "disabled"} for ${role.displayName}", level = AuditLevel.INFO)
    }

    /** LLM tool definitions for the skills enabled for [role]. */
    suspend fun toolsFor(role: AgentRole, exclude: Set<String> = emptySet()): List<ToolSpec> =
        enabledFor(role).filter { it.id !in exclude }.map { ToolSpec(it.id, "${it.manifest.name}: ${it.manifest.description}", it.manifest.inputs) }

    /** Validates and stores a user-imported skill package. */
    suspend fun import(bytes: ByteArray, fileName: String): Skill {
        ensureLoaded()
        val skill = SkillManifestParser.parsePackage(bytes, fileName)
        SkillManifestParser.validateImport(skill, _skills.value.filter { it.builtIn }.map { it.id }.toSet())
        state.saveImported(
            ImportedSkillRecord(
                id = skill.id,
                manifestJson = TubeJson.encodeToString(SkillManifest.serializer(), skill.manifest),
                promptTemplate = skill.promptTemplate,
                importedAt = time.now(),
                sourceName = fileName,
            ),
        )
        audit.record("SkillRegistry", "skill.imported", "Imported ${skill.id} v${skill.manifest.version} from $fileName (permissions ${skill.manifest.permissions})", level = AuditLevel.SECURITY)
        reload()
        return skill
    }

    suspend fun remove(skillId: String) {
        val skill = get(skillId) ?: return
        require(!skill.builtIn) { "Built-in skills can be disabled but not removed" }
        state.deleteImported(skillId)
        audit.record("SkillRegistry", "skill.removed", "Removed imported skill $skillId", level = AuditLevel.SECURITY)
        reload()
    }

    /**
     * Executes [skillId] on behalf of [role]. Fails when the skill is unknown, not allowed for the
     * agent, disabled, or the kill switch is on. Every attempt is written to the skill-call log.
     */
    suspend fun invoke(role: AgentRole, skillId: String, input: JsonObject, projectId: String?): SkillOutcome {
        ensureLoaded()
        if (settings.current().killSwitch) throw AgentsPausedException()
        val skill = get(skillId) ?: throw SkillException(skillId, "unknown skill")
        if (role !in skill.manifest.agents) throw SkillException(skillId, "not available to ${role.displayName}")
        if (!isEnabled(skill, role, state.overrides())) throw SkillException(skillId, "disabled for ${role.displayName} in the Skills Store")
        val problems = SkillManifestParser.validateInput(skill.manifest.inputs, input)
        if (problems.isNotEmpty()) throw SkillException(skillId, "invalid input: ${problems.joinToString("; ")}")
        val executor = executors[skill.executorId] ?: throw SkillException(skillId, "executor '${skill.executorId}' is not installed")

        val started = time.now()
        return try {
            val output = executor.execute(SkillContext(skill, role, projectId, runtime), input)
            val duration = time.now() - started
            log(skill, role, projectId, input, output, null, duration)
            SkillOutcome(output, duration)
        } catch (e: Exception) {
            log(skill, role, projectId, input, null, e, time.now() - started)
            when (e) {
                is TubePilotException -> throw e
                is kotlinx.coroutines.CancellationException -> throw e
                else -> throw SkillException(skillId, e.message ?: e::class.java.simpleName, retryable = e is java.io.IOException, cause = e)
            }
        }
    }

    private suspend fun log(skill: Skill, role: AgentRole, projectId: String?, input: JsonObject, output: JsonObject?, error: Throwable?, durationMs: Long) {
        state.logCall(
            SkillCallRecord(
                timestamp = time.now(),
                skillId = skill.id,
                agent = role,
                projectId = projectId,
                input = input.toString(),
                output = output?.toString(),
                success = error == null,
                durationMs = durationMs,
                error = error?.message,
            ),
        )
        audit.record(
            role.displayName,
            if (error == null) "skill.call" else "skill.error",
            "${skill.id} in ${durationMs}ms" + (error?.let { ": ${JsonText.truncate(it.message.orEmpty(), 300)}" } ?: ""),
            projectId,
            if (error == null) AuditLevel.INFO else AuditLevel.WARNING,
        )
    }
}
