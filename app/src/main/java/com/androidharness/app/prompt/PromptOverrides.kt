package com.androidharness.app.prompt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Sparse representation of user prompt customization.
 *
 * Only what the user actually changed is stored: a default install carries an
 * empty [PromptOverrides] and behaves exactly like the version-controlled
 * defaults. Unknown section ids or tool names that appear here (for example
 * after a downgrade) are ignored at compose time, never applied.
 */
@Serializable
data class PromptOverrides(
    val version: Int = CURRENT_VERSION,
    /** section id -> replacement text. */
    val sections: Map<String, String> = emptyMap(),
    /** editable section ids the user turned off. */
    val disabledSections: Set<String> = emptySet(),
    /** tool name -> replacement description. */
    val toolDescriptions: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = sections.isEmpty() && disabledSections.isEmpty() && toolDescriptions.isEmpty()

    fun sectionOverride(id: String): String? = sections[id]

    fun isDisabled(id: String): Boolean = id in disabledSections

    fun toolOverride(name: String): String? = toolDescriptions[name]

    /**
     * Drops anything that can no longer be applied: retired section ids,
     * unknown tools, disabled entries that are no longer editable, and values
     * that fail validation. Prevents a stale or hostile stored payload from
     * affecting the assembled prompt.
     */
    fun sanitized(): PromptOverrides {
        val keptSections = sections.filterKeys { id ->
            val section = PromptDefaults.section(id) ?: return@filterKeys false
            section.protection == PromptProtection.EDITABLE &&
                PromptValidation.validateSectionText(section, sections[id].orEmpty()) == null
        }
        val keptDisabled = disabledSections.filter { id ->
            PromptDefaults.section(id)?.allowDisable == true
        }
        val keptTools = toolDescriptions.filterKeys { name ->
            name.isNotBlank() && name.length <= PromptValidation.MAX_TOOL_NAME_CHARS
        }.filterValues { PromptValidation.validateToolDescription(it) == null }
        return copy(sections = keptSections, disabledSections = keptDisabled.toSet(), toolDescriptions = keptTools)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** Single JSON codec for the persisted/backed-up [PromptOverrides] payload. */
object PromptOverridesJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(overrides: PromptOverrides): String =
        json.encodeToString(PromptOverrides.serializer(), overrides)

    /** Malformed or missing payloads fall back safely to the defaults. */
    fun decode(text: String?): PromptOverrides {
        if (text.isNullOrBlank()) return PromptOverrides()
        return runCatching { json.decodeFromString(PromptOverrides.serializer(), text) }
            .getOrNull()
            ?.let { if (it.version == PromptOverrides.CURRENT_VERSION) it else PromptOverrides() }
            ?: PromptOverrides()
    }
}
