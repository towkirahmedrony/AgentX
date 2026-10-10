package com.androidharness.app.ui.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun `search finds controls by familiar names and hidden keywords`() {
        assertEquals(listOf(SettingsPage.APPEARANCE), matchingSettingsPages("  DARK  theme "))
        assertEquals(listOf(SettingsPage.ENVIRONMENT), matchingSettingsPages("battery"))
        assertTrue(SettingsPage.MODELS in matchingSettingsPages("api\tkey"))
        assertEquals(listOf(SettingsPage.CHAT), matchingSettingsPages("repo map"))
        assertEquals(listOf(SettingsPage.PRIVACY), matchingSettingsPages("fingerprint"))
        assertEquals(listOf(SettingsPage.CODE_INTELLIGENCE), matchingSettingsPages("codegraph affected"))
    }

    @Test
    fun `search returns nested settings as primary style results`() {
        val context = matchingSettingsEntries("max context").first { it.title == "Max context window" }
        assertEquals(SettingsPage.AGENT, context.page)
        assertFalse(context.primary)

        val planning = matchingSettingsEntries("plan model").first { it.title == "Plan model" }
        assertEquals(SettingsPage.MODELS, planning.page)

        val lock = matchingSettingsEntries("auto lock timeout").first { it.title == "Auto-lock timeout" }
        assertEquals(SettingsPage.PRIVACY, lock.page)
        val subagent = matchingSettingsEntries("subagent model").first { it.title == "Subagent model" }
        assertEquals(SettingsPage.SUBAGENT, subagent.page)
        assertEquals("Subagent model", subagent.anchor)
    }

    @Test
    fun `empty search keeps the normal primary settings home`() {
        val entries = matchingSettingsEntries("")
        assertEquals(SettingsPage.entries.size, entries.size)
        assertTrue(entries.all { it.primary })
        assertEquals(SettingsPage.entries.toList(), matchingSettingsPages(""))
    }

    @Test
    fun `automation search finds integrated GitHub presets and build shortcut stays removed`() {
        assertEquals(listOf(SettingsPage.GITHUB), matchingSettingsPages("automation"))
        assertTrue(matchingSettingsPages("build test").isEmpty())
    }

    @Test
    fun `GitHub integration is the first settings page and supports a direct link`() {
        assertEquals(SettingsPage.GITHUB, matchingSettingsPages("").first())
        assertEquals(SettingsPage.GITHUB, settingsDeepLink("github"))
        assertEquals(listOf(SettingsPage.GITHUB), matchingSettingsPages("commit push"))
    }

    @Test
    fun `prompt editor is a visible settings page reachable by search`() {
        // Regression: the prompt editor shipped as an unreferenced composable
        // because it was never registered as a SettingsPage. The home list is
        // built from SettingsPage.entries, so with no PROMPTS entry the user had
        // no menu item and no navigation path capable of opening it.
        val prompts = matchingSettingsEntries("").firstOrNull { it.page == SettingsPage.PROMPTS }
        assertNotNull(prompts)
        assertTrue(prompts!!.primary)

        // Reachable by the labels the feature is documented under.
        assertTrue(SettingsPage.PROMPTS in matchingSettingsPages("prompt"))
        assertTrue(SettingsPage.PROMPTS in matchingSettingsPages("system prompt"))
        assertEquals(
            SettingsPage.PROMPTS,
            matchingSettingsEntries("system prompt").first { it.title == "System prompt" }.page,
        )
    }

    @Test
    fun `existing chat shortcuts land on their focused settings pages`() {
        assertEquals(SettingsPage.MODELS, settingsDeepLink("planning"))
        assertEquals(SettingsPage.VOICE, settingsDeepLink("voice"))
        assertNull(settingsDeepLink("unknown"))
    }
}
