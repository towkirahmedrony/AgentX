package com.androidharness.app.prompt

/**
 * Token accounting for the prompt preview and developer logging.
 *
 * These counts are ESTIMATES. They use the same `chars / 4` heuristic as the
 * rest of the app, so a developer can compare shapes quickly; the authoritative
 * number is the provider's own usage report, surfaced on the stream `Usage`
 * event (inputTokens/outputTokens/cachedInputTokens). Never present [Breakdown]
 * numbers as exact model usage.
 */
object PromptBudget {

    /** True for every value this object produces: they are estimates. */
    const val ESTIMATED = true

    data class Breakdown(
        /** Whole assembled system prompt (includes skills + workspace docs). */
        val systemTokens: Int,
        /** The Skills block alone (part of [systemTokens]). */
        val skillsTokens: Int,
        /** AGENTS.md / memory / repo map / todos block (part of [systemTokens]). */
        val workspaceTokens: Int,
        /** Serialized tool-schema JSON sent with the request. */
        val toolsTokens: Int,
        /** Conversation history. */
        val messagesTokens: Int,
        /** Per-request framing the provider adds (role markers, etc.). */
        val metaTokens: Int = 256,
    ) {
        val total: Int get() = systemTokens + toolsTokens + messagesTokens + metaTokens

        /** Always true: this breakdown is an estimate, not provider usage. */
        val estimated: Boolean get() = PromptBudget.ESTIMATED
    }

    fun of(
        composed: PromptComposer.ComposedPrompt,
        toolsChars: Int,
        messagesChars: Int,
        metaTokens: Int = 256,
    ): Breakdown = Breakdown(
        systemTokens = composed.estimatedTokens,
        skillsTokens = scopeChars(composed, PromptScope.SKILLS) / 4,
        workspaceTokens = scopeChars(composed, PromptScope.WORKSPACE) / 4,
        toolsTokens = toolsChars / 4,
        messagesTokens = messagesChars / 4,
        metaTokens = metaTokens,
    )

    private fun scopeChars(composed: PromptComposer.ComposedPrompt, scope: PromptScope): Int =
        composed.sections.filter { it.active && it.scope == scope }.sumOf { it.charCount }
}
