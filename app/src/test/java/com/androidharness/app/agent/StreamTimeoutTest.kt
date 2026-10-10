package com.androidharness.app.agent

import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The two-phase streaming guard, the provider timeout policy, and the retry
 * safety around them. All budgets are scaled down (real ones are 90s/120s/600s)
 * so the tests run in milliseconds; the relative timing is what matters.
 */
class StreamTimeoutTest {

    private fun config(first: Int = 0, idle: Int = 0) = ProviderConfig(
        id = "p", name = "Provider", type = ProviderType.OPENAI_COMPAT,
        baseUrl = "https://example.test/v1", model = "m",
        firstTokenTimeoutSec = first, idleTimeoutSec = idle,
    )

    private val meaningful: (StreamEvent) -> Boolean = { it !is StreamEvent.Heartbeat }

    /** The stall survives however the guard's scope unwound it. */
    private fun stallOf(t: Throwable?): StreamStalledException? =
        generateSequence(t) { it.cause }.filterIsInstance<StreamStalledException>().firstOrNull()

    // ---- guard: first-token phase -------------------------------------------

    @Test
    fun `stream that never responds fails on the first-token budget`() = runBlocking {
        val thrown = runCatching {
            flow<StreamEvent> { awaitCancellation() }
                .stallGuard(StreamTimeouts(80, 1_000), meaningful)
                .collect { }
        }.exceptionOrNull()
        val stall = stallOf(thrown)
        assertTrue("expected a first-token stall, got $thrown", stall != null)
        assertEquals(StallPhase.FIRST_TOKEN, stall!!.phase)
    }

    @Test
    fun `first token arriving after a delay still succeeds inside the longer budget`() = runBlocking {
        // Models the old 90s remote budget versus the new 600s self-hosted one:
        // a first chunk that arrives well after the short budget is accepted.
        val seen = mutableListOf<StreamEvent>()
        flow<StreamEvent> {
            delay(150)
            emit(StreamEvent.TextDelta("late"))
            emit(StreamEvent.Done("stop"))
        }.stallGuard(StreamTimeouts(500, 200), meaningful).collect { seen += it }
        assertEquals(listOf<StreamEvent>(StreamEvent.TextDelta("late"), StreamEvent.Done("stop")), seen)
    }

    // ---- guard: idle phase, keep-alives -------------------------------------

    @Test
    fun `keep-alives keep a quiet-but-live stream open between content chunks`() = runBlocking {
        val seen = mutableListOf<StreamEvent>()
        flow<StreamEvent> {
            emit(StreamEvent.TextDelta("a"))
            // Quiet longer than the idle budget in total, but every gap is short.
            repeat(5) { delay(40); emit(StreamEvent.Heartbeat) }
            emit(StreamEvent.TextDelta("b"))
            emit(StreamEvent.Done("stop"))
        }.stallGuard(StreamTimeouts(500, 120), meaningful).collect { seen += it }
        assertEquals(StreamEvent.TextDelta("a"), seen.first())
        assertEquals(StreamEvent.TextDelta("b"), seen[seen.size - 2])
        assertEquals(StreamEvent.Done("stop"), seen.last())
        assertEquals(5, seen.count { it is StreamEvent.Heartbeat })
    }

    @Test
    fun `keep-alives never satisfy the first-token deadline`() = runBlocking {
        // A server that pings forever without producing anything must still
        // fail: heartbeats prove liveness, not generation.
        val thrown = runCatching {
            flow<StreamEvent> {
                while (true) { emit(StreamEvent.Heartbeat); delay(20) }
            }.stallGuard(StreamTimeouts(120, 1_000), meaningful).collect { }
        }.exceptionOrNull()
        val stall = stallOf(thrown)
        assertTrue("expected a first-token stall, got $thrown", stall != null)
        assertEquals(StallPhase.FIRST_TOKEN, stall!!.phase)
    }

    @Test
    fun `a quiet gap after the first token is judged by the idle budget, not a stale first-token timer`() = runBlocking {
        val seen = mutableListOf<StreamEvent>()
        flow<StreamEvent> {
            emit(StreamEvent.TextDelta("first"))
            // Longer than the first-token budget, shorter than the idle budget.
            delay(250)
            emit(StreamEvent.TextDelta("second"))
            emit(StreamEvent.Done("stop"))
        }.stallGuard(StreamTimeouts(120, 400), meaningful).collect { seen += it }
        assertEquals(StreamEvent.TextDelta("second"), seen[seen.size - 2])
        assertEquals(StreamEvent.Done("stop"), seen.last())
    }

    @Test
    fun `a genuine inter-chunk silence fails on the idle budget`() = runBlocking {
        val thrown = runCatching {
            flow<StreamEvent> {
                emit(StreamEvent.TextDelta("start"))
                delay(400)
                emit(StreamEvent.TextDelta("too late"))
            }.stallGuard(StreamTimeouts(500, 80), meaningful).collect { }
        }.exceptionOrNull()
        val stall = stallOf(thrown)
        assertTrue("expected an idle stall, got $thrown", stall != null)
        assertEquals(StallPhase.IDLE, stall!!.phase)
    }

    // ---- guard: completion and cancellation ---------------------------------

    @Test
    fun `normal completion forwards every event including usage and done`() = runBlocking {
        val usage = StreamEvent.Usage(10, 5, cachedInputTokens = 2, cacheReported = true)
        val seen = mutableListOf<StreamEvent>()
        flowOf(StreamEvent.TextDelta("hi"), usage, StreamEvent.Done("stop"))
            .stallGuard(StreamTimeouts(500, 500), meaningful)
            .collect { seen += it }
        assertEquals(listOf<StreamEvent>(StreamEvent.TextDelta("hi"), usage, StreamEvent.Done("stop")), seen)
    }

    @Test
    fun `cancellation during the initial wait is not a stall`() = runBlocking {
        val job = launch {
            flow<StreamEvent> { awaitCancellation() }
                .stallGuard(StreamTimeouts(5_000, 5_000), meaningful)
                .collect { }
        }
        delay(50)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancellation after partial output is not a stall`() = runBlocking {
        val job = launch {
            flow<StreamEvent> {
                emit(StreamEvent.TextDelta("partial"))
                awaitCancellation()
            }.stallGuard(StreamTimeouts(5_000, 5_000), meaningful).collect { }
        }
        delay(50)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    // ---- retry safety --------------------------------------------------------

    @Test
    fun `first-token timeout retries before any output then succeeds`() = runBlocking {
        var attempt = 0
        val retries = mutableListOf<AgentEvent.Retrying>()
        val result = StreamRetrier.run(
            streamFor = {
                (attempt++).let { n ->
                    if (n == 0) flow<StreamEvent> { awaitCancellation() }
                    else flowOf(StreamEvent.TextDelta("recovered"), StreamEvent.Done("stop"))
                }
            },
            onAttemptStart = {}, hasOutput = { false }, handleEvent = { },
            retryReason = { it }, emitEvent = { retries += it }, sleep = { },
            timeouts = StreamTimeouts(120, 500),
        )
        assertEquals(null, result)
        assertEquals(1, retries.size)
        assertTrue(retries[0].reason.contains("initial response timed out"))
    }

    @Test
    fun `partial tool-call output blocks retry and is never replayed`() = runBlocking {
        var attempts = 0
        val calls = mutableListOf<ToolCallData>()
        val result = StreamRetrier.run(
            streamFor = {
                attempts++
                flowOf(
                    StreamEvent.ToolCallReady(ToolCallData("call_1", "write_file", "{\"path\":\"a\"}")),
                    StreamEvent.Failure("stream idle, timed out"),
                )
            },
            onAttemptStart = {}, hasOutput = { calls.isNotEmpty() },
            handleEvent = { if (it is StreamEvent.ToolCallReady) calls += it.call },
            retryReason = { it },
            emitEvent = { error("a completed tool call must never be replayed") }, sleep = { },
        )
        assertEquals("stream idle, timed out", result)
        assertEquals(1, attempts)
        assertEquals(1, calls.size)
    }

    @Test
    fun `user cancellation is never converted into a retry`() = runBlocking {
        var attempts = 0
        val thrown = runCatching {
            StreamRetrier.run(
                streamFor = { attempts++; throw CancellationException("user stopped") },
                onAttemptStart = {}, hasOutput = { false }, handleEvent = { },
                retryReason = { it },
                emitEvent = { error("cancellation must not retry") }, sleep = { },
            )
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(1, attempts)
    }

    // ---- timeout policy: defaults and validation ----------------------------

    @Test
    fun `hosted providers keep the fast default budgets`() {
        val t = StreamTimeoutPolicy.resolve(config(), onDeviceModel = false)
        assertEquals(90_000, t.firstTokenMs)
        assertEquals(90_000, t.idleMs)
    }

    @Test
    fun `on-device models keep the long prefill budget`() {
        // Preserves the historical single 600s window on both phases.
        val t = StreamTimeoutPolicy.resolve(config(), onDeviceModel = true)
        assertEquals(600_000, t.firstTokenMs)
        assertEquals(600_000, t.idleMs)
    }

    @Test
    fun `explicit self-hosted settings win`() {
        val t = StreamTimeoutPolicy.resolve(
            config(first = StreamTimeoutPolicy.SELF_HOSTED_FIRST_TOKEN_SEC, idle = StreamTimeoutPolicy.SELF_HOSTED_IDLE_SEC),
            onDeviceModel = false,
        )
        assertEquals(600_000, t.firstTokenMs)
        assertEquals(120_000, t.idleMs)
    }

    @Test
    fun `out-of-range values fall back to the provider default`() {
        val tooSmall = StreamTimeoutPolicy.resolve(config(first = 1, idle = 0), onDeviceModel = false)
        assertEquals(90_000, tooSmall.firstTokenMs)
        val tooLarge = StreamTimeoutPolicy.resolve(config(first = 0, idle = 999_999), onDeviceModel = false)
        assertEquals(90_000, tooLarge.idleMs)
    }

    @Test
    fun `timeouts must be positive`() {
        try {
            StreamTimeouts(0, 100)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `provider timeout settings round-trip and default to auto`() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(ProviderConfig.serializer(), config(first = 600, idle = 120))
        val decoded = json.decodeFromString(ProviderConfig.serializer(), encoded)
        assertEquals(600, decoded.firstTokenTimeoutSec)
        assertEquals(120, decoded.idleTimeoutSec)

        // An old saved provider (fields absent) decodes to auto (0).
        val legacy = json.decodeFromString(
            ProviderConfig.serializer(),
            """{"id":"x","name":"n","type":"OPENAI_COMPAT","baseUrl":"u","model":"m"}""",
        )
        assertEquals(0, legacy.firstTokenTimeoutSec)
        assertEquals(0, legacy.idleTimeoutSec)
    }
}
