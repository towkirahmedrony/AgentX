package com.androidharness.app.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The automatic (AgentEngine) and manual (/compact in ChatViewModel) paths used
 * to carry their own copy of this text. Consolidating it must not change the
 * wording, so pin it here.
 */
class CompactionPromptTest {
    @Test
    fun `compaction instruction matches the historical wording`() {
        assertEquals(
            "Summarize this coding-agent conversation compactly. Preserve: the user's goal, " +
                "files created/modified and their paths, key decisions, pending work and next steps. " +
                "Output plain notes only.",
            CompactionPrompt.SUMMARY_INSTRUCTION,
        )
    }
}
