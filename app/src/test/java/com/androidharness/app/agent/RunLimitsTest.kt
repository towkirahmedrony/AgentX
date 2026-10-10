package com.androidharness.app.agent

import com.androidharness.app.llm.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the execution limits: safe defaults, the legacy
 * migration, every limit's enforcement point, and the guards that stop a
 * runaway run without touching a productive one.
 *
 * Everything here is deterministic and offline: no provider, no paid call, no
 * Android API. Clocks are injected wherever time matters.
 */
class RunLimitsTest {

    // ---- defaults and migration ------------------------------------------

    @Test
    fun `a new installation gets a bounded iteration ceiling, not unlimited`() {
        assertEquals(100, ExecutionLimits.DEFAULT_ITERATIONS)
        val fresh = LimitsMigration.migrate(storedVersion = LimitsMigration.LEGACY, storedIterations = null)
        assertEquals(ExecutionLimits.DEFAULT_ITERATIONS, fresh.iterations)
        assertTrue("the change is observable, not silent", fresh.changed)
        assertEquals("iteration limit defaulted", fresh.note)
        assertFalse("a fresh install is never unlimited by accident", ExecutionLimits.isUnlimited(fresh.iterations))
    }

    @Test
    fun `an explicit unlimited is preserved and never rewritten`() {
        val migrated = LimitsMigration.migrate(storedVersion = LimitsMigration.LEGACY, storedIterations = 0)
        assertEquals(0, migrated.iterations)
        assertFalse("a value the user chose is not reinterpreted", migrated.changed)
        assertNull(migrated.note)
        assertTrue(ExecutionLimits.isUnlimited(migrated.iterations))
    }

    @Test
    fun `a legacy configured ceiling is left alone`() {
        val migrated = LimitsMigration.migrate(storedVersion = LimitsMigration.LEGACY, storedIterations = 250)
        assertEquals(250, migrated.iterations)
        assertFalse(migrated.changed)
        assertEquals(LimitsMigration.CURRENT, migrated.version)
    }

    @Test
    fun `an invalid negative ceiling becomes the documented default`() {
        val migrated = LimitsMigration.migrate(storedVersion = LimitsMigration.LEGACY, storedIterations = -5)
        assertEquals(ExecutionLimits.DEFAULT_ITERATIONS, migrated.iterations)
        assertTrue(migrated.changed)
        assertEquals("invalid iteration limit replaced", migrated.note)
        assertFalse(ExecutionLimits.isUnlimited(migrated.iterations))
    }

    @Test
    fun `an already current schema is not migrated again`() {
        val current = LimitsMigration.migrate(storedVersion = LimitsMigration.CURRENT, storedIterations = 0)
        assertEquals(0, current.iterations)
        assertFalse("re-running the migration must not disturb a stored choice", current.changed)
        val absent = LimitsMigration.migrate(storedVersion = LimitsMigration.CURRENT, storedIterations = null)
        assertEquals(ExecutionLimits.DEFAULT_ITERATIONS, absent.iterations)
    }

    @Test
    fun `zero missing negative and invalid values are all distinguished`() {
        assertFalse(ExecutionLimits.isUnlimited(5))
        assertTrue(ExecutionLimits.isUnlimited(0))
        assertTrue(
            "a negative cannot reach the engine (the migration replaces it); the guard treats " +
                "any non-positive value as 'no ceiling' rather than as a zero-token budget",
            ExecutionLimits.isUnlimited(-1),
        )
        assertNull(ExecutionLimits.ceilingOrNull(0))
        assertEquals(7, ExecutionLimits.ceilingOrNull(7))
        assertNull("an out-of-range entry is rejected, not stored", ExecutionLimits.sanitizeInput(0))
        assertNull(ExecutionLimits.sanitizeInput(-3))
        assertNull(ExecutionLimits.sanitizeInput(ExecutionLimits.MAX_ITERATIONS_ALLOWED + 1))
        assertEquals(25, ExecutionLimits.sanitizeInput(25))
        assertNull(ExecutionLimits.sanitizeInput(null))
    }

    // ---- iteration limit and termination ---------------------------------

    @Test
    fun `exactly the configured number of rounds runs`() {
        var iterations = 0
        var rounds = 0
        while (true) {
            if (ExecutionLimits.decideNextRound(false, iterations, 3) != ExecutionLimits.RoundDecision.RUN) break
            iterations++
            rounds++
        }
        assertEquals("3 means 3 rounds, not 2", 3, rounds)
        assertEquals(ExecutionLimits.RoundDecision.ITERATION_REACHED, ExecutionLimits.decideNextRound(false, 3, 3))
    }

    @Test
    fun `an unlimited ceiling keeps running`() {
        assertEquals(ExecutionLimits.RoundDecision.RUN, ExecutionLimits.decideNextRound(false, 10_000, 0))
        assertEquals(ExecutionLimits.RoundDecision.RUN, ExecutionLimits.decideNextRound(false, 0, 0))
    }

    @Test
    fun `no round starts after the run is terminated`() {
        assertEquals(
            "a terminated run must never begin another iteration",
            ExecutionLimits.RoundDecision.STOPPED,
            ExecutionLimits.decideNextRound(terminated = true, iterations = 0, maxIterations = 0),
        )
        assertEquals(
            ExecutionLimits.RoundDecision.STOPPED,
            ExecutionLimits.decideNextRound(terminated = true, iterations = 100, maxIterations = 100),
        )
    }

    // ---- budget enforcement ----------------------------------------------

    @Test
    fun `a duration limit pauses the run against the configured minutes`() {
        var clock = 0L
        val budget = TaskBudget(TaskLimits(minutes = 2), 0, 0.0, 0L) { clock }
        budget.check()
        clock = 119_000
        budget.check()
        clock = 120_000
        assertEquals("a minute of the two-minute budget is left", 1_000L, budget.remainingTimeMs())
        clock = 120_000
        val paused = assertThrows(TaskPaused::class.java) { budget.check() }
        assertEquals(StopKind.TIME_LIMIT, paused.kind)
        assertEquals("a spent budget reports zero, never a negative remainder", 0L, budget.remainingTimeMs())
        clock = 500_000
        assertEquals(0L, budget.remainingTimeMs())
    }

    @Test
    fun `no duration limit means no remaining slice is claimed`() {
        val budget = TaskBudget(TaskLimits(), 0, 0.0, 0L) { 5_000L }
        assertNull("unlimited must not report a remaining budget", budget.remainingTimeMs())
    }

    @Test
    fun `a token ceiling is reached from provider-reported usage`() {
        val budget = TaskBudget(TaskLimits(tokens = 1_000), 0, 0.0, 0L)
        budget.add(600, 300, 0.01)
        budget.check()
        assertEquals(UsageStatus.ACTUAL, budget.usageStatus())
        budget.add(60, 60, 0.01)
        val paused = assertThrows(TaskPaused::class.java) { budget.check() }
        assertEquals(StopKind.TOKEN_LIMIT, paused.kind)
    }

    @Test
    fun `unknown usage is never counted as zero token consumption`() {
        val budget = TaskBudget(TaskLimits(tokens = 1_000), 0, 0.0, 0L)
        // The provider reported nothing at all for these requests.
        budget.chargeUnsourced(400)
        budget.check()
        assertEquals("estimated, not measured", UsageStatus.ESTIMATED, budget.usageStatus())
        budget.chargeUnsourced(700)
        val paused = assertThrows(TaskPaused::class.java) { budget.check() }
        assertEquals(StopKind.TOKEN_LIMIT, paused.kind)
        assertEquals(1_100L, budget.usage().first)
    }

    @Test
    fun `mixed reported and estimated usage is labelled as such`() {
        val budget = TaskBudget(TaskLimits(), 0, 0.0, 0L)
        assertEquals(UsageStatus.UNKNOWN, budget.usageStatus())
        budget.add(10, 10, null)
        assertEquals(UsageStatus.ACTUAL, budget.usageStatus())
        budget.chargeUnsourced(50)
        assertEquals(UsageStatus.MIXED, budget.usageStatus())
    }

    @Test
    fun `a cost ceiling is enforced against a reliable price`() {
        val budget = TaskBudget(TaskLimits(cost = 0.5), 0, 0.0, 0L)
        budget.add(1_000, 1_000, 0.4)
        budget.check()
        budget.add(1_000, 1_000, 0.2)
        val paused = assertThrows(TaskPaused::class.java) { budget.check() }
        assertEquals(StopKind.COST_LIMIT, paused.kind)
    }

    @Test
    fun `a cost ceiling is never enforced against an unavailable price`() {
        val budget = TaskBudget(TaskLimits(cost = 0.01), 0, 0.0, 0L)
        budget.markCostUnavailable("Cost ceiling not enforced: no reliable price for mystery-model.")
        budget.add(1_000_000, 1_000_000, null)
        // Would have blown through an invented price; must not pause the run.
        budget.check()
        assertEquals(
            "Cost ceiling not enforced: no reliable price for mystery-model.",
            budget.costAdvice(),
        )
    }

    @Test
    fun `unlimited limits permit any usage`() {
        val budget = TaskBudget(TaskLimits(), 9_999_999, 999.0, 999_999_999L) { 0L }
        budget.add(1_000_000, 1_000_000, 500.0)
        budget.chargeUnsourced(10_000_000)
        budget.check()
        assertTrue(budget.usage().first > 10_000_000)
    }

    @Test
    fun `an exhausted budget blocks the next provider request entirely`() = runBlocking {
        var called = false
        try {
            withContext(TaskBudget(TaskLimits(tokens = 1), 1, 0.0, 0L)) {
                StreamRetrier.run(
                    streamFor = { called = true; flowOf() },
                    onAttemptStart = {}, hasOutput = { false }, handleEvent = {},
                    retryReason = { it }, emitEvent = {},
                )
            }
            throw AssertionError("expected a pause")
        } catch (_: TaskPaused) {
        }
        assertFalse("no request may be issued once the budget is spent", called)
    }

    @Test
    fun `an overflow recovery retry cannot bypass the run budget`() = runBlocking {
        var compacted = false
        var retried = false
        try {
            withContext(TaskBudget(TaskLimits(tokens = 10), 10, 0.0, 0L)) {
                recoverFromContextOverflow(
                    failure = "context_length_exceeded",
                    recovery = OverflowRecovery(),
                    canCompact = true,
                    compactHistory = { compacted = true; true },
                    shrinkWithoutSummary = { false },
                    exhausted = { "no" },
                    onExhausted = {},
                    onRetrying = { retried = true },
                )
            }
            throw AssertionError("expected a pause")
        } catch (_: TaskPaused) {
        }
        assertFalse("a spent budget must not even pay for a compaction request", compacted)
        assertFalse("recovery must not re-issue a request past the budget", retried)
    }

    // ---- retry bounds ----------------------------------------------------

    @Test
    fun `retries stop at the policy bound and never retry a started answer`() = runBlocking {
        var attempts = 0
        var sleepCalls = 0
        val failure = StreamRetrier.run(
            streamFor = {
                attempts++
                kotlinx.coroutines.flow.flow { emit(StreamEvent.Failure("HTTP 429: rate limit exceeded", 429)) }
            },
            onAttemptStart = {}, hasOutput = { false }, handleEvent = {},
            retryReason = { it }, emitEvent = {}, sleep = { sleepCalls++ },
        )
        assertEquals("initial attempt plus MAX_RETRIES", RetryPolicy.MAX_RETRIES + 1, attempts)
        assertEquals(RetryPolicy.MAX_RETRIES, sleepCalls)
        assertTrue(failure!!.contains("429"))
    }

    @Test
    fun `a request that already produced output is never retried`() = runBlocking {
        var attempts = 0
        val produced = StringBuilder()
        StreamRetrier.run(
            streamFor = {
                attempts++
                kotlinx.coroutines.flow.flow {
                    emit(StreamEvent.TextDelta("partial answer"))
                    emit(StreamEvent.Failure("HTTP 500: upstream died", 500))
                }
            },
            onAttemptStart = { produced.setLength(0) },
            // Mirrors the engine: any text, thinking or tool call counts.
            hasOutput = { produced.isNotEmpty() },
            handleEvent = { event -> if (event is StreamEvent.TextDelta) produced.append(event.text) },
            retryReason = { it }, emitEvent = {}, sleep = {},
        )
        assertEquals("a partially produced answer must not be replayed", 1, attempts)
    }

    @Test
    fun `a non-retryable status is attempted once`() = runBlocking {
        var attempts = 0
        StreamRetrier.run(
            streamFor = {
                attempts++
                kotlinx.coroutines.flow.flow { emit(StreamEvent.Failure("HTTP 400: bad request", 400)) }
            },
            onAttemptStart = {}, hasOutput = { false }, handleEvent = {},
            retryReason = { it }, emitEvent = {}, sleep = {},
        )
        assertEquals(1, attempts)
    }

    // ---- cancellation ----------------------------------------------------

    @Test
    fun `cancellation during a model request is not turned into a retry`() = runBlocking {
        var attempts = 0
        val job = launch {
            StreamRetrier.run(
                streamFor = {
                    attempts++
                    kotlinx.coroutines.flow.flow {
                        throw CancellationException("user stopped the run")
                    }
                },
                onAttemptStart = {}, hasOutput = { false }, handleEvent = {},
                retryReason = { it }, emitEvent = {}, sleep = {},
            )
        }
        job.join()
        assertEquals("a cancellation is not a retryable failure", 1, attempts)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancellation during a retry backoff interrupts immediately`() = runBlocking {
        val started = System.nanoTime()
        val worker = launch {
            StreamRetrier.run(
                streamFor = {
                    kotlinx.coroutines.flow.flow { emit(StreamEvent.Failure("HTTP 500: upstream died", 500)) }
                },
                onAttemptStart = {}, hasOutput = { false }, handleEvent = {},
                retryReason = { it }, emitEvent = {},
                // Real cancellable sleep, as production uses.
                sleep = { delay(60_000) },
            )
        }
        delay(50)
        worker.cancel()
        worker.join()
        assertTrue(worker.isCancelled)
        assertTrue(
            "the backoff must be interruptible, not merely re-checked after it ends",
            (System.nanoTime() - started) / 1_000_000 < 5_000,
        )
    }

    @Test
    fun `cancellation during a tool call propagates instead of becoming a timeout`() = runBlocking {
        var caught: Throwable? = null
        val job = launch {
            try {
                withToolTimeout("slow_tool", timeoutMs = 60_000) {
                    // Suspends until cancelled; the 60s timeout is far away.
                    delay(30_000)
                }
            } catch (e: Throwable) {
                caught = e
            }
        }
        delay(50)
        job.cancel()
        job.join()
        assertTrue("real cancellation must not be reported as a tool timeout", caught is CancellationException)
        assertFalse(caught is ToolTimedOut)
    }

    @Test
    fun `a tool that loses control is stopped by the engine timeout`() = runBlocking {
        val thrown = assertThrows(ToolTimedOut::class.java) {
            runBlocking { withToolTimeout("stuck_tool", timeoutMs = 40) { delay(5_000) } }
        }
        assertEquals("stuck_tool", thrown.toolName)
        assertEquals(40L, thrown.timeoutMs)
        assertTrue(thrown.message!!.contains("stuck_tool"))
    }

    @Test
    fun `the engine tool timeout sits above every individual tool limit`() {
        // The shell tool clamps its own timeout to 600s; the engine bound must be
        // far above that so it can only fire when a tool has lost control.
        assertTrue(TOOL_HARD_TIMEOUT_MS > 600_000L)
        assertTrue(TOOL_HARD_TIMEOUT_MS >= 10 * 60_000L)
    }

    // ---- repeated identical failures -------------------------------------

    private fun failure(tool: String, args: String, error: String = "boom") =
        RepeatedFailureGuard.Failure(signature = "$tool:$args", tool = tool, error = error)

    @Test
    fun `a deterministic failure may be retried once and is then declined`() {
        val guard = RepeatedFailureGuard()
        assertNull("the first attempt always runs", guard.verdict(failure("shell", "{}"), deterministic = true))
        val blocked = guard.verdict(failure("shell", "{}"), deterministic = true)
        assertEquals(2, blocked!!.attempts)
        assertEquals("shell", blocked.tool)
        assertEquals("boom", blocked.firstError)
    }

    @Test
    fun `a transient failure gets one extra attempt for genuine flakiness`() {
        val guard = RepeatedFailureGuard()
        assertNull(guard.verdict(failure("http_request", "{}"), deterministic = false))
        assertNull(guard.verdict(failure("http_request", "{}"), deterministic = false))
        assertEquals(3, guard.verdict(failure("http_request", "{}"), deterministic = false)!!.attempts)
    }

    @Test
    fun `different arguments and different tools are never blocked`() {
        val guard = RepeatedFailureGuard()
        assertNull(guard.verdict(failure("shell", "{\"command\":\"a\"}"), deterministic = true))
        assertNull("a different argument is a different operation", guard.verdict(failure("shell", "{\"command\":\"b\"}"), deterministic = true))
        assertNull("a different tool is untouched", guard.verdict(failure("edit_file", "{\"command\":\"a\"}"), deterministic = true))
        // Only the original combination is spent.
        assertEquals(2, guard.verdict(failure("shell", "{\"command\":\"a\"}"), deterministic = true)!!.attempts)
    }

    @Test
    fun `a success clears the slate so a fixed-and-rerun flow still works`() {
        val guard = RepeatedFailureGuard()
        guard.verdict(failure("shell", "{}"), deterministic = true)
        guard.verdict(failure("shell", "{}"), deterministic = true)
        guard.progress()
        assertNull(
            "after a successful call the same command may run again",
            guard.verdict(failure("shell", "{}"), deterministic = true),
        )
    }

    @Test
    fun `the guard summarises what kept failing`() {
        val guard = RepeatedFailureGuard()
        repeat(3) { guard.verdict(failure("npm_test", "{\"a\":1}"), deterministic = false) }
        guard.verdict(failure("edit_file", "{\"b\":2}"), deterministic = true)
        val summary = guard.summary()
        assertTrue(summary.contains("npm_test x3"))
        assertTrue(summary.contains("edit_file x1"))
    }

    // ---- stall prevention ------------------------------------------------

    @Test
    fun `a productive run is never stopped by the stall guard`() {
        val guard = ProgressGuard()
        repeat(500) { assertFalse("a long task that keeps working must continue", guard.recordRound(true)) }
        assertEquals(0, guard.barrenRounds)
        assertEquals(500, guard.rounds)
    }

    @Test
    fun `a stuck run stops after the documented number of barren rounds`() {
        val guard = ProgressGuard()
        assertFalse(guard.recordRound(false))
        assertFalse(guard.recordRound(false))
        assertTrue("a run producing nothing must not run forever", guard.recordRound(false))
        assertTrue(guard.stalled)
    }

    @Test
    fun `one success resets the barren count`() {
        val guard = ProgressGuard()
        guard.recordRound(false)
        guard.recordRound(false)
        assertFalse(guard.recordRound(true))
        assertEquals(0, guard.barrenRounds)
        assertFalse(guard.recordRound(false))
        assertFalse(guard.recordRound(false))
        assertTrue(guard.recordRound(false))
    }

    @Test
    fun `the stall allowance is small enough to stop a stuck run quickly`() {
        assertTrue(ProgressGuard.DEFAULT_MAX_BARREN_ROUNDS in 2..5)
    }

    // ---- subagent budget -------------------------------------------------

    @Test
    fun `a subagent is bounded by its own iteration budget`() {
        val budget = SubagentBudget(maxIterations = 3)
        var rounds = 0
        while (budget.nextRound()) rounds++
        assertEquals(3, rounds)
        assertTrue(budget.iterationsReached)
        assertTrue(budget.exhausted)
        assertTrue(budget.reason().contains("3"))
    }

    @Test
    fun `a subagent is bounded by its time slice`() {
        var clock = 0L
        val budget = SubagentBudget(maxIterations = 1_000, deadlineMs = 5_000L, clock = { clock })
        assertTrue(budget.nextRound())
        clock = 5_001
        assertFalse("the time slice must stop it even with rounds left", budget.nextRound())
        assertTrue(budget.timedOut)
        assertFalse(budget.iterationsReached)
        assertTrue(budget.exhausted)
    }

    @Test
    fun `a subagent budget default keeps delegations useful but bounded`() {
        assertTrue(SubagentBudget.DEFAULT_MAX_ITERATIONS in 10..100)
        val unlimited = SubagentBudget(maxIterations = SubagentBudget.DEFAULT_MAX_ITERATIONS)
        assertTrue(unlimited.nextRound())
        assertNull("no slice is claimed when the parent set no time limit", SubagentBudget().deadlineMs)
    }

    // ---- final status and diagnostics ------------------------------------

    @Test
    fun `a budget stop is reported as a resumable limit, not a failure`() {
        val stop = RunStop(
            kind = StopKind.ITERATION_LIMIT,
            message = "Stopped after 100 tool iterations (iteration ceiling).",
            iteration = 100,
            maxIterations = 100,
            elapsedMs = 125_000,
            usageStatus = UsageStatus.MIXED,
            retries = 2,
        )
        assertTrue(stop.limitReached)
        assertTrue(stop.resumable)
        val described = stop.describe()
        assertTrue(described.contains("iterations 100/100"))
        assertTrue(described.contains("active 2m 5s"))
        assertTrue(described.contains("retries 2"))
        assertTrue("an estimate must be labelled as one", described.contains("partly estimated"))
    }

    @Test
    fun `each ending is distinguishable from the others`() {
        val success = RunStop(StopKind.SUCCESS, "done")
        val cancelled = RunStop(StopKind.CANCELLED, "stopped by user")
        val provider = RunStop(StopKind.PROVIDER_ERROR, "HTTP 500")
        assertFalse(success.limitReached)
        assertFalse(success.resumable)
        assertFalse(cancelled.limitReached)
        assertFalse(cancelled.resumable)
        assertFalse("a provider failure is not a budget stop", provider.limitReached)
        assertFalse(provider.resumable)
        assertTrue(RunStop(StopKind.STALLED, "stuck").resumable)
        assertTrue(RunStop(StopKind.TIME_LIMIT, "time").limitReached)
        assertTrue(RunStop(StopKind.COST_LIMIT, "cost").limitReached)
        assertTrue(RunStop(StopKind.TOKEN_LIMIT, "tokens").limitReached)
    }

    @Test
    fun `a subagent that used its whole budget is visible in the stop record`() {
        val stop = RunStop(
            StopKind.ITERATION_LIMIT, "ceiling",
            subagentBudgetReached = true,
        )
        assertTrue(stop.describe().contains("a subagent reached its own budget"))
    }

    @Test
    fun `duration rendering is human readable`() {
        assertEquals("45s", RunStop.formatDuration(45_000))
        assertEquals("2m 0s", RunStop.formatDuration(120_000))
        assertEquals("60m 0s", RunStop.formatDuration(3_600_000))
    }

    @Test
    fun `usage labels never imply false precision`() {
        assertEquals("provider-reported", RunStop.usageLabel(UsageStatus.ACTUAL))
        assertEquals("estimated", RunStop.usageLabel(UsageStatus.ESTIMATED))
        assertEquals("partly estimated", RunStop.usageLabel(UsageStatus.MIXED))
        assertEquals("unavailable", RunStop.usageLabel(UsageStatus.UNKNOWN))
    }

    // ---- interrupted-run recovery still works ----------------------------

    @Test
    fun `interrupted runs still close their open tool calls exactly once`() {
        val messages = listOf(
            com.androidharness.app.core.ChatMessage(
                role = com.androidharness.app.core.Role.ASSISTANT,
                toolCalls = listOf(
                    com.androidharness.app.core.ToolCallData("a", "write_file", "{}"),
                    com.androidharness.app.core.ToolCallData("b", "shell", "{}"),
                ),
            ),
            com.androidharness.app.core.ChatMessage(
                role = com.androidharness.app.core.Role.TOOL, text = "done", toolCallId = "a",
            ),
        )
        val missing = RunRecovery.missingResults(messages)
        assertEquals(listOf("b"), missing.map { it.toolCallId })
        assertTrue(missing.single().isError)
        assertTrue("closing is idempotent", RunRecovery.missingResults(messages + missing).isEmpty())
    }

    @Test
    fun `a paused record remains resumable after a limit stop`() {
        val record = TaskRecord(
            status = "paused",
            reason = "Token limit reached (estimated usage)",
            outcome = StopKind.TOKEN_LIMIT.name,
            outcomeDetail = "iterations 12/100 · tokens estimated",
            limitsNotice = "Cost ceiling not enforced: no reliable price for mystery-model.",
            limits = TaskLimits(tokens = 1_000),
        )
        assertTrue(record.resumable)
        assertEquals("TOKEN_LIMIT", record.outcome)
        assertTrue("an unenforceable ceiling is surfaced, not swallowed", record.limitsNotice!!.isNotEmpty())
    }
}
