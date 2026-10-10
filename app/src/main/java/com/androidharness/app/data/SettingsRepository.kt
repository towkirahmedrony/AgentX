package com.androidharness.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.prompt.PromptOverrides
import com.androidharness.app.prompt.PromptOverridesJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsStore by preferencesDataStore(name = "settings")

private val WEB_SEARCH_PROVIDERS = setOf("keyless", "brave", "tavily")

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

@kotlinx.serialization.Serializable
data class AppSettings(
    val permissionMode: PermissionMode = PermissionMode.CONFIRM_RISKY,
    val subagentFullAccess: Boolean = false,
    val subagentProviderId: String? = null,
    val subagentModel: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val activeProviderId: String? = null,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    val maxContextTokens: Int = DEFAULT_MAX_CONTEXT,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT,
    /** Tool-call iterations per run. 0 = unlimited. */
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    /** Hold a wakelock while a run is active so runs survive screen-off. */
    val keepAlive: Boolean = true,
    /** Drawer: pinned / archived session ids (DataStore, no DB migration). */
    val pinnedSessions: Set<String> = emptySet(),
    val archivedSessions: Set<String> = emptySet(),
    /** First-run setup was finished or skipped; stops the setup gate. */
    val onboardingDone: Boolean = false,
    /**
     * Model selected from the active provider's catalog. Null = use the
     * provider entry's saved default model.
     */
    val activeModel: String? = null,
    /** Skill names hidden from the catalog and slash picker. */
    val disabledSkills: Set<String> = emptySet(),
    /** web_search backend: "keyless" (default) | "brave" | "tavily". */
    val webSearchProvider: String = "keyless",
    /**
     * Screenshots are allowed by default outside credential screens. Off blocks
     * them app wide; screens that show keys and tokens always block.
     */
    val allowScreenshots: Boolean = true,
    /**
     * Separate models per agent mode: plan-mode runs use the planning
     * provider/model, everything else uses the execution one. Off (default)
     * runs everything on the single active model.
     */
    val planningModelsEnabled: Boolean = false,
    val planningProviderId: String? = null,
    val planningModel: String? = null,
    val executionProviderId: String? = null,
    val executionModel: String? = null,
    /** The one-time "you can now use two models" chat dialog was dismissed. */
    val planningModelsPromoSeen: Boolean = false,
    /** The one-time introductory fork dialog was dismissed. */
    val forkPromoSeen: Boolean = false,
    /** Biometric app lock: requires fingerprint/face/PIN authentication to open. */
    val biometricLockEnabled: Boolean = false,
    /** Grace period in minutes before re-locking when app is backgrounded (0 = immediately). */
    val biometricLockTimeoutMinutes: Int = 0,
    /** Voice speech-to-text engine: "inbuilt" (native Android recognizer) or "groq" (Groq Whisper API). */
    val voiceEngine: String = "inbuilt",
    /** Groq Whisper model: "whisper-large-v3" (default) or "whisper-large-v3-turbo". */
    val groqWhisperModel: String = "whisper-large-v3",
    /** The one-time voice configuration promo dialog was dismissed. */
    val voicePromoSeen: Boolean = false,
    /** Resume the last opened chat session when app launches. */
    val resumeLastChat: Boolean = true,
    /** ID of the last active chat session. */
    val lastActiveSessionId: String? = null,
    /** Generate and inject compact codebase symbol index (Repo map) into agent context. */
    val repoMapEnabled: Boolean = true,
    /** Local models start in plain chat until the user enables agent context. */
    val localModelAgentContext: Boolean = false,
    val cavemanInstalled: Boolean = false,
    val cavemanIntensity: com.androidharness.app.caveman.CavemanIntensity = com.androidharness.app.caveman.CavemanIntensity.OFF,
    val cavemanWenyan: Boolean = false,
    /**
     * Sparse user customization of the agent prompt. Empty (the default) means
     * the version-controlled defaults in `prompt/PromptDefaults.kt` are used
     * verbatim, so an untouched install behaves exactly like a source default.
     */
    val promptOverrides: PromptOverrides = PromptOverrides(),
    /** List skill names without their trigger descriptions to shorten the prompt. */
    val compactSkillsCatalog: Boolean = false,
    /**
     * Share of the context window held back for token-estimation error and the
     * model overshooting its own output. See
     * [com.androidharness.app.agent.ContextBudget].
     */
    val contextSafetyMarginPercent: Int = DEFAULT_SAFETY_MARGIN_PERCENT,
    /**
     * Schema version of the persisted execution limits. See
     * [com.androidharness.app.agent.LimitsMigration]: it is what lets a
     * never-configured limit take a safe default without rewriting the meaning
     * of a value a user chose on purpose.
     */
    val limitsVersion: Int = com.androidharness.app.agent.LimitsMigration.LEGACY,
) {
    companion object {
        const val DEFAULT_MAX_CONTEXT = 1_000_000
        const val DEFAULT_MAX_OUTPUT = 32_768
        const val DEFAULT_SAFETY_MARGIN_PERCENT =
            com.androidharness.app.agent.ContextBudget.DEFAULT_SAFETY_MARGIN_PERCENT
        /**
         * Iteration ceiling applied when the setting was never configured.
         * `0` here would mean "unlimited", which is exactly the ambiguity this
         * default removes: a user who wants no ceiling selects Unlimited
         * explicitly, and that choice is preserved as `0` on disk.
         */
        const val DEFAULT_MAX_ITERATIONS = com.androidharness.app.agent.ExecutionLimits.DEFAULT_ITERATIONS
        const val VOICE_ENGINE_INBUILT = "inbuilt"
        const val VOICE_ENGINE_GROQ = "groq"
        const val GROQ_MODEL_WHISPER_V3 = "whisper-large-v3"
        const val GROQ_MODEL_WHISPER_TURBO = "whisper-large-v3-turbo"
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PERMISSION_MODE = stringPreferencesKey("permission_mode")
        val SUBAGENT_FULL_ACCESS = booleanPreferencesKey("subagent_full_access")
        val SUBAGENT_PROVIDER = stringPreferencesKey("subagent_provider_id")
        val SUBAGENT_MODEL = stringPreferencesKey("subagent_model")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider_id")
        val THINKING_LEVEL = stringPreferencesKey("thinking_level")
        val MAX_CONTEXT = intPreferencesKey("max_context_tokens")
        val MAX_OUTPUT = intPreferencesKey("max_output_tokens")
        val MAX_ITERATIONS = intPreferencesKey("max_iterations")
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive")
        val PINNED = stringSetPreferencesKey("pinned_sessions")
        val ARCHIVED = stringSetPreferencesKey("archived_sessions")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val ACTIVE_MODEL = stringPreferencesKey("active_model")
        val DISABLED_SKILLS = stringSetPreferencesKey("disabled_skills")
        val WEB_SEARCH_PROVIDER = stringPreferencesKey("web_search_provider")
        val ALLOW_SCREENSHOTS = booleanPreferencesKey("allow_screenshots")
        val PLANNING_MODELS_ENABLED = booleanPreferencesKey("planning_models_enabled")
        val PLANNING_PROVIDER = stringPreferencesKey("planning_provider_id")
        val PLANNING_MODEL = stringPreferencesKey("planning_model")
        val EXECUTION_PROVIDER = stringPreferencesKey("execution_provider_id")
        val EXECUTION_MODEL = stringPreferencesKey("execution_model")
        val PLANNING_PROMO_SEEN = booleanPreferencesKey("planning_promo_seen")
        val FORK_PROMO_SEEN = booleanPreferencesKey("fork_promo_seen")
        val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        val BIOMETRIC_LOCK_TIMEOUT = intPreferencesKey("biometric_lock_timeout_minutes")
        val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        val GROQ_WHISPER_MODEL = stringPreferencesKey("groq_whisper_model")
        val VOICE_PROMO_SEEN = booleanPreferencesKey("voice_promo_seen")
        val RESUME_LAST_CHAT = booleanPreferencesKey("resume_last_chat")
        val LAST_ACTIVE_SESSION = stringPreferencesKey("last_active_session_id")
        val REPO_MAP_ENABLED = booleanPreferencesKey("repo_map_enabled")
        val LOCAL_MODEL_AGENT_CONTEXT = booleanPreferencesKey("local_model_agent_context")
        val CAVEMAN_INSTALLED = booleanPreferencesKey("caveman_installed")
        val CAVEMAN_INTENSITY = stringPreferencesKey("caveman_intensity")
        val CAVEMAN_WENYAN = booleanPreferencesKey("caveman_wenyan")
        val PROMPT_OVERRIDES = stringPreferencesKey("prompt_overrides")
        val COMPACT_SKILLS_CATALOG = booleanPreferencesKey("compact_skills_catalog")
        val CONTEXT_SAFETY_MARGIN = intPreferencesKey("context_safety_margin_percent")
        val LIMITS_SCHEMA_VERSION = intPreferencesKey("limits_schema_version")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        // Resolved on every read rather than written once at startup: the
        // result depends only on what is on disk, so it survives anything and
        // cannot be lost by a process death between migrate and save.
        val limits = com.androidharness.app.agent.LimitsMigration.migrate(
            storedVersion = prefs[Keys.LIMITS_SCHEMA_VERSION]
                ?: com.androidharness.app.agent.LimitsMigration.LEGACY,
            storedIterations = prefs[Keys.MAX_ITERATIONS],
        )
        AppSettings(
            permissionMode = prefs[Keys.PERMISSION_MODE]
                ?.let { runCatching { PermissionMode.valueOf(it) }.getOrNull() }
                ?: PermissionMode.CONFIRM_RISKY,
            subagentFullAccess = prefs[Keys.SUBAGENT_FULL_ACCESS] ?: false,
            themeMode = prefs[Keys.THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            activeProviderId = prefs[Keys.ACTIVE_PROVIDER] ?: com.androidharness.app.llm.HarnessProvider.ID,
            thinkingLevel = prefs[Keys.THINKING_LEVEL]
                ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
                ?: ThinkingLevel.OFF,
            maxContextTokens = prefs[Keys.MAX_CONTEXT] ?: AppSettings.DEFAULT_MAX_CONTEXT,
            maxOutputTokens = prefs[Keys.MAX_OUTPUT] ?: AppSettings.DEFAULT_MAX_OUTPUT,
            maxIterations = limits.iterations,
            limitsVersion = limits.version,
            keepAlive = prefs[Keys.KEEP_ALIVE] ?: true,
            pinnedSessions = prefs[Keys.PINNED] ?: emptySet(),
            archivedSessions = prefs[Keys.ARCHIVED] ?: emptySet(),
            onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: false,
            activeModel = prefs[Keys.ACTIVE_MODEL],
            disabledSkills = prefs[Keys.DISABLED_SKILLS] ?: emptySet(),
            webSearchProvider = prefs[Keys.WEB_SEARCH_PROVIDER]
                ?.takeIf { it in WEB_SEARCH_PROVIDERS }
                ?: "keyless",
            allowScreenshots = prefs[Keys.ALLOW_SCREENSHOTS] ?: true,
            planningModelsEnabled = prefs[Keys.PLANNING_MODELS_ENABLED] ?: false,
            planningProviderId = prefs[Keys.PLANNING_PROVIDER],
            planningModel = prefs[Keys.PLANNING_MODEL],
            executionProviderId = prefs[Keys.EXECUTION_PROVIDER],
            executionModel = prefs[Keys.EXECUTION_MODEL],
            subagentProviderId = prefs[Keys.SUBAGENT_PROVIDER],
            subagentModel = prefs[Keys.SUBAGENT_MODEL],
            planningModelsPromoSeen = prefs[Keys.PLANNING_PROMO_SEEN] ?: false,
            forkPromoSeen = prefs[Keys.FORK_PROMO_SEEN] ?: false,
            biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK_ENABLED] ?: false,
            biometricLockTimeoutMinutes = prefs[Keys.BIOMETRIC_LOCK_TIMEOUT] ?: 0,
            voiceEngine = prefs[Keys.VOICE_ENGINE] ?: AppSettings.VOICE_ENGINE_INBUILT,
            groqWhisperModel = prefs[Keys.GROQ_WHISPER_MODEL] ?: AppSettings.GROQ_MODEL_WHISPER_V3,
            voicePromoSeen = prefs[Keys.VOICE_PROMO_SEEN] ?: false,
            resumeLastChat = prefs[Keys.RESUME_LAST_CHAT] ?: true,
            lastActiveSessionId = prefs[Keys.LAST_ACTIVE_SESSION],
            repoMapEnabled = prefs[Keys.REPO_MAP_ENABLED] ?: true,
            localModelAgentContext = prefs[Keys.LOCAL_MODEL_AGENT_CONTEXT] ?: false,
            cavemanInstalled = prefs[Keys.CAVEMAN_INSTALLED] ?: false,
            cavemanIntensity = prefs[Keys.CAVEMAN_INTENSITY]?.let {
                runCatching { com.androidharness.app.caveman.CavemanIntensity.valueOf(it) }.getOrNull()
            } ?: com.androidharness.app.caveman.CavemanIntensity.OFF,
            cavemanWenyan = prefs[Keys.CAVEMAN_WENYAN] ?: false,
            promptOverrides = PromptOverridesJson.decode(prefs[Keys.PROMPT_OVERRIDES]).sanitized(),
            compactSkillsCatalog = prefs[Keys.COMPACT_SKILLS_CATALOG] ?: false,
            contextSafetyMarginPercent = (prefs[Keys.CONTEXT_SAFETY_MARGIN] ?: AppSettings.DEFAULT_SAFETY_MARGIN_PERCENT)
                .coerceIn(
                    com.androidharness.app.agent.ContextBudget.MIN_SAFETY_MARGIN_PERCENT,
                    com.androidharness.app.agent.ContextBudget.MAX_SAFETY_MARGIN_PERCENT,
                ),
        )
    }

    suspend fun restorePortable(s: AppSettings) {
        context.settingsStore.edit { p ->
            p[Keys.THEME_MODE] = s.themeMode.name
            p[Keys.DYNAMIC_COLOR] = s.dynamicColor
            p[Keys.THINKING_LEVEL] = s.thinkingLevel.name
            p[Keys.MAX_CONTEXT] = s.maxContextTokens
            p[Keys.MAX_OUTPUT] = s.maxOutputTokens
            p[Keys.MAX_ITERATIONS] = s.maxIterations
            p[Keys.LIMITS_SCHEMA_VERSION] = com.androidharness.app.agent.LimitsMigration.CURRENT
            p[Keys.KEEP_ALIVE] = s.keepAlive
            p[Keys.SUBAGENT_FULL_ACCESS] = s.subagentFullAccess
            p[Keys.DISABLED_SKILLS] = s.disabledSkills
            p[Keys.WEB_SEARCH_PROVIDER] = s.webSearchProvider
            p[Keys.VOICE_ENGINE] = s.voiceEngine
            p[Keys.GROQ_WHISPER_MODEL] = s.groqWhisperModel
            p[Keys.RESUME_LAST_CHAT] = s.resumeLastChat
            p[Keys.REPO_MAP_ENABLED] = s.repoMapEnabled
            p[Keys.LOCAL_MODEL_AGENT_CONTEXT] = s.localModelAgentContext
            p[Keys.CAVEMAN_INSTALLED] = s.cavemanInstalled
            p[Keys.CAVEMAN_INTENSITY] = s.cavemanIntensity.name
            p[Keys.CAVEMAN_WENYAN] = s.cavemanWenyan
            p[Keys.PLANNING_MODELS_ENABLED] = s.planningModelsEnabled
            p[Keys.COMPACT_SKILLS_CATALOG] = s.compactSkillsCatalog
            p[Keys.CONTEXT_SAFETY_MARGIN] = s.contextSafetyMarginPercent.coerceIn(
                com.androidharness.app.agent.ContextBudget.MIN_SAFETY_MARGIN_PERCENT,
                com.androidharness.app.agent.ContextBudget.MAX_SAFETY_MARGIN_PERCENT,
            )
            val restoredPrompts = s.promptOverrides.sanitized()
            if (restoredPrompts.isEmpty) p.remove(Keys.PROMPT_OVERRIDES)
            else p[Keys.PROMPT_OVERRIDES] = PromptOverridesJson.encode(restoredPrompts)
            listOf(Keys.ACTIVE_PROVIDER to s.activeProviderId, Keys.ACTIVE_MODEL to s.activeModel,
                Keys.PLANNING_PROVIDER to s.planningProviderId, Keys.PLANNING_MODEL to s.planningModel,
                Keys.EXECUTION_PROVIDER to s.executionProviderId, Keys.EXECUTION_MODEL to s.executionModel,
                Keys.SUBAGENT_PROVIDER to s.subagentProviderId, Keys.SUBAGENT_MODEL to s.subagentModel
            ).forEach { (key, value) -> if (value == null) p.remove(key) else p[key] = value }
        }
    }

    suspend fun setWebSearchProvider(provider: String) {
        if (provider !in WEB_SEARCH_PROVIDERS) return
        context.settingsStore.edit { it[Keys.WEB_SEARCH_PROVIDER] = provider }
    }

    suspend fun setPermissionMode(mode: PermissionMode) {
        context.settingsStore.edit { it[Keys.PERMISSION_MODE] = mode.name }
    }

    suspend fun setSubagentFullAccess(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.SUBAGENT_FULL_ACCESS] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAllowScreenshots(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.ALLOW_SCREENSHOTS] = enabled }
    }

    suspend fun setPlanningModelsEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.PLANNING_MODELS_ENABLED] = enabled }
    }

    suspend fun setPlanningModelsPromoSeen(seen: Boolean) {
        context.settingsStore.edit { it[Keys.PLANNING_PROMO_SEEN] = seen }
    }

    suspend fun setForkPromoSeen(seen: Boolean) {
        context.settingsStore.edit { it[Keys.FORK_PROMO_SEEN] = seen }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.BIOMETRIC_LOCK_ENABLED] = enabled }
    }

    suspend fun setBiometricLockTimeout(minutes: Int) {
        context.settingsStore.edit { it[Keys.BIOMETRIC_LOCK_TIMEOUT] = minutes }
    }

    suspend fun setVoiceEngine(engine: String) {
        context.settingsStore.edit { it[Keys.VOICE_ENGINE] = engine }
    }

    suspend fun setGroqWhisperModel(model: String) {
        context.settingsStore.edit { it[Keys.GROQ_WHISPER_MODEL] = model }
    }

    suspend fun setVoicePromoSeen(seen: Boolean) {
        context.settingsStore.edit { it[Keys.VOICE_PROMO_SEEN] = seen }
    }

    suspend fun setPlanningModel(providerId: String?, model: String?) {
        context.settingsStore.edit { prefs ->
            if (providerId == null) prefs.remove(Keys.PLANNING_PROVIDER)
            else prefs[Keys.PLANNING_PROVIDER] = providerId
            if (model.isNullOrBlank()) prefs.remove(Keys.PLANNING_MODEL)
            else prefs[Keys.PLANNING_MODEL] = model
        }
    }

    suspend fun setExecutionModel(providerId: String?, model: String?) {
        context.settingsStore.edit { prefs ->
            if (providerId == null) prefs.remove(Keys.EXECUTION_PROVIDER)
            else prefs[Keys.EXECUTION_PROVIDER] = providerId
            if (model.isNullOrBlank()) prefs.remove(Keys.EXECUTION_MODEL)
            else prefs[Keys.EXECUTION_MODEL] = model
        }
    }

    suspend fun setSubagentModel(providerId: String?, model: String?) {
        context.settingsStore.edit { prefs ->
            if (providerId.isNullOrBlank()) {
                prefs.remove(Keys.SUBAGENT_PROVIDER)
                prefs.remove(Keys.SUBAGENT_MODEL)
            } else {
                prefs[Keys.SUBAGENT_PROVIDER] = providerId
                if (model.isNullOrBlank()) prefs.remove(Keys.SUBAGENT_MODEL)
                else prefs[Keys.SUBAGENT_MODEL] = model
            }
        }
    }

    suspend fun setActiveProvider(id: String?) {
        context.settingsStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_PROVIDER)
            else prefs[Keys.ACTIVE_PROVIDER] = id
        }
    }

    suspend fun clearMissingLocalProviderSelections(availableIds: Set<String>) {
        context.settingsStore.edit { prefs ->
            listOf(Keys.ACTIVE_PROVIDER to Keys.ACTIVE_MODEL,
                Keys.PLANNING_PROVIDER to Keys.PLANNING_MODEL,
                Keys.EXECUTION_PROVIDER to Keys.EXECUTION_MODEL,
                Keys.SUBAGENT_PROVIDER to Keys.SUBAGENT_MODEL).forEach { (providerKey, modelKey) ->
                val id = prefs[providerKey]
                if (id != null && (com.androidharness.app.local.LocalModelCatalog.isLocal(id) || com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(id)) && id !in availableIds) {
                    if (providerKey == Keys.ACTIVE_PROVIDER) {
                        prefs[providerKey] = com.androidharness.app.llm.HarnessProvider.ID
                    } else prefs.remove(providerKey)
                    prefs.remove(modelKey)
                }
            }
        }
    }

    suspend fun setThinkingLevel(level: ThinkingLevel) {
        context.settingsStore.edit { it[Keys.THINKING_LEVEL] = level.name }
    }

    suspend fun setMaxContextTokens(tokens: Int) {
        context.settingsStore.edit { it[Keys.MAX_CONTEXT] = tokens }
    }

    suspend fun setMaxOutputTokens(tokens: Int) {
        context.settingsStore.edit { it[Keys.MAX_OUTPUT] = tokens }
    }

    /**
     * Persisting a value through the UI always marks the limits schema current,
     * so an explicit choice — including Unlimited — is never re-migrated.
     */
    suspend fun setMaxIterations(iterations: Int) {
        context.settingsStore.edit {
            it[Keys.MAX_ITERATIONS] = iterations
            it[Keys.LIMITS_SCHEMA_VERSION] = com.androidharness.app.agent.LimitsMigration.CURRENT
        }
    }

    suspend fun setKeepAlive(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.KEEP_ALIVE] = enabled }
    }

    suspend fun setPinned(sessionId: String, pinned: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.PINNED] ?: emptySet()
            prefs[Keys.PINNED] = if (pinned) current + sessionId else current - sessionId
        }
    }

    suspend fun setArchived(sessionId: String, archived: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.ARCHIVED] ?: emptySet()
            prefs[Keys.ARCHIVED] = if (archived) current + sessionId else current - sessionId
        }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.settingsStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setRepoMapEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.REPO_MAP_ENABLED] = enabled }
    }

    suspend fun setCavemanInstalled(installed: Boolean) {
        context.settingsStore.edit {
            it[Keys.CAVEMAN_INSTALLED] = installed
            it[Keys.CAVEMAN_INTENSITY] = com.androidharness.app.caveman.CavemanIntensity.OFF.name
        }
    }

    suspend fun setLocalModelAgentContext(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.LOCAL_MODEL_AGENT_CONTEXT] = enabled }
    }

    suspend fun setCavemanIntensity(intensity: com.androidharness.app.caveman.CavemanIntensity) {
        context.settingsStore.edit {
            if (it[Keys.CAVEMAN_INSTALLED] == true) it[Keys.CAVEMAN_INTENSITY] = intensity.name
        }
    }

    suspend fun setCavemanWenyan(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.CAVEMAN_WENYAN] = enabled }
    }

    suspend fun setSkillEnabled(name: String, enabled: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_SKILLS] ?: emptySet()
            prefs[Keys.DISABLED_SKILLS] = if (enabled) current - name else current + name
        }
    }

    /** Model override for the ACTIVE provider; null falls back to its saved default. */
    suspend fun setActiveModel(model: String?) {
        context.settingsStore.edit { prefs ->
            if (model.isNullOrBlank()) prefs.remove(Keys.ACTIVE_MODEL)
            else prefs[Keys.ACTIVE_MODEL] = model
        }
    }

    suspend fun setResumeLastChat(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.RESUME_LAST_CHAT] = enabled }
    }

    suspend fun setLastActiveSessionId(sessionId: String?) {
        context.settingsStore.edit { prefs ->
            if (sessionId.isNullOrBlank()) prefs.remove(Keys.LAST_ACTIVE_SESSION)
            else prefs[Keys.LAST_ACTIVE_SESSION] = sessionId
        }
    }

    // --- Prompt customization -------------------------------------------

    suspend fun setCompactSkillsCatalog(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.COMPACT_SKILLS_CATALOG] = enabled }
    }

    /**
     * Share of the context window held back for estimation error. Clamped to the
     * documented range so a corrupt or hostile value can never drive the usable
     * input budget to zero or beyond the window.
     */
    suspend fun setContextSafetyMarginPercent(percent: Int) {
        context.settingsStore.edit {
            it[Keys.CONTEXT_SAFETY_MARGIN] = percent.coerceIn(
                com.androidharness.app.agent.ContextBudget.MIN_SAFETY_MARGIN_PERCENT,
                com.androidharness.app.agent.ContextBudget.MAX_SAFETY_MARGIN_PERCENT,
            )
        }
    }

    /** Clears every prompt and tool-description override, and turns the compact catalog back off. */
    suspend fun resetAllPrompts() {
        context.settingsStore.edit { prefs ->
            prefs.remove(Keys.PROMPT_OVERRIDES)
            prefs[Keys.COMPACT_SKILLS_CATALOG] = false
        }
    }

    /** Replaces one editable section's text; null restores the default. */
    suspend fun setPromptSectionOverride(sectionId: String, text: String?) = updatePromptOverrides { current ->
        if (text == null) current.copy(sections = current.sections - sectionId)
        else current.copy(sections = current.sections + (sectionId to text))
    }

    /** Drops both the text override and the disabled flag for one section. */
    suspend fun resetPromptSection(sectionId: String) = updatePromptOverrides { current ->
        current.copy(
            sections = current.sections - sectionId,
            disabledSections = current.disabledSections - sectionId,
        )
    }

    /** Editable sections may be turned off; protected ones are dropped by [PromptOverrides.sanitized]. */
    suspend fun setPromptSectionEnabled(sectionId: String, enabled: Boolean) = updatePromptOverrides { current ->
        if (enabled) current.copy(disabledSections = current.disabledSections - sectionId)
        else current.copy(disabledSections = current.disabledSections + sectionId)
    }

    /** Replaces one tool's description; null restores the built-in one. */
    suspend fun setToolDescriptionOverride(toolName: String, description: String?) = updatePromptOverrides { current ->
        if (description == null) current.copy(toolDescriptions = current.toolDescriptions - toolName)
        else current.copy(toolDescriptions = current.toolDescriptions + (toolName to description))
    }

    suspend fun resetToolDescription(toolName: String) = updatePromptOverrides { current ->
        current.copy(toolDescriptions = current.toolDescriptions - toolName)
    }

    /**
     * Read-modify-write of the stored payload. The result is always sanitized so a
     * retired section id, a protected section or invalid text can never reach disk.
     */
    private suspend fun updatePromptOverrides(
        transform: (PromptOverrides) -> PromptOverrides,
    ) {
        context.settingsStore.edit { prefs ->
            val current = PromptOverridesJson.decode(prefs[Keys.PROMPT_OVERRIDES])
            val next = transform(current).sanitized()
            if (next.isEmpty) prefs.remove(Keys.PROMPT_OVERRIDES)
            else prefs[Keys.PROMPT_OVERRIDES] = PromptOverridesJson.encode(next)
        }
    }
}
