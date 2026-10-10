package com.androidharness.app.prompt

/** Which part of the assembled agent prompt a section belongs to. */
enum class PromptScope(val label: String) {
    CORE("Core"),
    ENVIRONMENT("Environment"),
    WORKSPACE("Workspace"),
    SKILLS("Skills"),
    SUBAGENT("Sub-agents"),
}

/**
 * How much freedom a user has over a section.
 *
 * [PROTECTED] sections carry a security or correctness rule from the protected
 * rule set (path containment, shell restrictions, grounding, PLAN-mode limits,
 * tool schemas and so on). Their defaults are always used, they cannot be
 * disabled, and customization never removes or replaces them.
 */
enum class PromptProtection { EDITABLE, PROTECTED }

/**
 * The runtime condition under which a section contributes to the prompt.
 *
 * Triggers are pure predicates over [PromptFacts] so the assembled prompt is a
 * deterministic function of the runtime facts, which keeps it testable without
 * an Android device.
 */
enum class PromptTrigger {
    ALWAYS,

    /** Exactly one workspace shape is active at a time. */
    WORKSPACE_SSH,
    WORKSPACE_SHELL_READY,
    WORKSPACE_SHELL_TOYBOX,
    WORKSPACE_NO_SHELL,

    /** True for every workspace that is not an SSH host. */
    NOT_SSH,

    SHIZUKU_GRANTED,
    SHIZUKU_NO_PERMISSION,
    SHIZUKU_NOT_RUNNING,

    FULL_ACCESS,
    PLAN_MODE,

    SKILLS_PRESENT,
    AGENTS_MD_PRESENT,
    MEMORY_PRESENT,
    MEMORY_TOPICS_PRESENT,
    TODOS_PRESENT,
    REPO_MAP_PRESENT,

    /** Sub-agent instruction variants, resolved by [PromptComposer.subagentSystemPrompt]. */
    SUBAGENT_ACTION,
    SUBAGENT_READONLY,
}

/**
 * One independently addressable piece of the agent prompt.
 *
 * [id] is the stable key persistence and backups use; it never depends on the
 * display [title]. [defaultText] lives in version-controlled source so changes
 * stay reviewable in Git. Placeholders are `{{name}}` tokens resolved from
 * [PromptFacts] at compose time; a section that declares placeholders requires
 * an override to keep every one of them.
 */
data class PromptSection(
    val id: String,
    val scope: PromptScope,
    val title: String,
    val description: String,
    val defaultText: String,
    val protection: PromptProtection = PromptProtection.EDITABLE,
    val trigger: PromptTrigger = PromptTrigger.ALWAYS,
    val maxChars: Int = PromptValidation.MAX_SECTION_CHARS,
    /** Editable sections may be turned off; protected sections never can. */
    val allowDisable: Boolean = protection == PromptProtection.EDITABLE,
) {
    val protected: Boolean get() = protection == PromptProtection.PROTECTED

    val requiredPlaceholders: List<String>
        get() = PLACEHOLDER_REGEX.findAll(defaultText).map { it.groupValues[1] }.distinct().toList()

    companion object {
        val PLACEHOLDER_REGEX = Regex("""\{\{([a-z_]+)\}\}""")
    }
}

/**
 * Storage limits and text validation shared by the settings repository, the
 * backup validator and the composer. Limits are explicit and surfaced to the
 * user instead of silently truncating their text.
 */
object PromptValidation {
    const val MAX_SECTION_CHARS = 20_000
    const val MAX_TOOL_DESCRIPTION_CHARS = 4_000
    const val MAX_OVERRIDE_COUNT = 300
    const val MAX_SECTION_ID_CHARS = 80
    const val MAX_TOOL_NAME_CHARS = 200

    /** C0 controls other than tab/newline/carriage return, plus DEL. */
    private val DISALLOWED_CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val SECTION_ID = Regex("^[a-z0-9]+(?:[._][a-z0-9]+)*$")

    fun containsDisallowedControl(value: String): Boolean = DISALLOWED_CONTROL.containsMatchIn(value)

    fun isSectionId(value: String): Boolean =
        value.length in 1..MAX_SECTION_ID_CHARS && SECTION_ID.matches(value)

    /**
     * Returns a human-readable problem with [text] for [section], or null when
     * the text is acceptable. Callers surface the message rather than dropping
     * the user's edit.
     */
    fun validateSectionText(section: PromptSection, text: String): String? {
        if (text.length > section.maxChars) {
            return "Too long: ${text.length} of ${section.maxChars} characters."
        }
        if (containsDisallowedControl(text)) return "Contains unsupported control characters."
        val missing = section.requiredPlaceholders.filterNot { text.contains("{{$it}}") }
        if (missing.isNotEmpty()) {
            return "Keep the placeholder(s): ${missing.joinToString(" ") { "{{$it}}" }}."
        }
        return null
    }

    fun validateToolDescription(text: String): String? {
        if (text.isBlank()) return "Description cannot be empty."
        if (text.length > MAX_TOOL_DESCRIPTION_CHARS) {
            return "Too long: ${text.length} of $MAX_TOOL_DESCRIPTION_CHARS characters."
        }
        if (containsDisallowedControl(text)) return "Contains unsupported control characters."
        return null
    }
}
