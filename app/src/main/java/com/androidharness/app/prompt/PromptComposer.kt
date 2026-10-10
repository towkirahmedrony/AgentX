package com.androidharness.app.prompt

/**
 * Assembles the final prompt from version-controlled defaults, sparse user
 * overrides and the runtime [PromptFacts].
 *
 * The composer is a pure function: no Android APIs, no I/O. That keeps the
 * assembled prompt unit-testable and guarantees that editing customization
 * cannot reach the protected rules, which always come from [PromptDefaults].
 */
object PromptComposer {

    /** One section's contribution to the assembled prompt plus preview metadata. */
    data class ComposedSection(
        val section: PromptSection,
        val text: String,
        val active: Boolean,
        val overridden: Boolean,
        val disabled: Boolean,
        val problem: String? = null,
        val charCount: Int = text.length,
        val estimatedTokens: Int = text.length / 4,
    ) {
        val id: String get() = section.id
        val title: String get() = section.title
        val scope: PromptScope get() = section.scope
        val protected: Boolean get() = section.protected
    }

    /** The assembled prompt together with the per-section breakdown for the preview. */
    data class ComposedPrompt(val sections: List<ComposedSection>) {
        val text: String = sections.filter { it.active }.joinToString("") { it.text }
        val charCount: Int get() = text.length
        val estimatedTokens: Int get() = charCount / 4
    }

    /**
     * Assembles the main agent prompt. [overrides] are honored only for
     * [PromptProtection.EDITABLE] sections whose text still validates; anything
     * else falls back to the default.
     */
    fun compose(
        facts: PromptFacts,
        overrides: PromptOverrides = PromptOverrides(),
    ): ComposedPrompt = compose(PromptDefaults.MAIN_SECTIONS, facts, overrides)

    /** Sections of one scope only, in assembly order (used for the Skills block). */
    fun composeScope(
        scope: PromptScope,
        facts: PromptFacts,
        overrides: PromptOverrides = PromptOverrides(),
    ): ComposedPrompt = compose(PromptDefaults.ALL_SECTIONS.filter { it.scope == scope }, facts, overrides)

    private fun compose(
        sections: List<PromptSection>,
        facts: PromptFacts,
        overrides: PromptOverrides,
    ): ComposedPrompt {
        val composed = sections.map { section ->
            if (!facts.matches(section.trigger)) {
                return@map ComposedSection(section, "", active = false, overridden = false, disabled = false)
            }
            if (section.allowDisable && overrides.isDisabled(section.id)) {
                return@map ComposedSection(section, "", active = false, overridden = false, disabled = true)
            }
            val candidate = if (section.protection == PromptProtection.EDITABLE) {
                overrides.sectionOverride(section.id)?.takeIf { it != section.defaultText }
            } else {
                null
            }
            val problem = candidate?.let { PromptValidation.validateSectionText(section, it) }
            val body = if (candidate != null && problem == null) candidate else section.defaultText
            ComposedSection(
                section = section,
                text = substitute(body, facts),
                active = true,
                overridden = candidate != null && problem == null,
                disabled = false,
                problem = problem,
            )
        }
        return ComposedPrompt(composed)
    }

    /**
     * The subagent system prompt. Read-only and action agents keep their own
     * protected instructions plus the shared restrictions; there is no user
     * customization on this path.
     */
    fun subagentSystemPrompt(actionTools: Boolean): String {
        val body = PromptDefaults.SUBAGENT_SECTIONS.filter { section ->
            when (section.trigger) {
                PromptTrigger.SUBAGENT_ACTION -> actionTools
                PromptTrigger.SUBAGENT_READONLY -> !actionTools
                else -> true
            }
        }.joinToString("") { it.defaultText }
        return substitute(body, PromptFacts(workspaceKind = WorkspaceKind.NO_SHELL, workspacePath = ""))
    }

    /**
     * The Skills prompt block (`## Skills (mandatory)` through
     * `</available_skills>`), including the surrounding separators the main
     * prompt needs. Empty when no skill is enabled.
     */
    fun skillsBlock(index: String, compact: Boolean = false): String {
        if (index.isBlank()) return ""
        val facts = PromptFacts(
            workspaceKind = WorkspaceKind.NO_SHELL,
            workspacePath = "",
            skillsIndex = index,
            compactSkillsCatalog = compact,
        )
        return composeScope(PromptScope.SKILLS, facts).text
    }

    /** Resolves every `{{placeholder}}` from the runtime facts. */
    private fun substitute(text: String, facts: PromptFacts): String {
        if (!text.contains("{{")) return text
        return PromptSection.PLACEHOLDER_REGEX.replace(text) { match ->
            placeholderValue(match.groupValues[1], facts) ?: match.value
        }
    }

    private fun placeholderValue(name: String, facts: PromptFacts): String? = when (name) {
        "workspace_path" -> facts.workspacePath
        "workspace_root" -> facts.workspaceRoot
        "skills_index" -> renderSkillsIndex(facts)
        "agents_md" -> facts.agentsMd.orEmpty()
        "memory" -> facts.memory.orEmpty()
        "memory_topics" -> facts.memoryTopics.joinToString(", ")
        "todos" -> facts.todos.orEmpty()
        "repository_map" -> facts.repositoryMap.orEmpty()
        else -> null
    }

    /**
     * The catalog body is trimmed (matching the historical block) and can be
     * rendered compactly as `name` only when the user asks for it.
     */
    private fun renderSkillsIndex(facts: PromptFacts): String {
        val raw = facts.skillsIndex.orEmpty().trimEnd()
        if (!facts.compactSkillsCatalog) return raw
        return raw.lineSequence().joinToString("\n") { line ->
            val dash = line.indexOf("- ")
            val colon = line.indexOf(':', startIndex = if (dash >= 0) dash + 2 else 0)
            if (dash >= 0 && colon > dash) line.substring(0, colon) else line
        }
    }
}
