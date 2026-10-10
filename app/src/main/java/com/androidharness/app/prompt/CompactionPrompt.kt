package com.androidharness.app.prompt

/**
 * Single source of truth for the conversation-compaction instruction.
 *
 * The automatic compaction inside [com.androidharness.app.agent.AgentEngine]
 * and the manual `/compact` path in
 * [com.androidharness.app.ui.chat.ChatViewModel] used to carry their own copy of
 * this text. Both now read it from here so the behavior stays identical and the
 * wording changes in exactly one place.
 */
object CompactionPrompt {
    const val SUMMARY_INSTRUCTION: String =
        "Summarize this coding-agent conversation compactly. Preserve: the user's goal, " +
            "files created/modified and their paths, key decisions, pending work and next steps. " +
            "Output plain notes only."
}
