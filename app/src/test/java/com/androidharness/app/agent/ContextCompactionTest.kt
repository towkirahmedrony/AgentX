package com.androidharness.app.agent

import com.androidharness.app.agent.ChatMessageFixtures.assistant
import com.androidharness.app.agent.ChatMessageFixtures.assistantCalls
import com.androidharness.app.agent.ChatMessageFixtures.schema
import com.androidharness.app.agent.ChatMessageFixtures.summary
import com.androidharness.app.agent.ChatMessageFixtures.tool
import com.androidharness.app.agent.ChatMessageFixtures.user
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for when compaction fires, what it keeps, and that it can
 * never loop or strand a tool call.
 *
 * All fixtures are deterministic and offline; no provider is called.
 */
class ContextCompactionTest {

    // 20_000 window - 2_000 output - 2_000 safety margin (10%) = 16_000 for input.
    private val budget = ContextBudget.resolve(configuredLimitTokens = 20_000, maxOutputTokens = 2_000)

    private fun estimated(
        history: List<ChatMessage>,
        systemPrompt: String = "",
        tools: List<com.androidharness.app.llm.ToolSchema> = emptyList(),
    ): Int = ContextBudget.accounting(history, systemPrompt, tools).inputTokens

    private fun noOrphan(history: List<ChatMessage>): Boolean {
        var awaiting: Set<String> = emptySet()
        for (m in history) {
            when (m.role) {
                Role.TOOL -> {
                    val id = m.toolCallId ?: return false
                    if (id !in awaiting) return false
                    awaiting = awaiting - id
                }
                Role.ASSISTANT ->
                    awaiting = if (m.toolCalls.isEmpty()) emptySet() else m.toolCalls.mapTo(HashSet()) { it.id }
                else -> awaiting = emptySet()
            }
        }
        return true
    }

    // ---- the trigger -------------------------------------------------------

    @Test
    fun `compaction triggers before the request can exceed the configured window`() {
        assertEquals(16_000, budget.usableInputTokens)

        val oversized = listOf(user("x".repeat(60_000)))
        assertTrue("an over-budget request must trigger compaction", budget.exceeds(estimated(oversized)))

        val fitting = listOf(user("x".repeat(1_000)))
        assertFalse("a fitting request must not trigger compaction", budget.exceeds(estimated(fitting)))
    }

    @Test
    fun `the trigger counts the system prompt and the tool schemas, not just the history`() {
        val history = listOf(user("x".repeat(1_000)))
        assertFalse(budget.exceeds(estimated(history)))

        // The same history, with the prompt the engine actually sends.
        val prompt = "p".repeat(60_000)
        assertTrue(
            "a large system prompt must count towards the budget",
            budget.exceeds(estimated(history, systemPrompt = prompt)),
        )

        val manyTools = (0 until 40).map { schema("tool_$it", "d".repeat(2_000)) }
        assertTrue(
            "the exposed tool schemas must count towards the budget",
            budget.exceeds(estimated(history, tools = manyTools)),
        )
    }

    @Test
    fun `a large tool result is what pushes a request over`() {
        val small = listOf(user("go"), assistantCalls("c1"), tool("c1", "ok"), user("latest"))
        val large = listOf(user("go"), assistantCalls("c1"), tool("c1", "z".repeat(80_000)), user("latest"))
        assertFalse(budget.exceeds(estimated(small)))
        assertTrue("large tool output must be visible to the trigger", budget.exceeds(estimated(large)))
    }

    @Test
    fun `different windows move the trigger point`() {
        val history = listOf(user("x".repeat(30_000))) // ~8_300 estimated tokens
        val small = ContextBudget.resolve(configuredLimitTokens = 8_192, maxOutputTokens = 2_000)
        val large = ContextBudget.resolve(configuredLimitTokens = 200_000, maxOutputTokens = 2_000)

        assertTrue("a small window must compact this", small.exceeds(estimated(history)))
        assertFalse("a large window must not", large.exceeds(estimated(history)))
    }

    @Test
    fun `a model catalog window lowers the trigger point`() {
        // ~139k tokens: fits a 1M setting, does not fit the model's real 128k.
        val history = listOf(user("x".repeat(500_000)))
        val configuredOnly = ContextBudget.resolve(configuredLimitTokens = 1_000_000, maxOutputTokens = 8_192)
        val bounded = ContextBudget.resolve(
            configuredLimitTokens = 1_000_000,
            knownModelTokens = 128_000,
            maxOutputTokens = 8_192,
        )
        assertEquals(1_000_000, configuredOnly.limitTokens)
        assertEquals("the model's own window bounds the setting", 128_000, bounded.limitTokens)
        assertTrue(bounded.usableInputTokens < configuredOnly.usableInputTokens)
        assertFalse("the generous setting would happily send this", configuredOnly.exceeds(estimated(history)))
        assertTrue("the real window must compact first", bounded.exceeds(estimated(history)))
    }

    @Test
    fun `an unknown window still yields a usable, bounded budget`() {
        val fallback = ContextBudget.resolve(configuredLimitTokens = 0, maxOutputTokens = 32_768)
        assertFalse(fallback.limitKnown)
        assertEquals(ContextBudget.FALLBACK_LIMIT_TOKENS, fallback.limitTokens)
        assertTrue(fallback.usableInputTokens > 0)
        assertFalse("an unknown window is never treated as unlimited", fallback.limitTokens > 1_000_000)
    }

    // ---- what compaction keeps ---------------------------------------------

    @Test
    fun `the newest user request survives compaction verbatim`() {
        val history = buildList {
            repeat(20) { add(user("old turn $it " + "x".repeat(500))) }
            add(assistant("done"))
            add(user("THE REQUEST I AM ANSWERING"))
        }
        val (older, recent) = ContextHygiene.compactionSplit(history)

        assertTrue("there must be something to summarize", older.isNotEmpty())
        assertTrue("the newest request must be kept, not summarized", recent.contains(history.last()))
        assertEquals(history.last(), recent.first())
        assertFalse("the newest request must never be summarized away", older.contains(history.last()))
    }

    @Test
    fun `a split landing inside a tool group keeps the declaring call`() {
        // 12 messages, keep-8 puts the boundary on the third tool result: the
        // slice must walk back to the assistant call that declared it.
        val ids = (1..10).map { "c$it" }
        val history = buildList {
            add(user("old goal"))
            add(assistantCalls(*ids.toTypedArray()))
            ids.forEach { add(tool(it, "r$it")) }
        }
        val (older, recent) = ContextHygiene.compactionSplit(history)

        assertEquals("the boundary must land on the declaring call", assistantCalls(*ids.toTypedArray()), recent.first())
        assertFalse("a kept slice must never start on a tool result", recent.first().role == Role.TOOL)
        assertFalse("the summarized half must not end on a tool result", older.last().role == Role.TOOL)
        assertTrue(noOrphan(recent))
    }

    @Test
    fun `the compacted conversation is a valid provider history`() {
        val history = buildList {
            repeat(12) { add(user("turn $it " + "y".repeat(300))) }
            add(assistantCalls("c1"))
            add(tool("c1", "result"))
            add(user("latest"))
        }
        val (older, recent) = ContextHygiene.compactionSplit(history)
        val compacted = ContextHygiene.sanitizeToolPairing(listOf(summary("folded earlier conversation")) + recent)
        val summaryBack = ContextHygiene.sanitizeToolPairing(listOf(summary("folded earlier conversation")) + ContextHygiene.emergencyTrim(older, 50) + recent)

        assertTrue(noOrphan(compacted))
        assertTrue(noOrphan(summaryBack))
        assertEquals(history.last(), compacted.last())
        assertTrue("the summary is the head of the compacted history", compacted.first().text.startsWith(AgentEngine.COMPACTION_PREFIX))
    }

    // ---- trimming ----------------------------------------------------------

    @Test
    fun `trimming protects the folded summary instead of dropping it first`() {
        val history = buildList {
            add(summary("the run's goal and decisions " + "s".repeat(2_000)))
            repeat(10) { add(user("turn $it " + "x".repeat(2_000))) }
            add(user("latest"))
        }
        val trimmed = ContextHygiene.trimHistory(history, maxContextTokens = 20_000, reservedTokens = 19_000)

        assertTrue("the folded memory must survive", trimmed.first().text.startsWith(AgentEngine.COMPACTION_PREFIX))
        assertTrue("the newest request must survive", trimmed.contains(history.last()))
    }

    @Test
    fun `emergency trim keeps the newest request and squeezes oversized tool output`() {
        val history = listOf(
            user("THE REQUEST I AM ANSWERING"),
            assistantCalls("c1"),
            tool("c1", "z".repeat(100_000)),
        )
        val trimmed = ContextHygiene.emergencyTrim(history, budgetTokens = 1_000)

        assertTrue("the request must survive", trimmed.contains(history[0]))
        assertTrue("the tool pair must stay valid", noOrphan(trimmed))
        assertTrue(
            "an impossible request must still be shrunk as far as it goes",
            ContextHygiene.totalTokens(trimmed) <= 1_000,
        )
    }

    @Test
    fun `emergency trim never drops the declaring call of a retained result`() {
        val history = listOf(
            user("go"),
            assistantCalls("c1", "c2"),
            tool("c1", "a".repeat(20_000)),
            tool("c2", "b".repeat(20_000)),
            user("latest"),
        )
        val trimmed = ContextHygiene.emergencyTrim(history, budgetTokens = 500)
        assertTrue(noOrphan(trimmed))
        val declared = trimmed.filter { it.role == Role.ASSISTANT }.flatMap { it.toolCalls }.map { it.id }.toSet()
        trimmed.filter { it.role == Role.TOOL }.forEach { assertTrue(it.toolCallId in declared) }
    }

    @Test
    fun `a history far larger than the window converges without churning`() {
        val history = buildList {
            add(user("start"))
            repeat(60) {
                add(assistantCalls("c$it"))
                add(tool("c$it", "o".repeat(5_000)))
                if (it % 5 == 0) add(user("turn $it"))
            }
            add(user("latest"))
        }
        val first = ContextHygiene.trimToBudget(history, budgetTokens = 2_000)
        val second = ContextHygiene.trimToBudget(first, budgetTokens = 2_000)

        assertEquals("trimming an already-trimmed history must be a no-op", first.size, second.size)
        assertEquals(first, second)
        assertTrue(noOrphan(first))
        assertEquals("the newest request is never sacrificed", history.last(), first.last())
    }

    @Test
    fun `trimming cannot be defeated by a huge newest turn`() {
        val history = listOf(
            user("start"),
            assistant("acknowledged"),
            user("here is an enormous paste " + "p".repeat(200_000)),
        )
        val trimmed = ContextHygiene.trimToBudget(history, budgetTokens = 2_000)
        assertTrue("the newest turn is kept even when it alone exceeds the budget", trimmed.contains(history.last()))
        assertTrue("everything else is dropped", trimmed.size == 1)
    }

    @Test
    fun `protected instructions are never paid for out of the history budget`() {
        val tools = listOf(schema("shell", "run a command"))
        val roomy = ContextBudget.historyBudgetTokens(budget, systemPrompt = "", tools = tools)
        val crowded = ContextBudget.historyBudgetTokens(budget, systemPrompt = "i".repeat(40_000), tools = tools)
        assertTrue("the prompt is always reserved, never squeezed out", crowded < roomy)
        assertTrue(crowded >= 0)
    }
}
