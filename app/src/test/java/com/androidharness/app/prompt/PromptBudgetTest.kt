package com.androidharness.app.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetTest {

    private fun localLinux(
        skillsIndex: String? = null,
        agentsMd: String? = null,
    ) = PromptFacts(
        workspaceKind = WorkspaceKind.SHELL_READY,
        workspacePath = "/work",
        workspaceRoot = "/work",
        skillsIndex = skillsIndex,
        agentsMd = agentsMd,
    )

    @Test
    fun `breakdown separates skills and workspace from the system prompt`() {
        val composed = PromptComposer.compose(
            localLinux(skillsIndex = "  repo:\n    - git: Repos.\n", agentsMd = "Build with gradle."),
        )
        val breakdown = PromptBudget.of(composed, toolsChars = 4000, messagesChars = 400)

        // Skills/workspace tokens are a SUBSET of the system prompt, not extra.
        assertTrue(breakdown.skillsTokens > 0)
        assertTrue(breakdown.workspaceTokens > 0)
        assertTrue(breakdown.systemTokens >= breakdown.skillsTokens + breakdown.workspaceTokens)
        assertEquals(1000, breakdown.toolsTokens)
        assertEquals(100, breakdown.messagesTokens)
        assertEquals(
            breakdown.systemTokens + breakdown.toolsTokens + breakdown.messagesTokens + breakdown.metaTokens,
            breakdown.total,
        )
    }

    @Test
    fun `empty workspace docs contribute nothing`() {
        val breakdown = PromptBudget.of(PromptComposer.compose(localLinux()), toolsChars = 0, messagesChars = 0)
        assertEquals(0, breakdown.skillsTokens)
        assertEquals(0, breakdown.workspaceTokens)
        assertEquals(0, breakdown.toolsTokens)
        assertEquals(0, breakdown.messagesTokens)
    }

    @Test
    fun `counts are always labelled as estimates`() {
        val breakdown = PromptBudget.of(PromptComposer.compose(localLinux()), toolsChars = 0, messagesChars = 0)
        assertTrue(breakdown.estimated)
        assertTrue(PromptBudget.ESTIMATED)
    }
}
