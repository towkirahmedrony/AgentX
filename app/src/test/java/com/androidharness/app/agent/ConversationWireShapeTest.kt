package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageData
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.local.LocalChatCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the conversation shape actually transmitted.
 *
 * The reported failure is HTTP 500 from an OpenAI-compatible server running the
 * model's chat template:
 *
 *   "After the optional system message, conversation roles must alternate user
 *    and assistant roles except for tool calls and results."
 *
 * This asserts the invariant that made it happen and must never happen again:
 * the transmitted list carries no system turn, begins with a user turn, and
 * alternates user/assistant with tool calls and results intact. It also covers
 * the image cases, because an image is what pushed the conversation into the
 * state that exposed it.
 *
 * Deterministic and offline: no provider, no paid call, no Android API.
 */
class ConversationWireShapeTest {

    private val photo = ImageData("image/jpeg", "cGhvdG8=")
    private val photoRef = ImageRef("photo.jpg", "image/jpeg")

    private fun user(text: String = "u", images: List<ImageRef> = emptyList(), data: List<ImageData> = emptyList()) =
        ChatMessage(role = Role.USER, text = text, images = images, imageData = data)

    private fun assistant(text: String = "a", calls: List<ToolCallData> = emptyList()) =
        ChatMessage(role = Role.ASSISTANT, text = text, toolCalls = calls)

    private fun tool(id: String, text: String = "ok") =
        ChatMessage(role = Role.TOOL, text = text, toolCallId = id, toolName = "shell")

    private fun wire(history: List<ChatMessage>, prompt: String = "SYSTEM PROMPT") =
        ContextHygiene.toWireShape(history, prompt)

    /**
     * The rule the failing template enforces, encoded so a regression cannot
     * pass silently: no system turn, an opening user turn, and only the
     * documented neighbours for each role.
     */
    private fun assertTransmittable(messages: List<ChatMessage>) {
        assertTrue(
            "an OpenAI-style chat template cannot render a system turn inside the list",
            messages.none { it.role == Role.SYSTEM },
        )
        assertEquals("the first turn must be a user turn", Role.USER, messages.first().role)
        for (i in 1 until messages.size) {
            val previous = messages[i - 1].role
            when (messages[i].role) {
                Role.USER -> assertEquals("user must follow assistant or tool", Role.ASSISTANT, previous)
                Role.ASSISTANT -> assertTrue(
                    "assistant must follow user or tool",
                    previous == Role.USER || previous == Role.TOOL,
                )
                Role.TOOL -> assertTrue(
                    "a result follows its call or another result",
                    previous == Role.ASSISTANT || previous == Role.TOOL,
                )
                Role.SYSTEM -> throw AssertionError("system turn in the message list")
            }
        }
    }

    // ---- valid conversations pass through ---------------------------------

    @Test
    fun `a valid text-only conversation is transmitted unchanged`() {
        val history = listOf(user("hello"), assistant("hi"), user("do it"))
        val result = wire(history)
        assertTransmittable(result.messages)
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), result.messages.map { it.role })
        assertEquals(history.map { it.text }, result.messages.map { it.text })
        assertEquals("the caller's system prompt is preserved", "SYSTEM PROMPT", result.system)
    }

    @Test
    fun `a user message carrying text and an image keeps both`() {
        val history = listOf(user("look at this", listOf(photoRef), listOf(photo)))
        val result = wire(history)
        assertTransmittable(result.messages)
        assertEquals(1, result.messages.size)
        assertEquals("look at this", result.messages[0].text)
        assertEquals("the image payload must survive", listOf(photo), result.messages[0].imageData)
        assertTrue(result.messages[0].images.isNotEmpty())
    }

    @Test
    fun `image data is preserved through the local codec serialization`() {
        val history = listOf(user("what is this", listOf(photoRef), listOf(photo)))
        val result = wire(history)
        val payload = Json.parseToJsonElement(
            LocalChatCodec.request("SYS", result.messages, emptyList(), includeAgentContext = true),
        ).jsonObject
        val messages = payload.getValue("messages").jsonArray
        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        val turn = messages[1].jsonObject
        assertEquals("user", turn.getValue("role").jsonPrimitive.content)
        val content = turn.getValue("content").jsonArray
        assertEquals("text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("what is this", content[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(
            "the image must be offered to the model, not dropped",
            "media_marker",
            content[1].jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    // ---- the reported failure --------------------------------------------

    @Test
    fun `a stored compaction summary is folded into the instruction, not sent as a turn`() {
        // The exact stored state that produced the 500: RunManager persists the
        // summary and the notice as SYSTEM rows, and the slice starts at them.
        val history = listOf(
            ContextHygiene.summaryMessage("user asked for a refactor; src/A.kt was edited"),
            ContextHygiene.compactionNotice(),
            user("now add tests"),
        )
        val result = wire(history)

        assertTransmittable(result.messages)
        assertEquals("only the real turn is transmitted", 1, result.messages.size)
        assertTrue(
            "the summary must still reach the model through the instruction",
            result.system.contains("src/A.kt was edited"),
        )
        assertTrue(
            "the compaction notice is for the user, never the model",
            result.messages.none { it.text.contains(ContextHygiene.COMPACTION_NOTICE_PREFIX) } &&
                !result.system.contains(ContextHygiene.COMPACTION_NOTICE_PREFIX),
        )
        assertTrue(result.system.startsWith("SYSTEM PROMPT"))
    }

    @Test
    fun `a failed image request does not poison the next text-only message`() {
        // Stored state after: photo send fails, its user row stays, then "hi".
        val history = listOf(
            user("hello"),
            assistant("hi there"),
            user("what is in this photo", listOf(photoRef), listOf(photo)),
            user("hi"),
        )
        val result = wire(history)

        assertTransmittable(result.messages)
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), result.messages.map { it.role })
        val merged = result.messages.last()
        assertTrue("the new message is present", merged.text.contains("hi"))
        assertTrue("the failed turn is not discarded", merged.text.contains("what is in this photo"))
        assertEquals("its image is not lost", listOf(photo), merged.imageData)
    }

    @Test
    fun `a failed assistant turn followed by a new user message stays transmittable`() {
        // An assistant row that carries nothing (a rejected or empty turn) must
        // not break alternation for the message that follows it.
        val history = listOf(user("first"), assistant(""), user("second"))
        val result = wire(history)
        assertTransmittable(result.messages)
        assertEquals(1, result.messages.size)
        assertTrue(result.messages[0].text.contains("first"))
        assertTrue(result.messages[0].text.contains("second"))
    }

    @Test
    fun `consecutive user messages from an interruption are merged, not dropped`() {
        // resume appends a CONTINUE_PROMPT user row; a cancelled turn leaves a
        // user row. Both are real user content and must survive.
        val history = listOf(
            user("do the thing"),
            user(RunRecovery.CONTINUE_PROMPT),
        )
        val result = wire(history)
        assertTransmittable(result.messages)
        assertEquals(1, result.messages.size)
        assertTrue(result.messages[0].text.contains("do the thing"))
        assertTrue(result.messages[0].text.contains("Resume the interrupted task"))
    }

    @Test
    fun `a history with no user turn is passed through rather than emptied`() {
        // Guard, not a case: every flow opens with the user's turn. Sending an
        // empty message list would fail on its own, so the turns are kept.
        val result = wire(listOf(assistant("only an assistant turn")))
        assertEquals("a request must never be sent with no turns", 1, result.messages.size)
        assertEquals("only an assistant turn", result.messages[0].text)
    }

    @Test
    fun `a history that begins mid-turn drops only the unanswerable prefix`() {
        val history = listOf(
            assistant("working on it", listOf(ToolCallData("c1", "shell", "{}"))),
            tool("c1"),
            user("carry on"),
        )
        val result = wire(history)
        assertTransmittable(result.messages)
        assertEquals(1, result.messages.size)
        assertEquals("carry on", result.messages[0].text)
    }

    // ---- tool integrity ---------------------------------------------------

    @Test
    fun `tool calls and their results stay paired and in order`() {
        val history = listOf(
            user("run it"),
            assistant("", listOf(ToolCallData("c1", "shell", "{}"), ToolCallData("c2", "read_file", "{}"))),
            tool("c1"),
            tool("c2"),
            assistant("done"),
            user("again"),
        )
        val result = wire(history)
        assertTransmittable(result.messages)
        assertEquals(
            listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.TOOL, Role.ASSISTANT, Role.USER),
            result.messages.map { it.role },
        )
        val declared = result.messages.filter { it.role == Role.ASSISTANT }
            .flatMap { it.toolCalls }.map { it.id }
        assertEquals(listOf("c1", "c2"), declared)
        assertEquals(listOf("c1", "c2"), result.messages.filter { it.role == Role.TOOL }.map { it.toolCallId })
    }

    @Test
    fun `a merge can never leave a result without its call`() {
        val history = listOf(
            user("go"),
            assistant("first", listOf(ToolCallData("c1", "shell", "{}"))),
            assistant("", listOf(ToolCallData("c2", "shell", "{}"))),
            tool("c1"),
            tool("c2"),
        )
        val result = wire(history)
        assertTransmittable(result.messages)
        val declared = result.messages.filter { it.role == Role.ASSISTANT }.flatMap { it.toolCalls }.map { it.id }.toSet()
        result.messages.filter { it.role == Role.TOOL }.forEach {
            assertTrue("orphaned result ${it.toolCallId}", it.toolCallId in declared)
        }
    }

    // ---- trimming and compaction -----------------------------------------

    @Test
    fun `trimming and compaction keep the structure transmittable`() {
        val history = buildList {
            add(user("start"))
            repeat(30) {
                add(assistant("step $it", listOf(ToolCallData("c$it", "shell", "{}"))))
                add(tool("c$it", "o".repeat(400)))
            }
            add(user("finish"))
        }
        val trimmed = ContextHygiene.trimToBudget(
            ContextHygiene.forModel(history),
            budgetTokens = 2_000,
        )
        val result = wire(trimmed, "PROMPT")
        assertTransmittable(result.messages)
    }

    @Test
    fun `a compacted conversation is transmittable after the summary is stored`() {
        val (older, recent) = ContextHygiene.compactionSplit(
            buildList {
                repeat(12) { add(user("turn $it")) }
                add(assistant("answer"))
                add(user("latest"))
            },
        )
        assertTrue(older.isNotEmpty())
        val stored = ContextHygiene.sanitizeToolPairing(
            listOf(ContextHygiene.summaryMessage("folded"), ContextHygiene.compactionNotice(), ContextHygiene.compactionNotice()) + recent,
        )
        val result = wire(stored)
        assertTransmittable(result.messages)
        assertTrue(result.system.contains("folded"))
    }

    // ---- image integrity --------------------------------------------------

    @Test
    fun `an image whose payload cannot be loaded is reported, never silently text-only`() {
        // images non-empty but the file went missing: ImageStore.resolve returns
        // null, so withImagesResolved leaves imageData empty.
        val history = listOf(user("what is this", listOf(photoRef), emptyList()))
        val result = wire(history)
        assertTransmittable(result.messages)
        val text = result.messages.single().text
        assertTrue("the model must be told an image was attached", text.contains("could not be loaded"))
        assertTrue(text.contains("photo.jpg"))
        assertTrue("the user's own text is kept", text.contains("what is this"))
    }

    @Test
    fun `the unsupported-model notice is not duplicated by the wire shape`() {
        // The engine strips images for a text-only model; that note already
        // records the attachment, so the wire must not add a second one.
        val stripped = ContextHygiene.stripImages(listOf(user("see this", listOf(photoRef), listOf(photo))))
        val result = wire(stripped)
        assertTransmittable(result.messages)
        val text = result.messages.single().text
        assertEquals("exactly one notice", 1, Regex("\\[Attached image").findAll(text).count())
        assertTrue(text.contains("does not support image input"))
    }

    @Test
    fun `a text-only turn is never downgraded or emptied`() {
        val result = wire(listOf(user("just text")))
        assertEquals("just text", result.messages.single().text)
        assertTrue(result.messages.single().imageData.isEmpty())
    }

    // ---- persistence is untouched ----------------------------------------

    @Test
    fun `repairing the wire shape never rewrites the stored transcript`() {
        val history = listOf(user("a"), user("b"), ContextHygiene.compactionNotice(), user("c"))
        val before = history.toList()
        wire(history)
        assertEquals("the caller's list is not mutated", before, history)
        assertEquals("every stored row is still there", 4, history.size)
        assertTrue("including the notice the UI renders", history.any { it.role == Role.SYSTEM })
    }
}
