package com.androidharness.app.data

import com.androidharness.app.prompt.PromptOverrides
import com.androidharness.app.prompt.PromptValidation
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBackupCompatibilityTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `a backup exported before prompt customization still validates`() {
        // No promptOverrides field at all: kotlinx uses the default.
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(SettingsBackupFile.serializer(), """{"version":1,"settings":{}}""")
        assertTrue(decoded.settings.promptOverrides.isEmpty)
        SettingsBackupValidation.validate(decoded)
    }

    @Test
    fun `prompt overrides survive an export and import round trip`() {
        val overrides = PromptOverrides(
            sections = mapOf("core.keep_simple" to "Short answers."),
            disabledSections = setOf("core.browser_automation"),
            toolDescriptions = mapOf("grep" to "Search text."),
        )
        val file = SettingsBackupFile(settings = AppSettings(promptOverrides = overrides))
        val encoded = json.encodeToString(SettingsBackupFile.serializer(), file)
        val decoded = json.decodeFromString(SettingsBackupFile.serializer(), encoded)
        SettingsBackupValidation.validate(decoded)
        assertEquals(overrides, decoded.settings.promptOverrides)
        assertEquals(overrides, decoded.settings.promptOverrides.sanitized())
    }

    @Test
    fun `oversized prompt text is rejected on import`() {
        val huge = "x".repeat(PromptValidation.MAX_SECTION_CHARS + 1)
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupValidation.validate(
                SettingsBackupFile(settings = AppSettings(promptOverrides = PromptOverrides(sections = mapOf("core.keep_simple" to huge)))),
            )
        }
    }

    @Test
    fun `control characters in prompt text are rejected on import`() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupValidation.validate(
                SettingsBackupFile(settings = AppSettings(promptOverrides = PromptOverrides(sections = mapOf("core.keep_simple" to "bad\u0001")))),
            )
        }
    }

    @Test
    fun `malformed section ids are rejected while well-formed unknown ids validate`() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupValidation.validate(
                SettingsBackupFile(settings = AppSettings(promptOverrides = PromptOverrides(sections = mapOf("Not An Id" to "x")))),
            )
        }
        // A retired-but-well-formed id is accepted and simply ignored at compose time.
        SettingsBackupValidation.validate(
            SettingsBackupFile(settings = AppSettings(promptOverrides = PromptOverrides(sections = mapOf("core.retired_section" to "x")))),
        )
    }

    @Test
    fun `unknown version is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupValidation.validate(
                SettingsBackupFile(settings = AppSettings(promptOverrides = PromptOverrides(version = 99))),
            )
        }
    }
}
