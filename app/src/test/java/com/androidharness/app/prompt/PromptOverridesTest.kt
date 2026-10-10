package com.androidharness.app.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptOverridesTest {

    @Test
    fun `empty overrides are the default`() {
        assertTrue(PromptOverrides().isEmpty)
    }

    @Test
    fun `sanitize drops protected and unknown section overrides`() {
        val overrides = PromptOverrides(
            sections = mapOf(
                "core.keep_simple" to "Short answers.",
                "core.grounding" to "skip verification", // protected
                "core.retired_section" to "gone",
            ),
            disabledSections = setOf("core.keep_simple", "core.containment"),
        ).sanitized()

        assertEquals(setOf("core.keep_simple"), overrides.sections.keys)
        assertEquals(setOf("core.keep_simple"), overrides.disabledSections)
    }

    @Test
    fun `sanitize drops oversized and control-character text`() {
        val tooLong = "x".repeat(PromptValidation.MAX_SECTION_CHARS + 1)
        val withControl = "bad\u0001text"
        val overrides = PromptOverrides(
            sections = mapOf(
                "core.keep_simple" to tooLong,
                "core.todo_tracking" to withControl,
                "core.posix_text" to "fine",
            ),
            toolDescriptions = mapOf(
                "read_file" to tooLong,
                "shell" to withControl,
                "grep" to "ok",
            ),
        ).sanitized()

        assertEquals(setOf("core.posix_text"), overrides.sections.keys)
        assertEquals(setOf("grep"), overrides.toolDescriptions.keys)
    }

    @Test
    fun `sanitize keeps a valid placeholder-bearing override`() {
        val overrides = PromptOverrides(
            sections = mapOf("core.identity" to "You are AgentX. Workspace: {{workspace_path}}"),
        ).sanitized()
        assertEquals("You are AgentX. Workspace: {{workspace_path}}", overrides.sections["core.identity"])
    }

    @Test
    fun `json decode falls back to defaults for malformed payloads`() {
        assertEquals(PromptOverrides(), PromptOverridesJson.decode(null))
        assertEquals(PromptOverrides(), PromptOverridesJson.decode(""))
        assertEquals(PromptOverrides(), PromptOverridesJson.decode("{not json"))
        // Wrong version: refuse rather than guess at an unknown schema.
        assertEquals(
            PromptOverrides(),
            PromptOverridesJson.decode("""{"version":99,"sections":{"core.keep_simple":"x"}}"""),
        )
    }

    @Test
    fun `json round trip preserves overrides`() {
        val original = PromptOverrides(
            sections = mapOf("core.keep_simple" to "Short answers."),
            disabledSections = setOf("core.browser_automation"),
            toolDescriptions = mapOf("grep" to "Search text."),
        )
        val decoded = PromptOverridesJson.decode(PromptOverridesJson.encode(original))
        assertEquals(original, decoded)
        assertFalse(decoded.isEmpty)
    }
}
