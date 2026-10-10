package com.androidharness.app.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for context-overflow detection and bounded recovery.
 *
 * Two properties matter more than any single case: a generic provider error
 * must never be mistaken for an overflow (recovering from one would throw away
 * the conversation and then fail again), and recovery must be finite and
 * cancellation-safe.
 */
class ContextOverflowTest {

    // ---- detection ---------------------------------------------------------

    @Test
    fun `openai and openrouter overflow wording is recognised`() {
        assertTrue(
            ContextOverflow.isOverflow(
                """HTTP 400: {"error":{"message":"This model's maximum context length is 128000 tokens. """ +
                    """However, your messages resulted in 200000 tokens.","code":"context_length_exceeded"}}""",
            ),
        )
        assertTrue(ContextOverflow.isOverflow("Please reduce the length of the messages.", 400))
        assertTrue(ContextOverflow.isOverflow("context_length_exceeded"))
    }

    @Test
    fun `anthropic overflow wording is recognised`() {
        assertTrue(
            ContextOverflow.isOverflow("HTTP 400: prompt is too long: 250000 tokens > 200000 maximum", 400),
        )
    }

    @Test
    fun `google overflow wording is recognised`() {
        assertTrue(
            ContextOverflow.isOverflow(
                "HTTP 400: The input token count (1200000) exceeds the maximum number of tokens allowed (1048576).",
                400,
            ),
        )
    }

    @Test
    fun `a rejection that names the context window is recognised`() {
        assertTrue(ContextOverflow.isOverflow("Request exceeds the context window for this model", 400))
    }

    @Test
    fun `generic provider errors are not misclassified`() {
        val generic = listOf(
            """HTTP 400: {"error":{"message":"Invalid parameter: temperature"}}""",
            "HTTP 400: unsupported model `gpt-nope`",
            "HTTP 400: Upstream returned unsupported tool arguments; expected a JSON string or object",
            "HTTP 401: invalid_api_key",
            "HTTP 403: you do not have access to this model",
            "HTTP 429: rate limit exceeded",
            "HTTP 500: internal server error",
            "model_not_found",
            "image omitted: active model does not support image input",
            null,
            "",
        )
        for (message in generic) {
            assertFalse("misclassified as overflow: $message", ContextOverflow.isOverflow(message, 400))
        }
    }

    @Test
    fun `the reported status must be one a context rejection uses`() {
        assertFalse(
            "a 5xx is not a context-length rejection",
            ContextOverflow.isOverflow("the context window was exceeded", 503),
        )
        assertTrue(
            "in-stream provider errors often carry no status at all",
            ContextOverflow.isOverflow("the context window was exceeded", null),
        )
    }

    // ---- bounded recovery --------------------------------------------------

    @Test
    fun `recovery attempts are bounded`() {
        val recovery = OverflowRecovery()
        repeat(ContextOverflow.MAX_RECOVERIES) { assertTrue(recovery.claim()) }
        assertTrue(recovery.exhausted)
        assertFalse("recovery must never be unbounded", recovery.claim())
        assertEquals(ContextOverflow.MAX_RECOVERIES, recovery.attempts)
    }

    @Test
    fun `a non-overflow failure consumes nothing and never retries`() = runBlocking {
        val recovery = OverflowRecovery()
        var exhausted = false
        var retried = false
        val retry = recoverFromContextOverflow(
            failure = "HTTP 429: rate limit exceeded",
            recovery = recovery,
            canCompact = true,
            compactHistory = { error("compaction must not run for a generic error") },
            shrinkWithoutSummary = { error("trimming must not run for a generic error") },
            exhausted = { "nope" },
            onExhausted = { exhausted = true },
            onRetrying = { retried = true },
        )
        assertFalse(retry)
        assertFalse(exhausted)
        assertFalse(retried)
        assertEquals(0, recovery.attempts)
    }

    @Test
    fun `an overflow that compacts successfully is retried`() = runBlocking {
        val recovery = OverflowRecovery()
        var attemptsSeen = 0
        var exhausted = false
        val retry = recoverFromContextOverflow(
            failure = "prompt is too long: 250000 tokens > 200000 maximum",
            recovery = recovery,
            canCompact = true,
            compactHistory = { true },
            shrinkWithoutSummary = { error("trimming must not run when compaction worked") },
            exhausted = { "nope" },
            onExhausted = { exhausted = true },
            onRetrying = { attemptsSeen = it },
        )
        assertTrue(retry)
        assertFalse(exhausted)
        assertEquals(1, attemptsSeen)
        assertEquals(1, recovery.attempts)
    }

    @Test
    fun `an overflow that cannot be compacted falls back to a hard trim`() = runBlocking {
        val retry = recoverFromContextOverflow(
            failure = "context_length_exceeded",
            recovery = OverflowRecovery(),
            canCompact = false,
            compactHistory = { error("compaction was not offered") },
            shrinkWithoutSummary = { true },
            exhausted = { "nope" },
            onExhausted = { },
            onRetrying = { },
        )
        assertTrue(retry)
    }

    @Test
    fun `a history that cannot shrink ends the run with an actionable message`() = runBlocking {
        var message: String? = null
        val retry = recoverFromContextOverflow(
            failure = "context_length_exceeded",
            recovery = OverflowRecovery(),
            canCompact = true,
            compactHistory = { false },
            shrinkWithoutSummary = { false },
            exhausted = { "Context limit reached: start a new chat." },
            onExhausted = { message = it },
            onRetrying = { error("an impossible request must not be retried") },
        )
        assertFalse(retry)
        assertEquals("Context limit reached: start a new chat.", message)
    }

    @Test
    fun `an exhausted attempt budget ends the run instead of retrying`() = runBlocking {
        val recovery = OverflowRecovery(maxAttempts = 1)
        assertTrue(
            recoverFromContextOverflow(
                "context_length_exceeded", recovery, true, { true }, { false }, { "m" }, { }, { },
            ),
        )
        var message: String? = null
        val retry = recoverFromContextOverflow(
            "context_length_exceeded", recovery, true, { true }, { false }, { "budget spent" },
            { message = it }, { error("must not retry past the bound") },
        )
        assertFalse(retry)
        assertEquals("budget spent", message)
    }

    @Test
    fun `cancellation interrupts recovery and is never converted into a retry`() = runBlocking {
        val recovery = OverflowRecovery()
        var retried = false
        var thrown: Throwable? = null
        try {
            recoverFromContextOverflow(
                failure = "context_length_exceeded",
                recovery = recovery,
                canCompact = true,
                compactHistory = { throw CancellationException("user stopped the run") },
                shrinkWithoutSummary = { error("must not run after cancellation") },
                exhausted = { "nope" },
                onExhausted = { },
                onRetrying = { retried = true },
            )
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("cancellation must propagate", thrown is CancellationException)
        assertFalse("a cancelled run must not be retried", retried)
    }

    @Test
    fun `the exhausted message names the budget so the user can act`() {
        val budget = ContextBudget.resolve(configuredLimitTokens = 8_192, maxOutputTokens = 32_768)
        val message = ContextOverflow.exhaustedMessage(budget, estimateTokens = 200_000)
        assertTrue(message.contains("8"))
        assertTrue("a window too small for its own reservations says so", message.contains("too small"))

        val roomy = ContextBudget.resolve(configuredLimitTokens = 200_000, maxOutputTokens = 8_192)
        assertTrue(ContextOverflow.exhaustedMessage(roomy, 199_000).contains("Start a new chat"))
    }

    // ---- compaction loop prevention ----------------------------------------

    @Test
    fun `compaction of an unchanged history is attempted at most once`() {
        val guard = CompactionGuard()
        val history = listOf(ChatMessageFixtures.user("hello"))
        assertTrue(guard.shouldAttempt(history))
        guard.record(history)
        assertFalse("compacting the same history again would loop", guard.shouldAttempt(history))
    }

    @Test
    fun `compaction is allowed again once the history moves on, but stays bounded`() {
        val guard = CompactionGuard()
        var history = listOf(ChatMessageFixtures.user("hello"))
        var allowed = 0
        repeat(10) {
            if (guard.shouldAttempt(history)) {
                allowed++
                guard.record(history)
            }
            // The run keeps appending, so every attempt sees new history.
            history = history + ChatMessageFixtures.assistant("worked")
        }
        assertEquals(CompactionGuard.MAX_COMPACTIONS_PER_RUN, allowed)
    }

    @Test
    fun `reset clears both the count and the fingerprint`() {
        val guard = CompactionGuard()
        val history = listOf(ChatMessageFixtures.user("hello"))
        while (guard.shouldAttempt(history)) guard.record(history)
        assertFalse(guard.shouldAttempt(history))
        guard.reset()
        assertEquals(0, guard.attempts)
        assertTrue(guard.shouldAttempt(history))
    }
}
