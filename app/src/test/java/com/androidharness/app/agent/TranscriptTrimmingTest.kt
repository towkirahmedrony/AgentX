package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression + property coverage for the P0-1 defect: history trimming used to
 * remove an assistant message that declared tool calls while keeping the tool
 * results that answered them, producing a history every provider rejects
 * ("tool result without a preceding tool call").
 *
 * These tests validate the message *relationships* and ordering, never just
 * that the history got shorter.
 */
class TranscriptTrimmingTest {

    // ---- helpers -----------------------------------------------------------

    private fun user(text: String = "u") = ChatMessage(role = Role.USER, text = text)

    private fun assistantText(text: String = "a") = ChatMessage(role = Role.ASSISTANT, text = text)

    private fun assistantCalls(vararg ids: String) = ChatMessage(
        role = Role.ASSISTANT,
        text = "",
        toolCalls = ids.map { ToolCallData(id = it, name = "shell", argumentsJson = "{}") },
    )

    private fun tool(id: String, text: String = "result", isError: Boolean = false) = ChatMessage(
        role = Role.TOOL,
        text = text,
        toolCallId = id,
        toolName = "shell",
        isError = isError,
    )

    private fun toolIds(history: List<ChatMessage>): List<String> =
        history.filter { it.role == Role.TOOL }.mapNotNull { it.toolCallId }

    /**
     * The shared provider invariant: a TOOL result is reachable only from an
     * assistant tool-call message retained before it. Missing results for an
     * assistant call are an interrupted-run state handled by RunRecovery and
     * are deliberately not flagged here.
     */
    private fun noOrphanToolResult(history: List<ChatMessage>): Boolean {
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

    /** Mirror of what the OpenAI-compatible/Responses serializers require. */
    private fun openAiCompatSerializationValid(history: List<ChatMessage>): Boolean {
        val declared = LinkedHashSet<String>()
        for (m in history) {
            when (m.role) {
                Role.ASSISTANT -> {
                    if (m.toolCalls.isNotEmpty()) {
                        declared.clear()
                        m.toolCalls.forEach { declared += it.id }
                    } else {
                        declared.clear()
                    }
                }
                Role.TOOL -> {
                    val id = m.toolCallId ?: return false
                    if (id !in declared) return false
                    declared -= id
                }
                else -> declared.clear()
            }
        }
        return true
    }

    private fun isSubsequence(sub: List<ChatMessage>, full: List<ChatMessage>): Boolean {
        var i = 0
        for (m in full) {
            if (i < sub.size && sub[i] === m) i++
        }
        return i == sub.size
    }

    // 10_000 context - 1_000 output - 8_192 reserve = 3_232 char budget
    private val tokens = 10_000
    private val outputTokens = 1_000

    // ---- 1. valid pair is kept --------------------------------------------

    @Test
    fun `assistant tool call and its result are preserved when no trim is needed`() {
        val history = listOf(user("run"), assistantCalls("c1"), tool("c1", "ok"))
        val out = ContextHygiene.trimHistory(history, tokens, outputTokens)
        assertEquals(history.size, out.size)
        assertTrue(noOrphanToolResult(out))
        assertEquals(listOf("c1"), toolIds(out))
    }

    // ---- 2. the original defect -------------------------------------------

    @Test
    fun `removing the declaring assistant message does not leave its result orphaned`() {
        // The old implementation stopped at `kept.size > 2` after dropping the
        // tool call and returned [toolResult, latestUser] -> orphaned result.
        val history = listOf(
            user("old goal"),
            assistantCalls("c1"),
            tool("c1", "x".repeat(4_000)),
            user("latest"),
        )
        val out = ContextHygiene.trimHistory(history, tokens, outputTokens)
        assertTrue("result outlived its declaring call: $out", noOrphanToolResult(out))
        assertTrue(toolIds(out).isEmpty())
        assertEquals(user("latest"), out.last())
    }

    // ---- 3. multiple calls, only some results exist ------------------------

    @Test
    fun `partial results for a multi-call assistant message stay consistent`() {
        // Call retained: the one real result must stay and the missing one must
        // not invent a result or orphan anything.
        val retained = ContextHygiene.trimHistory(
            listOf(user("x".repeat(5_000)), assistantCalls("c1", "c2"), tool("c1", "r1"), user("latest")),
            tokens, outputTokens,
        )
        assertTrue(noOrphanToolResult(retained))
        assertEquals(listOf("c1"), toolIds(retained))

        // Call trimmed away: no result may survive.
        val trimmed = ContextHygiene.trimHistory(
            listOf(user("go"), assistantCalls("c1", "c2"), tool("c1", "x".repeat(4_000)), user("latest")),
            tokens, outputTokens,
        )
        assertTrue(noOrphanToolResult(trimmed))
        assertTrue(toolIds(trimmed).isEmpty())
    }

    // ---- 4. trimming boundary between a call and its results ---------------

    @Test
    fun `a boundary landing between a tool call and its results keeps the pair whole`() {
        // Removing the assistant call must also remove the following results.
        val dropped = ContextHygiene.trimHistory(
            listOf(user("go"), assistantCalls("c1"), tool("c1", "x".repeat(4_000)), user("latest")),
            tokens, outputTokens,
        )
        assertTrue(noOrphanToolResult(dropped))
        assertTrue("group was split: $dropped", toolIds(dropped).isEmpty())

        // A boundary before the call keeps the call and all its results.
        val kept = ContextHygiene.trimHistory(
            listOf(user("x".repeat(5_000)), assistantCalls("c1"), tool("c1", "r"), user("latest")),
            tokens, outputTokens,
        )
        assertTrue(noOrphanToolResult(kept))
        assertEquals(listOf("c1"), toolIds(kept))
        assertTrue(kept.any { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() })
    }

    // ---- 5. several groups in one history ----------------------------------

    @Test
    fun `multiple tool-call groups are never split`() {
        val history = listOf(
            user("go"),
            assistantCalls("c1"), tool("c1", "a".repeat(3_000)),
            assistantCalls("c2"), tool("c2", "b".repeat(3_000)),
            assistantCalls("c3"), tool("c3", "c"),
            user("latest"),
        )
        val out = ContextHygiene.trimHistory(history, tokens, outputTokens)
        assertTrue(noOrphanToolResult(out))
        // Every retained result has its retained call, and no group is partial.
        val callIds = out.filter { it.role == Role.ASSISTANT }.flatMap { it.toolCalls }.map { it.id }.toSet()
        out.filter { it.role == Role.TOOL }.forEach { assertTrue(it.toolCallId in callIds) }
        assertFalse(toolIds(out).contains("c1")) // oldest group dropped whole
        assertTrue(toolIds(out).contains("c3"))
    }

    // ---- 6. a large tool result triggers trimming --------------------------

    @Test
    fun `a large tool result triggers trimming and the result stays valid`() {
        val history = listOf(
            user("go"),
            assistantCalls("big"), tool("big", "z".repeat(8_000)),
            user("latest"),
        )
        val out = ContextHygiene.trimHistory(history, tokens, outputTokens)
        assertTrue("expected a shorter history", out.size < history.size)
        assertTrue(noOrphanToolResult(out))
    }

    // ---- 7. ordinary conversations without tool calls ----------------------

    @Test
    fun `conversations without tool calls remain valid and trim from the front`() {
        val history = listOf(
            user("x".repeat(5_000)), assistantText("ack"), user("second"), assistantText("done"), user("latest"),
        )
        val out = ContextHygiene.trimHistory(history, tokens, outputTokens)
        assertTrue(noOrphanToolResult(out))
        assertEquals(assistantText("ack"), out.first())
        assertEquals(user("latest"), out.last())
    }

    // ---- 8. repeated trimming never invalidates ----------------------------

    @Test
    fun `repeated trimming never produces an orphaned result`() {
        var current = listOf(
            user("go"),
            assistantCalls("c1"), tool("c1", "a".repeat(3_000)),
            assistantCalls("c2"), tool("c2", "b".repeat(3_000)),
            assistantCalls("c3"), tool("c3", "c".repeat(3_000)),
            user("latest"),
        )
        listOf(60_000, 20_000, 10_000, 10_000, 5_000).forEach { context ->
            current = ContextHygiene.trimHistory(current, context, outputTokens)
            assertTrue("invalid after trimming at $context: $current", noOrphanToolResult(current))
            assertTrue(openAiCompatSerializationValid(current))
        }
    }

    // ---- 9. a valid history stays valid ------------------------------------

    @Test
    fun `a valid history remains valid across every budget`() {
        val history = listOf(
            user("go"),
            assistantCalls("c1", "c2"), tool("c1", "r1"), tool("c2", "r2"),
            assistantText("thinking"), user("more"), assistantCalls("c3"), tool("c3", "r3"),
            user("latest"),
        )
        listOf(1, 2_000, 9_000, 10_000, 50_000, 200_000).forEach { context ->
            val out = ContextHygiene.trimHistory(history, context, outputTokens)
            assertTrue("invalid at $context: $out", noOrphanToolResult(out))
            assertTrue(isSubsequence(out, history))
        }
    }

    // ---- 10. interrupted / recovered tool calls ----------------------------

    @Test
    fun `recovery results keep their existing meaning when their call is retained`() {
        val synthetic = tool(
            "c1",
            "Interrupted: outcome unknown. Inspect the current state before taking another action. Do not blindly repeat this operation.",
            isError = true,
        )
        val history = listOf(user("x".repeat(5_000)), assistantCalls("c1"), synthetic, user("latest"))
        val out = ContextHygiene.trimHistory(history, tokens, outputTokens)
        val keptResult = out.first { it.role == Role.TOOL }
        assertEquals("c1", keptResult.toolCallId)
        assertTrue(keptResult.isError)
        assertTrue(keptResult.text.startsWith("Interrupted: outcome unknown"))
        assertTrue(noOrphanToolResult(out))
    }

    @Test
    fun `recovery results are dropped with their call, not left orphaned`() {
        val history = listOf(
            user("go"),
            assistantCalls("c1"),
            tool("c1", "x".repeat(4_000), isError = true),
            user("latest"),
        )
        val out = ContextHygiene.trimHistory(history, tokens, outputTokens)
        assertTrue(noOrphanToolResult(out))
        assertTrue(toolIds(out).isEmpty())
    }

    // ---- 11. provider serialization never sees an orphan -------------------

    @Test
    fun `trimmed output passes provider-style serialization validation`() {
        val history = listOf(
            user("go"),
            assistantCalls("c1"), tool("c1", "a".repeat(3_000)),
            assistantCalls("c2"), tool("c2", "b".repeat(3_000)),
            user("latest"),
        )
        listOf(1, 5_000, 10_000, 40_000).forEach { context ->
            val out = ContextHygiene.trimHistory(history, context, outputTokens)
            assertTrue("orphan reached serialization at $context: $out", openAiCompatSerializationValid(out))
        }
    }

    // ---- 12. malformed / duplicate results are handled safely --------------

    @Test
    fun `duplicate orphan and malformed results are dropped safely`() {
        val history = listOf(
            user("go"),
            tool("stray", "no declaring call"),          // orphan before any call
            assistantCalls("c1"),
            tool("c1", "first"),
            tool("c1", "duplicate"),                       // duplicate
            tool("never-declared", "malformed"),           // id never declared here
            user("latest"),
            tool("late-stray", "after a user message"),    // orphan after group closed
        )
        val out = ContextHygiene.sanitizeToolPairing(history)
        assertTrue(noOrphanToolResult(out))
        assertEquals(listOf("c1"), toolIds(out))
        assertEquals("first", out.first { it.role == Role.TOOL }.text)
        assertTrue(isSubsequence(out, history))
    }

    @Test
    fun `sanitize never drops an assistant tool call`() {
        val history = listOf(user("go"), assistantCalls("c1"), user("latest"))
        val out = ContextHygiene.sanitizeToolPairing(history)
        assertEquals(history, out)
    }

    // ---- property-style coverage over deterministic histories --------------

    @Test
    fun `property - trimming never orphans a result, reorders history, or drops the latest user`() {
        val rng = java.util.Random(20261010L)
        repeat(500) { iter ->
            val history = randomHistory(rng)
            val contextTokens = 1_000 + rng.nextInt(45_000)
            val out = ContextHygiene.trimHistory(history, contextTokens, 500)
            assertTrue("iter $iter: orphan result after trim: $out", noOrphanToolResult(out))
            assertTrue("iter $iter: provider validation failed: $out", openAiCompatSerializationValid(out))
            assertTrue("iter $iter: message order changed", isSubsequence(out, history))
            val lastUser = history.indexOfLast { it.role == Role.USER }
            if (lastUser >= 0) {
                assertTrue("iter $iter: latest user message dropped", out.contains(history[lastUser]))
            }
        }
    }

    private fun randomHistory(rng: java.util.Random): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        out += user("start-${rng.nextInt(1_000)}")
        val groups = 1 + rng.nextInt(6)
        for (g in 0 until groups) {
            if (rng.nextInt(100) < 40) out += user("turn-$g-" + "x".repeat(rng.nextInt(2_000)))
            val callCount = 1 + rng.nextInt(3)
            val ids = (0 until callCount).map { "c$g-$it" }
            out += assistantCalls(*ids.toTypedArray())
            for (id in ids) {
                val roll = rng.nextInt(100)
                if (roll < 15) continue // missing result (interrupted call)
                out += tool(id, "r".repeat(rng.nextInt(1_500)))
                if (roll >= 90) out += tool(id, "dup") // duplicate result
            }
            if (rng.nextInt(100) < 20) out += assistantText("note-$g")
        }
        out += user("latest-${rng.nextInt(1_000)}")
        if (rng.nextInt(100) < 20) {
            out.add(rng.nextInt(out.size), tool("orphan-${rng.nextInt(1_000)}", "stray"))
        }
        return out
    }
}
