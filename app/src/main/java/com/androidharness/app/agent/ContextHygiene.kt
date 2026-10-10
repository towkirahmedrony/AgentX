package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role

/**
 * Keeps the model-facing history small without lying that the user said the
 * summary. Old tool dumps are truncated in the *working* copy only; the DB
 * still holds the full output for the UI.
 */
object ContextHygiene {

    /** Most recent tool results stay verbatim so the model can still act on them. */
    const val RECENT_FULL_TOOLS = 6

    const val STALE_TOOL_CHARS = 4_000

    /**
     * Maximum number of recent images to keep in model context across the
     * whole conversation (keeps only the 2 most recent images in context,
     * while user can attach freely).
     */
    const val MAX_CONTEXT_IMAGES = 2

    fun shrinkToolResults(
        history: List<ChatMessage>,
        recentFull: Int = RECENT_FULL_TOOLS,
        maxChars: Int = STALE_TOOL_CHARS,
        maxImages: Int = MAX_CONTEXT_IMAGES,
    ): List<ChatMessage> {
        val toolIndices = history.indices.filter { history[it].role == Role.TOOL }
        val keepFull = toolIndices.takeLast(recentFull).toSet()

        // Count all images across the conversation from newest to oldest
        var keptImages = 0
        val withEvictedImages = history.asReversed().map { m ->
            if (m.imageData.isEmpty()) {
                m
            } else {
                val availableQuota = (maxImages - keptImages).coerceAtLeast(0)
                if (availableQuota >= m.imageData.size) {
                    keptImages += m.imageData.size
                    m
                } else if (availableQuota > 0) {
                    val keptSlice = m.imageData.takeLast(availableQuota)
                    keptImages += availableQuota
                    m.copy(imageData = keptSlice)
                } else {
                    // All image quota exhausted: strip base64 payloads for older turns
                    m.copy(imageData = emptyList())
                }
            }
        }.asReversed()

        return withEvictedImages.mapIndexed { i, m ->
            if (m.role != Role.TOOL || i in keepFull || m.text.length <= maxChars) m
            else m.copy(text = truncate(m.text, maxChars))
        }
    }

    /**
     * Enforces the provider-agnostic tool call/result invariant on [history]:
     *
     *  - a TOOL result is kept only when the assistant message that declared
     *    its tool call is present earlier in the same result group;
     *  - duplicate results for one tool-call id are dropped;
     *  - a TOOL result with no id, or whose id was never declared, is dropped.
     *
     * Only orphaned/malformed results are removed, never assistant messages:
     * a tool call whose result is missing is an interrupted-run state that
     * [RunRecovery.missingResults] closes explicitly, so dropping the call
     * here would discard the model's intent instead of repairing the history.
     * Because this only deletes unanswerable results, it can never introduce a
     * new dangling call while repairing an orphaned result.
     */
    fun sanitizeToolPairing(history: List<ChatMessage>): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(history.size)
        // Tool-call ids declared by the most recent assistant message and still
        // awaiting a result. A result consumes its id, so repeats are dropped.
        var awaiting: Set<String> = emptySet()
        for (m in history) {
            when (m.role) {
                Role.TOOL -> {
                    val id = m.toolCallId
                    if (id != null && id in awaiting) {
                        awaiting = awaiting - id
                        out += m
                    }
                    // else: orphan, duplicate or malformed result, dropped.
                }
                Role.ASSISTANT -> {
                    out += m
                    awaiting = if (m.toolCalls.isEmpty()) emptySet()
                    else m.toolCalls.mapTo(HashSet()) { it.id }
                }
                // A user/system message closes any still-open tool-call group,
                // so results after it no longer answer a retained call.
                else -> {
                    out += m
                    awaiting = emptySet()
                }
            }
        }
        return out
    }

    /**
     * Trim old history to a character budget while keeping the retained suffix
     * a valid provider conversation. Unlike a plain `drop(n)`, this never
     * splits an assistant tool-call message from its results:
     *
     *  - removing an assistant message that declares tool calls also removes
     *    the tool results that immediately follow it;
     *  - the retained suffix never begins with an orphaned tool result, and any
     *    result that lost its declaring call is dropped;
     *  - the most recent user message (and everything after it) is preserved as
     *    the floor, so the model always keeps the request it is answering.
     *
     * Auto-compaction still owns the real context budget; this is the coarse
     * first pass and may return an over-budget suffix rather than an invalid
     * one when the newest user turn alone exceeds the budget.
     */
    fun trimHistory(
        history: List<ChatMessage>,
        maxContextTokens: Int,
        maxOutputTokens: Int,
    ): List<ChatMessage> {
        // reserve space for system prompt, tool schemas and the model's reply
        val budgetChars = (maxContextTokens - maxOutputTokens - 8_192) * 4
        // Never trim past the most recent user turn: the model always needs the
        // request it is answering. Messages before it are fair game.
        val lastUser = history.indexOfLast { it.role == Role.USER }
        val floor = if (lastUser >= 0) lastUser else 0
        if (budgetChars <= 0) {
            val tail = history.drop(floor)
            return sanitizeToolPairing(tail.ifEmpty { history.takeLast(1) })
        }
        var total = history.sumOf { it.text.length }
        if (total <= budgetChars) return sanitizeToolPairing(history)

        var cut = 0
        while (total > budgetChars && cut < floor) {
            val removed = history[cut++]
            total -= removed.text.length
            // Removing an assistant tool call orphans its results; drop them
            // too so the call/result pair is never split.
            if (removed.role == Role.ASSISTANT && removed.toolCalls.isNotEmpty()) {
                while (cut < floor && history[cut].role == Role.TOOL) {
                    total -= history[cut++].text.length
                }
            }
        }
        // Defensive: a malformed or boundary-orphaned result can still sit at
        // the cut, so drop any leading result that has no retained call.
        while (cut < floor && history[cut].role == Role.TOOL) {
            total -= history[cut++].text.length
        }
        return sanitizeToolPairing(history.drop(cut))
    }

    /**
     * Strips all base64 image data from messages while inserting a text notice
     * so text-only models understand an image was previously present without
     * receiving unsupported binary payloads.
     */
    fun stripImages(
        messages: List<ChatMessage>,
        note: String = "[Attached image omitted: active model does not support image input]",
    ): List<ChatMessage> = messages.map { m ->
        if (m.imageData.isEmpty()) {
            m
        } else {
            val names = m.images.joinToString(", ") { it.name }.ifEmpty { "image" }
            val placeholder = "$note ($names)"
            val newText = if (m.text.isNotBlank()) "${m.text}\n\n$placeholder" else placeholder
            m.copy(text = newText, imageData = emptyList())
        }
    }

    fun summaryMessage(summary: String): ChatMessage = ChatMessage(
        role = Role.SYSTEM,
        text = "${AgentEngine.COMPACTION_PREFIX}\n\n$summary",
    )

    /**
     * Marker prefix of the user-visible compaction notice line; the summary
     * itself stays hidden, this tells the user the transcript was folded.
     */
    const val COMPACTION_NOTICE_PREFIX = "[Context compacted:"

    fun compactionNotice(): ChatMessage = ChatMessage(
        role = Role.SYSTEM,
        text = "$COMPACTION_NOTICE_PREFIX older messages were summarized into the hidden note above]",
    )

    /**
     * Model-facing slice: last compaction summary plus everything after it,
     * with stale tool dumps truncated. The UI still sees the full transcript.
     */
    fun forModel(history: List<ChatMessage>): List<ChatMessage> {
        val lastSummary = history.indexOfLast {
            it.role == Role.SYSTEM && it.text.startsWith(AgentEngine.COMPACTION_PREFIX)
        }
        val sliced = if (lastSummary >= 0) history.subList(lastSummary, history.size) else history
        return shrinkToolResults(sliced)
    }

    private fun truncate(text: String, maxChars: Int): String {
        val marker = "\n[truncated ${text.length - maxChars} chars of tool output]\n"
        val keep = ((maxChars - marker.length) / 2).coerceAtLeast(64)
        return text.take(keep) + marker + text.takeLast(keep)
    }
}
