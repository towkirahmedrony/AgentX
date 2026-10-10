package com.androidharness.app.agent

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Why a run stopped.
 *
 * The distinction matters because the four outcomes need different treatment: a
 * budget stop is resumable and the user can raise the limit, a provider failure
 * is an error the user may retry, a cancellation is the user's own doing, and a
 * stall means the agent is stuck and should be re-prompted with new information.
 */
enum class StopKind {
    /** The model produced a final answer; nothing to recover. */
    SUCCESS,

    /** The user stopped the run. */
    CANCELLED,

    /** The configured tool-call iteration ceiling was reached. */
    ITERATION_LIMIT,

    /** The configured token ceiling was reached. */
    TOKEN_LIMIT,

    /** The configured cost ceiling was reached, against reliable prices. */
    COST_LIMIT,

    /** The configured active-time ceiling was reached. */
    TIME_LIMIT,

    /** Rounds passed without any successful tool result: the agent is stuck. */
    STALLED,

    /** A provider/transport failure ended the run. */
    PROVIDER_ERROR,

    /** The process died mid-run; state was restored on next launch. */
    INTERRUPTED,
}

/** How trustworthy the token figure behind a decision is. */
enum class UsageStatus {
    /** The provider reported usage for every request charged to this budget. */
    ACTUAL,

    /** No provider reported usage; every charge is a documented estimate. */
    ESTIMATED,

    /** Some requests reported usage, others were estimated. */
    MIXED,

    /** Nothing has been charged yet, so no claim can be made either way. */
    UNKNOWN,
}

/**
 * A structured stop record: what ended the run, and the numbers behind it.
 *
 * Every field is either a measured value or explicitly null. Nothing here is
 * invented to fill a gap, and [usageStatus] travels with the token figures so a
 * reader can never mistake an estimate for provider-exact usage.
 */
data class RunStop(
    val kind: StopKind,
    val message: String,
    val iteration: Int = 0,
    val maxIterations: Int? = null,
    val elapsedMs: Long = 0,
    val timeLimitMs: Long? = null,
    val usageStatus: UsageStatus = UsageStatus.UNKNOWN,
    val retries: Int = 0,
    val subagentBudgetReached: Boolean = false,
) {
    /** True when a configured budget — not a failure — ended the run. */
    val limitReached: Boolean
        get() = kind == StopKind.ITERATION_LIMIT || kind == StopKind.TOKEN_LIMIT ||
            kind == StopKind.COST_LIMIT || kind == StopKind.TIME_LIMIT

    /** True when the run can be resumed with more room. */
    val resumable: Boolean
        get() = limitReached || kind == StopKind.STALLED || kind == StopKind.INTERRUPTED

    /**
     * One line a user can act on, including the numbers that produced it and
     * whether the token figure was reported or estimated.
     */
    fun describe(): String {
        val parts = mutableListOf(message)
        maxIterations?.let { parts += "iterations $iteration/$it" }
        if (elapsedMs > 0) parts += "active ${formatDuration(elapsedMs)}" + (timeLimitMs?.let { " of ${formatDuration(it)}" } ?: "")
        if (retries > 0) parts += "retries $retries"
        if (usageStatus != UsageStatus.UNKNOWN) parts += "tokens ${usageLabel(usageStatus)}"
        if (subagentBudgetReached) parts += "a subagent reached its own budget"
        return parts.joinToString(" · ")
    }

    companion object {
        fun usageLabel(status: UsageStatus): String = when (status) {
            UsageStatus.ACTUAL -> "provider-reported"
            UsageStatus.ESTIMATED -> "estimated"
            UsageStatus.MIXED -> "partly estimated"
            UsageStatus.UNKNOWN -> "unavailable"
        }

        fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
        }
    }
}

/**
 * Semantics of the persisted tool-call iteration ceiling.
 *
 * The three states are deliberately distinct, because conflating them is what
 * let every installation run without an iteration ceiling:
 *
 *  - `null`  — nothing was ever persisted. The setting was never configured, so
 *              the safe documented default applies.
 *  - `0`     — an explicit "Unlimited". A user chose this (the settings picker
 *              offers it by name), so it is honoured forever.
 *  - `n > 0` — a real ceiling.
 *  - `n < 0` — invalid. No code path writes a negative value, so this can only
 *              come from corrupt or hand-edited data; falling back to the
 *              default is safer than silently enabling unlimited.
 */
object ExecutionLimits {

    /** Explicit, user-selectable "Unlimited". */
    const val UNLIMITED = 0

    /**
     * Ceiling applied when the setting was never configured. Chosen to be far
     * above any legitimate coding task (the settings picker offers 25/100/250,
     * and a single turn typically needs a handful of rounds) while still
     * bounding a runaway loop within minutes rather than hours.
     */
    const val DEFAULT_ITERATIONS = 100

    /** Highest value the settings UI and backup validation accept. */
    const val MAX_ITERATIONS_ALLOWED = 10_000

    /** Documented fallback for a missing, negative or absurd value. */
    fun defaultIterations(): Int = DEFAULT_ITERATIONS

    fun isUnlimited(limit: Int): Boolean = limit <= UNLIMITED

    /** True when [iterations] completed rounds have used up [limit]. */
    fun reached(iterations: Int, limit: Int): Boolean = !isUnlimited(limit) && iterations >= limit

    /** The ceiling as a nullable value: null means "no ceiling". */
    fun ceilingOrNull(limit: Int): Int? = if (isUnlimited(limit)) null else limit

    /** Accepts a user-entered value only when it is a usable ceiling. */
    fun sanitizeInput(raw: Int?): Int? = raw?.takeIf { it in 1..MAX_ITERATIONS_ALLOWED }

    /** What the run loop may do before its next round. */
    enum class RoundDecision {
        /** Start another round. */
        RUN,

        /** The ceiling is reached: stop and report. */
        ITERATION_REACHED,

        /** The run is already over: another round must not start. */
        STOPPED,
    }

    /**
     * The single decision point before each round.
     *
     * Both rules live here so neither can be forgotten at a call site: a
     * terminated run never starts another iteration, and the ceiling is checked
     * *before* the counter moves, so exactly `maxIterations` rounds run rather
     * than `maxIterations - 1`.
     */
    fun decideNextRound(terminated: Boolean, iterations: Int, maxIterations: Int): RoundDecision = when {
        terminated -> RoundDecision.STOPPED
        reached(iterations, maxIterations) -> RoundDecision.ITERATION_REACHED
        else -> RoundDecision.RUN
    }
}

/**
 * Versioned migration for the persisted execution limits.
 *
 * Legacy schema (v1) stored only `max_iterations`, and `0` served double duty as
 * both "Unlimited" (a real user choice the picker offers) and the shipping
 * default, so the two were indistinguishable on disk. The fix cannot simply
 * redefine `0`, because that would silently take away an explicit choice from
 * every user who made it.
 *
 * v2 resolves the ambiguity by where the value came from, not by changing what
 * it means:
 *
 *  - a value that was never persisted  -> the new bounded default;
 *  - a persisted `0`                   -> stays Unlimited;
 *  - a persisted negative              -> invalid, replaced with the default;
 *  - a persisted positive              -> untouched.
 *
 * This is pure so the whole table is unit-testable without Android or DataStore.
 */
object LimitsMigration {

    const val LEGACY = 1
    const val CURRENT = 2

    /**
     * @param storedVersion the persisted schema version, or [LEGACY] when the
     *   key is absent (which is what every pre-existing installation has).
     * @param storedIterations the persisted ceiling, or null when the key was
     *   never written.
     */
    data class Result(
        val version: Int,
        val iterations: Int,
        /** True when [iterations] differs from what was persisted. */
        val changed: Boolean,
        /** Machine-readable explanation for diagnostics, or null when untouched. */
        val note: String?,
    )

    fun migrate(storedVersion: Int, storedIterations: Int?): Result {
        if (storedVersion >= CURRENT) {
            return Result(CURRENT, storedIterations ?: ExecutionLimits.DEFAULT_ITERATIONS, false, null)
        }
        return when {
            storedIterations == null ->
                Result(CURRENT, ExecutionLimits.DEFAULT_ITERATIONS, true, "iteration limit defaulted")
            storedIterations < 0 ->
                Result(CURRENT, ExecutionLimits.DEFAULT_ITERATIONS, true, "invalid iteration limit replaced")
            else ->
                // Includes 0: an explicit Unlimited is preserved exactly.
                Result(CURRENT, storedIterations, false, null)
        }
    }
}

/**
 * Bounds *unproductive* work without bounding productive work.
 *
 * A wall-clock or iteration ceiling cannot make this distinction: a legitimate
 * refactor may run for hours, while a stuck agent burns every round re-issuing
 * calls that cannot succeed. What separates them is whether the run is getting
 * anywhere, so this counts consecutive rounds in which **no tool call
 * succeeded** — no file was read or written, no command returned cleanly.
 *
 * Successful tool results reset the counter, so an arbitrarily long task that
 * keeps making progress is never interrupted. Only a run that produces nothing
 * at all for [maxBarrenRounds] consecutive rounds stops, and it stops as a
 * resumable stop with the reason recorded.
 */
class ProgressGuard(private val maxBarrenRounds: Int = DEFAULT_MAX_BARREN_ROUNDS) {

    companion object {
        /**
         * Consecutive fruitless rounds tolerated. Three is enough for a bad edit
         * plus a correction plus one more attempt, and short enough that a fully
         * stuck run ends within a few rounds instead of running all night.
         */
        const val DEFAULT_MAX_BARREN_ROUNDS = 3
    }

    var rounds: Int = 0
        private set

    var barrenRounds: Int = 0
        private set

    /** @return true when the run should stop. */
    fun recordRound(madeProgress: Boolean): Boolean {
        rounds++
        if (madeProgress) {
            barrenRounds = 0
            return false
        }
        barrenRounds++
        return barrenRounds >= maxBarrenRounds
    }

    val stalled: Boolean get() = barrenRounds >= maxBarrenRounds
}

/**
 * Stops a tool call that keeps failing with the same inputs.
 *
 * What it does and does not do:
 *
 *  - It keys on the tool name plus the *normalised arguments*, so the same tool
 *    used with different inputs — or the same tool after the workspace changed —
 *    is never touched.
 *  - It never reaches into a tool's semantics, so no non-idempotent operation is
 *    ever deduplicated; it only declines to send an identical call again.
 *  - [progress] clears every count. One successful tool call means the world
 *    moved (a file was written, a dependency installed), so "retry `npm install`
 *    with the same arguments after fixing package.json" still works.
 *  - Deterministic failures — the classes [AgentEngine.deterministicFailureHint]
 *    recognises as unable to succeed unchanged — get a smaller allowance than
 *    transient ones, so a syntax-error retry is cut short while a flaky network
 *    call gets a second chance.
 *
 * The attempt counts are kept so a final stop can explain itself.
 */
class RepeatedFailureGuard(
    private val deterministicAllowance: Int = DEFAULT_DETERMINISTIC_ALLOWANCE,
    private val transientAllowance: Int = DEFAULT_TRANSIENT_ALLOWANCE,
) {

    companion object {
        /** Deterministic failures may be retried once, then are declined. */
        const val DEFAULT_DETERMINISTIC_ALLOWANCE = 1

        /** Transient failures get one extra attempt for genuine flakiness. */
        const val DEFAULT_TRANSIENT_ALLOWANCE = 2
    }

    /** One observed failure of a tool call. */
    data class Failure(val signature: String, val tool: String, val error: String)

    /** A refusal to run an identical call again. */
    data class Blocked(val tool: String, val attempts: Int, val firstError: String)

    private val counts = LinkedHashMap<String, Int>()
    private val firstError = LinkedHashMap<String, String>()
    private val tools = LinkedHashMap<String, String>()

    /** @return a [Blocked] verdict when this call must not be sent again. */
    fun verdict(failure: Failure, deterministic: Boolean): Blocked? {
        val attempts = (counts[failure.signature] ?: 0) + 1
        counts[failure.signature] = attempts
        tools[failure.signature] = failure.tool
        if (!firstError.containsKey(failure.signature)) firstError[failure.signature] = failure.error
        val allowance = if (deterministic) deterministicAllowance else transientAllowance
        if (attempts <= allowance) return null
        return Blocked(failure.tool, attempts, firstError[failure.signature] ?: failure.error)
    }

    /** Any success clears the state: the workspace is no longer the one that failed. */
    fun progress() {
        counts.clear()
        firstError.clear()
        tools.clear()
    }

    /** Identical failures seen so far, for the stop diagnostics. */
    fun summary(): String = counts.entries
        .sortedByDescending { it.value }
        .take(3)
        .joinToString("; ") { (signature, attempts) ->
            val tool = tools[signature] ?: signature.substringBefore(':')
            "$tool x$attempts"
        }
}

/**
 * The independent budget of one subagent.
 *
 * A subagent previously ran with no cap of its own, sharing only the parent's
 * token/cost/time budget — so a subagent that looped could consume the parent's
 * entire allowance, and an unconfigured parent (the default) gave it no ceiling
 * at all. This bounds a subagent's own work while leaving the shared budget as
 * the outer limit: usage is still charged to the parent, so parent limits keep
 * applying on top.
 */
class SubagentBudget(
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    /** Optional slice of the parent's remaining time this subagent may use. */
    val deadlineMs: Long? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    companion object {
        /**
         * Rounds one subagent may take. A delegated sub-task is scoped and
         * reported back to the parent, which can always delegate again with more
         * context, so a ceiling well below the parent's is appropriate.
         */
        const val DEFAULT_MAX_ITERATIONS = 40
    }

    private val started = clock()

    var iterations: Int = 0
        private set

    val iterationsReached: Boolean get() = iterations >= maxIterations

    val timedOut: Boolean
        get() = deadlineMs?.let { clock() - started >= it } ?: false

    val exhausted: Boolean get() = iterationsReached || timedOut

    /** Consumes one round; false when the subagent must stop instead. */
    fun nextRound(): Boolean {
        if (exhausted) return false
        iterations++
        return true
    }

    /** A reason the parent model can act on. */
    fun reason(): String = when {
        iterationsReached && timedOut ->
            "Subagent stopped: it used its whole budget ($maxIterations rounds and ${deadlineMs?.let { RunStop.formatDuration(it) }})."
        iterationsReached ->
            "Subagent stopped after $maxIterations tool rounds (its own budget)."
        else ->
            "Subagent stopped: it used its time budget (${deadlineMs?.let { RunStop.formatDuration(it) }})."
    }
}

/**
 * Last-resort wall-clock bound for a single tool execution.
 *
 * Tools that can legitimately run long carry their own, smaller, user-visible
 * timeouts (the shell tool clamps to 600s and defaults to 120s; MCP connections
 * bound their own handshakes). Nothing bounded the *engine's* call to
 * `tool.execute`, so a tool that never returned hung the run silently and
 * forever. This sits far above every tool's own limit so it can only fire when a
 * tool has genuinely lost control.
 */
const val TOOL_HARD_TIMEOUT_MS: Long = 30 * 60 * 1000L

/** Raised when [withToolTimeout] gives up on a tool. Distinct from cancellation. */
class ToolTimedOut(val toolName: String, val timeoutMs: Long) :
    RuntimeException("$toolName did not finish within ${RunStop.formatDuration(timeoutMs)}") {

    /**
     * Non-null explanation for call sites that must produce a concrete string
     * (a failed tool result). [RuntimeException.message] is nullable by type even
     * though this class always sets it.
     */
    val explanation: String
        get() = message ?: "$toolName did not finish within ${RunStop.formatDuration(timeoutMs)}"
}

/**
 * Runs [block] under [TOOL_HARD_TIMEOUT_MS], translating only the timeout into a
 * domain error.
 *
 * The distinction between a timeout and a cancellation is load-bearing: a
 * timeout is reported to the model as a failed tool result so it can change
 * approach, while a cancellation must keep propagating so the run stops
 * promptly. `withTimeout` throws [TimeoutCancellationException] for the former
 * and rethrows the caller's own cancellation for the latter, which is exactly
 * the split this preserves.
 */
suspend fun <T> withToolTimeout(
    toolName: String,
    timeoutMs: Long = TOOL_HARD_TIMEOUT_MS,
    block: suspend () -> T,
): T {
    if (timeoutMs <= 0) return block()
    return try {
        withTimeout(timeoutMs) { block() }
    } catch (timeout: TimeoutCancellationException) {
        throw ToolTimedOut(toolName, timeoutMs)
    }
}
