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

    /** Tool-result caps tried, in order, when a request still will not fit. */
    private val EMERGENCY_TOOL_CAPS = intArrayOf(4_000, 1_000, 200)

    /** Most recent messages compaction keeps verbatim next to the summary. */
    const val COMPACT_KEEP_RECENT = 8

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
     * Estimated tokens one message occupies in the model-facing request. Uses
     * the same accounting as [ContextBudget] so the coarse trim and the budget
     * that triggers compaction can never disagree about the size of the same
     * history. Images contribute their modelled cost, never their base64
     * length: a 1 MB payload is ~1.4 M characters but a few thousand tokens.
     */
    fun accountedTokens(message: ChatMessage): Int =
        ContextBudget.estimateTextTokens(message.text) +
            message.toolCalls.sumOf { ContextBudget.estimateToolCallTokens(it) } +
            (message.toolCallId?.let { ContextBudget.estimateTextTokens(it) } ?: 0) +
            message.imageData.sumOf { ContextBudget.estimateImageTokens(it) } +
            ContextBudget.MESSAGE_FRAMING_TOKENS

    /** Estimated tokens a whole history occupies, same accounting as above. */
    fun totalTokens(history: List<ChatMessage>): Int = history.sumOf { accountedTokens(it) }

    /**
     * Trim old history to a token budget while keeping the retained suffix a
     * valid provider conversation. Unlike a plain `drop(n)`, this never splits
     * an assistant tool-call message from its results:
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
     *
     * @param reservedTokens everything the request has already spent besides the
     *   history: the provider's output room, the safety margin, the system
     *   prompt, the tool schemas and the request framing. There is deliberately
     *   no flat default — a fixed guess for components the caller can measure
     *   is the defect this replaced. Callers that cannot measure it must say so
     *   by passing the value explicitly.
     */
    fun trimHistory(
        history: List<ChatMessage>,
        maxContextTokens: Int,
        reservedTokens: Int,
    ): List<ChatMessage> = trimToBudget(history, maxContextTokens - reservedTokens)

    /**
     * [trimHistory] against an already-computed token budget, for callers that
     * hold the budget directly (the summarizer request, for one, only has to
     * fit its own output room and framing).
     */
    fun trimToBudget(
        history: List<ChatMessage>,
        budgetTokens: Int,
    ): List<ChatMessage> {
        // Never trim past the most recent user turn: the model always needs the
        // request it is answering. Messages before it are fair game.
        val lastUser = history.indexOfLast { it.role == Role.USER }
        val floor = if (lastUser >= 0) lastUser else 0
        // A leading compaction summary is the folded memory of everything that
        // came before this point: dropping it would silently discard the only
        // record of the run's goal and decisions, so it is never trimmed away.
        val protectedPrefix = if (history.firstOrNull()?.let {
                it.role == Role.SYSTEM && it.text.startsWith(AgentEngine.COMPACTION_PREFIX)
            } == true
        ) 1 else 0
        if (budgetTokens <= 0) {
            val tail = history.drop(maxOf(floor, protectedPrefix))
            val combined = history.take(protectedPrefix) + tail
            return sanitizeToolPairing(combined.ifEmpty { history.takeLast(1) })
        }
        var total = totalTokens(history)
        if (total <= budgetTokens) return sanitizeToolPairing(history)

        var cut = protectedPrefix
        while (total > budgetTokens && cut < floor) {
            val removed = history[cut++]
            total -= accountedTokens(removed)
            // Removing an assistant tool call orphans its results; drop them
            // too so the call/result pair is never split.
            if (removed.role == Role.ASSISTANT && removed.toolCalls.isNotEmpty()) {
                while (cut < floor && history[cut].role == Role.TOOL) {
                    total -= accountedTokens(history[cut++])
                }
            }
        }
        // Defensive: a malformed or boundary-orphaned result can still sit at
        // the cut, so drop any leading result that has no retained call.
        while (cut < floor && history[cut].role == Role.TOOL) {
            total -= accountedTokens(history[cut++])
        }
        return sanitizeToolPairing(history.take(protectedPrefix) + history.drop(cut))
    }

    /**
     * Chooses what compaction summarizes and what it keeps verbatim.
     *
     * Two rules, both required for a usable result:
     *
     *  - the kept slice never begins on a tool result, because its declaring
     *    assistant call would be summarized away and the pair split;
     *  - the most recent user message always lands in the kept slice, so the
     *    request being answered survives compaction verbatim and the model can
     *    still act on it.
     *
     * Pure and allocation-light: unit-testable without a provider.
     */
    fun compactionSplit(
        history: List<ChatMessage>,
        keepRecent: Int = COMPACT_KEEP_RECENT,
    ): Pair<List<ChatMessage>, List<ChatMessage>> {
        if (history.isEmpty()) return emptyList<ChatMessage>() to emptyList()
        val lastUser = history.indexOfLast { it.role == Role.USER }
        var start = maxOf(history.size - keepRecent, if (lastUser >= 0) lastUser else 0)
        // Walk back off any leading tool result to its declaring call.
        while (start > 0 && history[start].role == Role.TOOL) start--
        if (start <= 0) return emptyList<ChatMessage>() to history
        return history.subList(0, start) to history.subList(start, history.size)
    }

    /**
     * Last resort for a request that still will not fit after compaction.
     *
     * Drops everything before the newest user turn, then truncates tool results
     * with progressively harsher caps. A run that cannot fit even then has a
     * single oversized turn (or a window too small for its own reservations)
     * and must fail with an actionable message rather than retry the same
     * impossible request — so this returns its best effort and lets the caller
     * decide, it never loops.
     */
    fun emergencyTrim(history: List<ChatMessage>, budgetTokens: Int): List<ChatMessage> {
        val lastUser = history.indexOfLast { it.role == Role.USER }
        val floor = if (lastUser >= 0) lastUser else 0
        // Same protection as [trimToBudget]: the folded summary of everything
        // already compacted is the run's memory and is never thrown away to
        // make room.
        val protectedPrefix = if (history.firstOrNull()?.let {
                it.role == Role.SYSTEM && it.text.startsWith(AgentEngine.COMPACTION_PREFIX)
            } == true
        ) 1 else 0
        // The newest user turn is always kept, so the slice starts at whichever
        // of the two floors comes later.
        val split = maxOf(floor, protectedPrefix)
        var out = sanitizeToolPairing(history.take(protectedPrefix) + history.drop(split))
        for (cap in EMERGENCY_TOOL_CAPS) {
            if (totalTokens(out) <= budgetTokens) return out
            out = out.map { m ->
                if (m.role != Role.TOOL || m.text.length <= cap) m else m.copy(text = truncate(m.text, cap))
            }
        }
        return out
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
