package com.androidharness.app.agent

import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The two independent streaming deadlines. They answer different questions and
 * must never be collapsed into one value:
 *
 *  - [firstTokenMs] bounds the wait from request start to the first meaningful
 *    response event. Large self-hosted models spend minutes here (cold start,
 *    GPU allocation, model load, prompt prefill).
 *  - [idleMs] bounds the gap between events once generation has begun. It is
 *    reset by any upstream activity, keep-alives included.
 */
internal data class StreamTimeouts(val firstTokenMs: Long, val idleMs: Long) {
    init { require(firstTokenMs > 0 && idleMs > 0) { "Stream timeouts must be positive" } }

    companion object {
        /** Fast hosted APIs: a silent gateway here means trouble, so fail fast. */
        val DEFAULT = StreamTimeouts(90_000, 90_000)
    }
}

/** Which deadline expired; kept distinct so logs and errors say what happened. */
internal enum class StallPhase { FIRST_TOKEN, IDLE }

/**
 * Raised when a stream goes silent past its configured budget. It extends
 * [IOException] so [RetryPolicy] treats it as a bounded, retryable transient
 * failure, exactly like a dropped socket, and only while no output was
 * produced (see [StreamRetrier]'s `hasOutput` gate).
 */
internal class StreamStalledException(
    val phase: StallPhase,
    val timeoutMs: Long,
) : IOException(
    when (phase) {
        StallPhase.FIRST_TOKEN ->
            "No response from the model within ${timeoutMs / 1000}s (initial response timed out)"
        StallPhase.IDLE ->
            "Model stopped sending output for ${timeoutMs / 1000}s (stream idle, timed out)"
    },
)

/**
 * Enforces both deadlines on a raw stream.
 *
 * Phase 1 is an absolute deadline measured from collection start: transport
 * keep-alives prove the socket is alive but never satisfy the first-token
 * budget, so a server that pings forever without generating anything still
 * fails. Phase 2 begins the moment a meaningful event arrives; the first-token
 * timer is discarded (never left running) and the interval between subsequent
 * events is bounded by [StreamTimeouts.idleMs]. Any upstream activity, a
 * [StreamEvent.Heartbeat] included, resets the idle window.
 *
 * The watchdog is a child of the returned flow, so a timeout cancels the
 * collection (and, through [StreamRetrier], surfaces as a normal failure) and
 * the job is always cancelled with the flow, never leaked.
 */
internal fun <T> Flow<T>.stallGuard(
    timeouts: StreamTimeouts,
    isMeaningful: (T) -> Boolean = { true },
): Flow<T> = channelFlow {
    // Conflated: the watchdog only needs to know *that* activity happened and
    // re-check the phase, not how many events arrived.
    val activity = Channel<Unit>(Channel.CONFLATED)
    val firstTokenSeen = AtomicBoolean(false)
    val recordedStall = AtomicReference<StreamStalledException?>(null)
    // A child of this producer scope. On a normal end of stream it is simply
    // cancelled in the finally block, never leaked; the activity channel is NOT
    // closed there, so the watchdog is cancelled rather than racing into a
    // ClosedReceiveChannelException.
    val watchdog = launch {
        // Phase 1: absolute, not resettable by keep-alives.
        val first = withTimeoutOrNull(timeouts.firstTokenMs) {
            while (!firstTokenSeen.get()) activity.receive()
            Unit
        }
        if (first == null) {
            val stall = StreamStalledException(StallPhase.FIRST_TOKEN, timeouts.firstTokenMs)
            recordedStall.set(stall)
            throw stall
        }
        // Phase 2: reset on every event; the first-token deadline is gone.
        while (true) {
            if (withTimeoutOrNull(timeouts.idleMs) { activity.receive() } == null) {
                val stall = StreamStalledException(StallPhase.IDLE, timeouts.idleMs)
                recordedStall.set(stall)
                throw stall
            }
        }
    }
    try {
        this@stallGuard.collect { value ->
            if (isMeaningful(value)) firstTokenSeen.set(true)
            activity.trySend(Unit)
            send(value)
        }
    } catch (e: Throwable) {
        // Depending on how the scope unwound, a stall can reach here as the
        // original exception or as the cancellation it caused. Surface the
        // recorded stall either way so the contract is stable; a genuine
        // user/scope cancellation has no recorded stall and passes through.
        val stall = recordedStall.get()
        if (stall != null) throw stall
        throw e
    } finally {
        watchdog.cancel()
    }
}

/**
 * Maps a provider's own settings onto the two streaming deadlines. Explicit
 * per-provider values always win; otherwise the provider category decides, so
 * a self-hosted endpoint the user marked as slow is not held to a hosted API's
 * fast-failure budget, and an ordinary hosted provider is never made to wait
 * for a self-hosted model's cold start.
 */
internal object StreamTimeoutPolicy {
    const val MIN_SEC = 5
    const val MAX_SEC = 3_600

    /** Fast hosted providers: preserve the historical 90s window. */
    const val HOSTED_FIRST_TOKEN_SEC = 90
    const val HOSTED_IDLE_SEC = 90

    /**
     * On-device models: preserve the historical 600s window on both phases.
     * Phone CPUs can pause a long time between tokens, and the previous
     * single 600s guard applied from collection start to every gap.
     */
    const val LOCAL_FIRST_TOKEN_SEC = 600
    const val LOCAL_IDLE_SEC = 600

    /**
     * Custom / self-hosted endpoints: a large model behind a reverse proxy can
     * spend minutes in cold start, model load or prompt prefill. This is the
     * default a new custom endpoint is created with; hosted brands keep the
     * fast 90s budget unless the user raises it.
     */
    const val SELF_HOSTED_FIRST_TOKEN_SEC = 600
    const val SELF_HOSTED_IDLE_SEC = 120

    fun resolve(config: ProviderConfig, onDeviceModel: Boolean): StreamTimeouts {
        val first = config.firstTokenTimeoutSec.takeIf { it in MIN_SEC..MAX_SEC }
            ?: if (onDeviceModel) LOCAL_FIRST_TOKEN_SEC else HOSTED_FIRST_TOKEN_SEC
        val idle = config.idleTimeoutSec.takeIf { it in MIN_SEC..MAX_SEC }
            ?: if (onDeviceModel) LOCAL_IDLE_SEC else HOSTED_IDLE_SEC
        return StreamTimeouts(first * 1000L, idle * 1000L)
    }
}

/**
 * The single shared retry wrapper for every streamed LLM request the engine
 * makes, main turn, subagent turn, compaction. Transient failures (HTTP
 * 408/429/5xx, network drops, stalled gateways, first-token or idle timeouts)
 * are retried with [RetryPolicy]'s backoff; permanent errors surface at once.
 *
 * A failure only retries while the attempt produced no output at all:
 * re-emitting deltas the UI already showed would duplicate them. Each call
 * site states which of its buffers count as output via [hasOutput] and
 * resets its buffers per attempt via [onAttemptStart]. In-band failures
 * ([StreamEvent.Failure]) and thrown exceptions are classified identically.
 *
 * [StreamEvent.Heartbeat] is deliberately not output: it only proves the
 * connection is alive, so it resets the idle guard without blocking a retry
 * that would otherwise be safe.
 */
internal object StreamRetrier {

    /**
     * Streams until completion or terminal failure. Returns null on success,
     * otherwise the final failure message (from an exception or from
     * [StreamEvent.Failure]).
     *
     * @param streamFor        builds one attempt's raw flow; retried as-is.
     * @param onAttemptStart   clears caller buffers before each attempt.
     * @param hasOutput        whether this attempt produced output yet; when
     *                         true a failure is terminal, never retried.
     * @param handleEvent      consumes non-failure events (deltas, usage…).
     * @param retryReason      formats the message shown in [AgentEvent.Retrying].
     * @param emitEvent        sink for [AgentEvent.Retrying] announcements.
     * @param sleep            waits out the backoff; injectable for tests.
     * @param timeouts         first-token and idle budgets; injectable for tests.
     */
    internal suspend fun run(
        streamFor: () -> Flow<StreamEvent>,
        onAttemptStart: () -> Unit,
        hasOutput: () -> Boolean,
        handleEvent: suspend (StreamEvent) -> Unit,
        retryReason: (String) -> String,
        emitEvent: suspend (AgentEvent.Retrying) -> Unit,
        sleep: suspend (Long) -> Unit = { delay(it) },
        timeouts: StreamTimeouts = StreamTimeouts.DEFAULT,
        allowRetries: Boolean = true,
        onTerminalFailure: (recoverable: Boolean) -> Unit = {},
    ): String? {
        var attempt = 0
        while (true) {
            kotlin.coroutines.coroutineContext[TaskBudget]?.check()
            onAttemptStart()
            var failure: String? = null
            var failureCode: Int? = null
            var cause: Throwable? = null
            try {
                streamFor()
                    .stallGuard(timeouts) { it !is StreamEvent.Heartbeat }
                    .takeWhile { event ->
                        when (event) {
                            is StreamEvent.Failure -> {
                                failure = event.message
                                failureCode = event.code
                            }
                            else -> handleEvent(event)
                        }
                        event !is StreamEvent.Failure
                    }.collect { }
            } catch (ce: CancellationException) {
                // A stall can reach us wrapped as cancellation depending on how
                // the guard's scope unwound. Unwrap only a real stall; genuine
                // cancellation (user stop, task pause, scope teardown) must
                // propagate unchanged and must never become a retry.
                val stall = generateSequence(ce as Throwable) { it.cause }
                    .filterIsInstance<StreamStalledException>().firstOrNull()
                if (stall == null) throw ce
                cause = stall
                failure = stall.message
            } catch (e: Exception) {
                cause = e
                failure = e.message ?: e.javaClass.simpleName
            }

            val retryable = allowRetries && failure != null &&
                attempt < RetryPolicy.MAX_RETRIES &&
                !hasOutput() &&
                RetryPolicy.isRetryable(cause, failure, failureCode)
            if (!retryable) {
                if (failure != null) onTerminalFailure(allowRetries && RetryPolicy.isRetryable(cause, failure, failureCode))
                return failure
            }

            attempt++
            val delayMs = RetryPolicy.delayMs(attempt)
            emitEvent(AgentEvent.Retrying(attempt, delayMs, retryReason(failure)))
            sleep(delayMs)
        }
    }
}
