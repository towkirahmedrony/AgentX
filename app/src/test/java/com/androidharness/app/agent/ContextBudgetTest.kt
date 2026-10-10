package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageData
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Regression coverage for the unified context budget: which request components
 * are counted, how the window is resolved, and that nothing is ever presented
 * as exact when it is only an estimate.
 *
 * Everything here is deterministic and offline: no provider, no paid call, no
 * Android API (image headers are built by hand and base64-encoded in-process).
 */
class ContextBudgetTest {

    // ---- helpers -----------------------------------------------------------

    private fun user(text: String) = ChatMessage(role = Role.USER, text = text)

    private fun tool(text: String) = ChatMessage(role = Role.TOOL, text = text, toolCallId = "c1", toolName = "shell")

    private fun toolSchema(name: String, description: String) = ToolSchema(
        name = name,
        description = description,
        parametersJson = JsonObject(emptyMap()),
    )

    private fun png(width: Int, height: Int, paddingBytes: Int = 0): ImageData {
        val bytes = ByteArray(24 + paddingBytes)
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        signature.copyInto(bytes, 0)
        bytes[12] = 'I'.code.toByte(); bytes[13] = 'H'.code.toByte()
        bytes[14] = 'D'.code.toByte(); bytes[15] = 'R'.code.toByte()
        putBe(bytes, 16, width)
        putBe(bytes, 20, height)
        return ImageData("image/png", Base64.getEncoder().encodeToString(bytes))
    }

    private fun jpeg(width: Int, height: Int): ImageData {
        val bytes = ByteArray(32)
        bytes[0] = 0xFF.toByte(); bytes[1] = 0xD8.toByte()
        // APP0 segment: marker at 2, length 16, payload 14 -> next marker at 20.
        bytes[2] = 0xFF.toByte(); bytes[3] = 0xE0.toByte()
        putBe(bytes, 4, 16)
        // SOF0: marker at 20, length at 22, precision at 24, height 25, width 27.
        bytes[20] = 0xFF.toByte(); bytes[21] = 0xC0.toByte()
        putBe(bytes, 22, 17)
        bytes[24] = 8
        bytes[25] = ((height shr 8) and 0xFF).toByte()
        bytes[26] = (height and 0xFF).toByte()
        bytes[27] = ((width shr 8) and 0xFF).toByte()
        bytes[28] = (width and 0xFF).toByte()
        return ImageData("image/jpeg", Base64.getEncoder().encodeToString(bytes))
    }

    private fun gif(width: Int, height: Int): ImageData {
        val bytes = ByteArray(16)
        "GIF89a".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        bytes[6] = (width and 0xFF).toByte(); bytes[7] = ((width shr 8) and 0xFF).toByte()
        bytes[8] = (height and 0xFF).toByte(); bytes[9] = ((height shr 8) and 0xFF).toByte()
        return ImageData("image/gif", Base64.getEncoder().encodeToString(bytes))
    }

    private fun unknownFormat(payloadBytes: Int): ImageData =
        ImageData("image/heic", Base64.getEncoder().encodeToString(ByteArray(payloadBytes) { 7 }))

    private fun putBe(target: ByteArray, at: Int, value: Int) {
        target[at] = ((value shr 24) and 0xFF).toByte()
        target[at + 1] = ((value shr 16) and 0xFF).toByte()
        target[at + 2] = ((value shr 8) and 0xFF).toByte()
        target[at + 3] = (value and 0xFF).toByte()
    }

    // ---- counted components ------------------------------------------------

    @Test
    fun `system prompt and tool schemas are counted components of the request`() {
        val history = listOf(user("hello"))
        val withoutTools = ContextBudget.accounting(history, systemPrompt = "", tools = emptyList())
        val withPrompt = ContextBudget.accounting(history, systemPrompt = "x".repeat(4_000), tools = emptyList())
        val withTools = ContextBudget.accounting(history, systemPrompt = "", tools = listOf(toolSchema("shell", "run")))

        assertTrue("a system prompt must add tokens", withPrompt.systemTokens > 0)
        assertTrue(withPrompt.inputTokens > withoutTools.inputTokens)
        assertTrue("tool schemas must add tokens", withTools.toolsTokens > 0)
        assertTrue(withTools.inputTokens > withoutTools.inputTokens)
        assertEquals(
            withoutTools.systemTokens + withoutTools.toolsTokens +
                withoutTools.messagesTokens + withoutTools.imagesTokens + withoutTools.overheadTokens,
            withoutTools.inputTokens,
        )
    }

    @Test
    fun `a bigger system prompt leaves less room for history`() {
        val budget = ContextBudget.resolve(configuredLimitTokens = 128_000, maxOutputTokens = 8_192)
        val tools = listOf(toolSchema("shell", "run a command"))
        val small = ContextBudget.historyBudgetTokens(budget, "short prompt", tools)
        val large = ContextBudget.historyBudgetTokens(budget, "p".repeat(20_000), tools)
        val noTools = ContextBudget.historyBudgetTokens(budget, "short prompt", emptyList())

        assertTrue("a longer prompt must shrink the history budget", large < small)
        assertTrue("tool schemas must shrink the history budget", noTools > small)
        // The history allowance is the usable input minus the fixed request
        // framing; with no prompt and no tools nothing else is taken out.
        assertEquals(
            budget.usableInputTokens,
            ContextBudget.historyBudgetTokens(budget, "", emptyList()) + ContextBudget.REQUEST_OVERHEAD_TOKENS,
        )
    }

    @Test
    fun `large tool results raise the estimate`() {
        val small = ContextHygiene.totalTokens(listOf(tool("ok")))
        val large = ContextHygiene.totalTokens(listOf(tool("z".repeat(40_000))))
        assertTrue("a 40k-char tool result must dominate a 2-char one", large > small * 100)
        // Same accounting as the budget, so trim and trigger cannot disagree.
        assertEquals(small, ContextBudget.estimateMessagesTokens(listOf(tool("ok"))))
    }

    @Test
    fun `non-ascii text is estimated more conservatively than ascii of the same length`() {
        val ascii = ContextBudget.estimateTextTokens("a".repeat(400))
        val cjk = ContextBudget.estimateTextTokens("\u4f60".repeat(400))
        assertTrue("CJK must not be estimated at the ASCII rate", cjk > ascii * 2)
    }

    @Test
    fun `replayed provider reasoning on a tool call is counted`() {
        val plain = ToolCallData(id = "c1", name = "shell", argumentsJson = "{}")
        val withReasoning = plain.copy(
            responseReasoning = listOf(JsonObject(mapOf("summary" to kotlinx.serialization.json.JsonPrimitive("r".repeat(2_000))))),
        )
        assertTrue(
            "reasoning replayed for stateless providers is part of the request",
            ContextBudget.estimateToolCallTokens(withReasoning) > ContextBudget.estimateToolCallTokens(plain),
        )
    }

    // ---- images ------------------------------------------------------------

    @Test
    fun `image cost comes from the dimensions, not the base64 length`() {
        val compact = png(1_024, 1_024, paddingBytes = 0)
        val padded = png(1_024, 1_024, paddingBytes = 200_000)
        assertEquals(
            "padding the same picture must not change its token cost",
            ContextBudget.estimateImageTokens(compact),
            ContextBudget.estimateImageTokens(padded),
        )
        assertTrue(
            "a 1.4M-character payload is a few thousand tokens, not hundreds of thousands",
            ContextBudget.estimateImageTokens(padded) < 4_000,
        )
    }

    @Test
    fun `a larger image costs more than a smaller one`() {
        assertTrue(ContextBudget.estimateImageTokens(png(1_024, 1_024)) > ContextBudget.estimateImageTokens(png(256, 256)))
    }

    @Test
    fun `jpeg and gif dimensions are read from their headers`() {
        assertEquals(ContextBudget.estimateImageTokens(png(800, 600)), ContextBudget.estimateImageTokens(jpeg(800, 600)))
        assertTrue(ContextBudget.estimateImageTokens(gif(800, 600)) > 0)
    }

    @Test
    fun `an unreadable image falls back to the conservative ceiling`() {
        assertEquals(
            ContextBudget.IMAGE_TOKEN_CEILING,
            ContextBudget.estimateImageTokens(unknownFormat(4_000)),
        )
    }

    @Test
    fun `images are accounted for in the history estimate`() {
        val withImage = ChatMessage(role = Role.USER, text = "look", imageData = listOf(png(1_024, 1_024)))
        val without = ChatMessage(role = Role.USER, text = "look")
        assertTrue(
            ContextHygiene.accountedTokens(withImage) > ContextHygiene.accountedTokens(without),
        )
        assertTrue(ContextBudget.estimateImagesTokens(listOf(withImage)) > 0)
    }

    // ---- limits ------------------------------------------------------------

    @Test
    fun `a known model window bounds the configured one`() {
        assertEquals(128_000 to true, ContextBudget.resolveLimit(1_000_000, 128_000))
        assertEquals(64_000 to true, ContextBudget.resolveLimit(64_000, 128_000))
        assertEquals(128_000 to true, ContextBudget.resolveLimit(1_000_000, 128_000))
    }

    @Test
    fun `an unknown limit uses the documented fallback instead of assuming unlimited`() {
        val (limit, known) = ContextBudget.resolveLimit(configuredTokens = 0, knownModelTokens = null)
        assertEquals(ContextBudget.FALLBACK_LIMIT_TOKENS, limit)
        assertFalse(known)
        // A nonsense configured value is treated the same way, never as a free pass.
        val (negative, negativeKnown) = ContextBudget.resolveLimit(configuredTokens = -1, knownModelTokens = null)
        assertEquals(ContextBudget.FALLBACK_LIMIT_TOKENS, negative)
        assertFalse(negativeKnown)
    }

    @Test
    fun `a configured window alone is honoured when no model metadata exists`() {
        assertEquals(200_000 to true, ContextBudget.resolveLimit(200_000, null))
    }

    @Test
    fun `different providers get different output reservations`() {
        val anthropic = ContextBudget.resolveOutputReservation(8_192, ThinkingLevel.HIGH, ProviderType.ANTHROPIC)
        val openAi = ContextBudget.resolveOutputReservation(8_192, ThinkingLevel.HIGH, ProviderType.OPENAI_COMPAT)
        assertEquals("Anthropic raises max_tokens for a thinking budget", 16_384 + ContextBudget.OUTPUT_BUDGET_HEADROOM, anthropic)
        assertEquals("effort-style providers keep the configured cap", 8_192, openAi)
        assertEquals(
            "Gemini is a budget family too",
            anthropic,
            ContextBudget.resolveOutputReservation(8_192, ThinkingLevel.HIGH, ProviderType.GEMINI),
        )
    }

    @Test
    fun `the safety margin is a bounded share of the window`() {
        assertEquals(
            ContextBudget.MIN_SAFETY_MARGIN_TOKENS,
            ContextBudget.resolveSafetyMarginTokens(8_192, ContextBudget.DEFAULT_SAFETY_MARGIN_PERCENT),
        )
        assertEquals(20_000, ContextBudget.resolveSafetyMarginTokens(200_000, 10))
        assertEquals(
            "a huge window must not reserve tens of thousands",
            ContextBudget.MAX_SAFETY_MARGIN_TOKENS,
            ContextBudget.resolveSafetyMarginTokens(2_000_000, 10),
        )
    }

    @Test
    fun `usable input never collapses and a too-small window is reported`() {
        val tiny = ContextBudget.resolve(configuredLimitTokens = 8_192, maxOutputTokens = 32_768)
        assertEquals(ContextBudget.MIN_USABLE_INPUT_TOKENS, tiny.usableInputTokens)
        assertTrue("the window cannot hold its own reservations", tiny.exhaustedByReservations)

        val roomy = ContextBudget.resolve(configuredLimitTokens = 200_000, maxOutputTokens = 8_192)
        assertEquals(200_000, roomy.limitTokens)
        assertTrue(roomy.usableInputTokens > 150_000)
        assertFalse(roomy.exhaustedByReservations)
    }

    @Test
    fun `the resolved budget exposes its own arithmetic`() {
        val budget = ContextBudget.resolve(200_000, null, 8_192, ThinkingLevel.OFF, ProviderType.OPENAI_COMPAT, 10)
        assertEquals(20_000, budget.safetyMarginTokens)
        assertEquals(8_192, budget.reservedOutputTokens)
        assertEquals(200_000 - 8_192 - 20_000, budget.usableInputTokens)
        assertTrue(budget.exceeds(budget.usableInputTokens + 1))
        assertFalse(budget.exceeds(budget.usableInputTokens))
    }

    // ---- labelling ---------------------------------------------------------

    @Test
    fun `estimates are labelled approximate and never exact`() {
        val accounting = ContextBudget.accounting(listOf(user("hi")), "prompt", emptyList())
        assertFalse("the app has no tokenizer; nothing here is exact", accounting.exact)

        val estimate = ContextEstimate(
            messagesTokens = accounting.messagesTokens,
            systemTokens = accounting.systemTokens,
            toolsTokens = accounting.toolsTokens,
            imagesTokens = accounting.imagesTokens,
            metaTokens = accounting.overheadTokens,
        )
        assertTrue(estimate.approximate)
        assertEquals(accounting.inputTokens, estimate.total)
    }
}
