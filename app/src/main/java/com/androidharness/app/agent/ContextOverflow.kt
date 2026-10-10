package com.androidharness.app.agent

/**
 * Recognises a provider's *context window exceeded* error, and only that.
 *
 * This classifier is deliberately narrow. A generic HTTP 400 means "your
 * request was wrong", which includes a bad parameter, an unsupported model, a
 * malformed tool schema or a rejected image; recovering from those by trimming
 * history would destroy the conversation for no reason and then fail again.
 * So a match requires a phrase that only a context-length rejection uses. Every
 * pattern here is either unambiguous on its own ("context_length_exceeded",
 * "prompt is too long") or a pair whose two halves must appear together.
 *
 * The HTTP status, when the transport reported one, must also be a status a
 * context rejection actually uses. In-stream provider errors frequently carry
 * no status at all (`code == null`), which is accepted.
 */
object ContextOverflow {

    /** Recovery attempts allowed per run. Bounded so a run can never spin. */
    const val MAX_RECOVERIES = 2

    /** Statuses a context-length rejection is reported under. */
    private val OVERFLOW_STATUSES = setOf(400, 413, 422)

    /** Phrases that can only mean the input did not fit. */
    private val DEFINITIVE = listOf(
        "context_length_exceeded",
        "context length exceeded",
        "maximum context length",
        "max context length",
        "prompt is too long",
        "input is too long",
        "exceeds the maximum number of tokens",
        "maximum number of input tokens",
        "reduce the length of the messages",
        "please reduce the length",
        "too many tokens",
    )

    /**
     * Pairs where each half is innocuous alone but together describe an
     * overflow. "context window" alone could describe a capability question;
     * paired with a rejection verb it cannot. Google's phrasing is the
     * motivating case for the second family: "The input token count (N)
     * exceeds the maximum number of tokens allowed".
     */
    private val PAIRS = listOf(
        "context window" to "exceed",
        "context window" to "too long",
        "context window" to "limit",
        "input token count" to "exceed",
        "token count" to "exceed",
    )

    /**
     * True when [message] describes a context-window rejection.
     *
     * @param code the transport or provider status, when one was reported.
     */
    fun isOverflow(message: String?, code: Int? = null): Boolean {
        if (message.isNullOrBlank()) return false
        if (code != null && code !in OVERFLOW_STATUSES) return false
        val lower = message.lowercase()
        if (DEFINITIVE.any { it in lower }) return true
        return PAIRS.any { (first, second) -> first in lower && second in lower }
    }

    /**
     * Human-readable, actionable text for a run that could not be recovered.
     * Names the budget that was exceeded so the user can act on it instead of
     * seeing a raw provider payload.
     */
    fun exhaustedMessage(budget: ContextBudget.Budget, estimateTokens: Int): String {
        val limit = budget.limitTokens
        val base = "Context limit reached: the request needs about $estimateTokens tokens but only " +
            "${budget.usableInputTokens} of the $limit-token window is available for input " +
            "(${budget.reservedOutputTokens} reserved for the reply, ${budget.safetyMarginTokens} safety margin)."
        return if (budget.exhaustedByReservations) {
            "$base The configured window is too small to hold that reservation; raise the max context window " +
                "or lower the reply length, or the request cannot be sent."
        } else {
            "$base The conversation could not be shortened any further without dropping your request. " +
                "Start a new chat, or raise the max context window in Settings > Agent behavior."
        }
    }
}

/**
 * One bounded step of context-overflow recovery.
 *
 * Pure of any provider or UI: the caller supplies how to shrink the history and
 * what to do with the outcome, which is what makes the contract testable
 * without a paid API call. The contract is:
 *
 *  - nothing happens for a failure that is not a context overflow;
 *  - the attempt budget is consumed first, so recovery can never be unbounded;
 *  - a retry happens only when the history actually got smaller, because
 *    re-issuing an identical oversized request would fail identically;
 *  - a cancellation raised by a shrink step propagates untouched —
 *    [compactHistory] is suspend and nothing here catches
 *    [kotlinx.coroutines.CancellationException].
 *
 * @return true when the caller may re-issue the request.
 */
internal suspend fun recoverFromContextOverflow(
    failure: String?,
    recovery: OverflowRecovery,
    canCompact: Boolean,
    compactHistory: suspend () -> Boolean,
    shrinkWithoutSummary: () -> Boolean,
    exhausted: () -> String,
    onExhausted: suspend (String) -> Unit,
    onRetrying: suspend (Int) -> Unit,
): Boolean {
    if (!ContextOverflow.isOverflow(failure)) return false
    // Recovery is not a way around the run's own budget: the compaction it
    // performs costs a provider request of its own, so the budget is checked
    // before that request is issued rather than after.
    kotlin.coroutines.coroutineContext[TaskBudget]?.check()
    if (!recovery.claim()) {
        onExhausted(exhausted())
        return false
    }
    var shrunk = false
    if (canCompact) shrunk = compactHistory()
    if (!shrunk) shrunk = shrinkWithoutSummary()
    if (!shrunk) {
        onExhausted(exhausted())
        return false
    }
    onRetrying(recovery.attempts)
    return true
}

/**
 * Bounded retry counter for context-overflow recovery.
 *
 * Recovery re-issues a request only after the history actually got smaller, and
 * only this many times per run. Without the bound, a provider that keeps
 * rejecting a request the app believes now fits would be retried forever.
 */
class OverflowRecovery(private val maxAttempts: Int = ContextOverflow.MAX_RECOVERIES) {

    var attempts: Int = 0
        private set

    val exhausted: Boolean get() = attempts >= maxAttempts

    /** Consumes one attempt; false when the budget is spent. */
    fun claim(): Boolean {
        if (exhausted) return false
        attempts++
        return true
    }
}

/**
 * Loop prevention for compaction.
 *
 * Compaction is only worth attempting when it can change the outcome. Two
 * conditions are required: attempts remain, and the history is not the same one
 * that already failed. The second rule is what stops the classic loop, a run
 * that compacts, still over-estimates, compacts the identical history again,
 * and burns every request on the same summary.
 *
 * The state is per run and deliberately cheap: [signature] hashes message
 * identity and sizes only, never image payloads, because it is evaluated on the
 * hot path.
 */
class CompactionGuard(private val maxAttempts: Int = MAX_COMPACTIONS_PER_RUN) {

    companion object {
        /** Compaction attempts allowed per run, including overflow-driven ones. */
        const val MAX_COMPACTIONS_PER_RUN = 3
    }

    var attempts: Int = 0
        private set

    private var lastSignature: Long = 0L

    /**
     * True when compacting could plausibly change the estimate: an attempt is
     * still available, and either this is the first attempt or the history has
     * moved on since the last one.
     */
    fun shouldAttempt(history: List<com.androidharness.app.core.ChatMessage>): Boolean {
        if (attempts >= maxAttempts) return false
        if (attempts == 0) return true
        return signature(history) != lastSignature
    }

    /** Records one attempt against the history it was made on. */
    fun record(history: List<com.androidharness.app.core.ChatMessage>) {
        attempts++
        lastSignature = signature(history)
    }

    fun reset() {
        attempts = 0
        lastSignature = 0L
    }

    /**
     * Cheap, order-sensitive fingerprint: how many messages, their roles, and
     * their text/tool/image *sizes*. Two histories with the same fingerprint
     * compact to the same thing, which is exactly the loop condition.
     */
    private fun signature(history: List<com.androidharness.app.core.ChatMessage>): Long {
        var hash = 1_125_899_906_842_597L // FNV offset basis
        for (message in history) {
            var part = message.role.ordinal.toLong()
            part = part * 31 + message.text.length
            part = part * 31 + message.toolCalls.size
            part = part * 31 + message.imageData.size
            part = part * 31 + (message.toolCallId?.length ?: 0)
            hash = (hash xor part) * 1_099_511_628_211L // FNV prime
        }
        return hash * 31 + history.size
    }
}
