package com.androidharness.app.agent

import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.HarnessProvider
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

    // A curated cloud brand versus a custom reverse-proxy endpoint (ngrok/Colab).
    private val hostedBase = "https://api.openai.com/v1"
    private val selfHostedBase = "https://r3-abcd.ngrok-free.app/v1"

    private fun config(
        first: Int = 0,
        idle: Int = 0,
        baseUrl: String = hostedBase,
        id: String = "p",
        type: ProviderType = ProviderType.OPENAI_COMPAT,
    ) = ProviderConfig(
        id = id, name = "Provider", type = type, baseUrl = baseUrl, model = "m",
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

    // ---- timeout policy: classification, defaults and validation -------------

    @Test
    fun `new self-hosted endpoint with unset timeouts gets the slow-model budget`() {
        val t = StreamTimeoutPolicy.resolve(config(baseUrl = selfHostedBase), onDeviceModel = false)
        assertEquals(600_000, t.firstTokenMs)
        assertEquals(120_000, t.idleMs)
    }

    @Test
    fun `existing custom endpoint with stored zero timeouts gets the self-hosted budget`() {
        // A stored 0 keeps its "inherit the category default" meaning; the
        // category default for a custom endpoint is the slow-model one.
        val t = StreamTimeoutPolicy.resolve(config(first = 0, idle = 0, baseUrl = selfHostedBase), onDeviceModel = false)
        assertEquals(600_000, t.firstTokenMs)
        assertEquals(120_000, t.idleMs)
    }

    @Test
    fun `restored legacy configuration without timeout fields resolves self-hosted`() {
        // A backup written before the fields existed decodes both to 0, and the
        // custom endpoint must still receive the slow-model budget.
        val legacy = Json { ignoreUnknownKeys = true }.decodeFromString(
            ProviderConfig.serializer(),
            """{"id":"x","name":"Colab Devstral","type":"OPENAI_COMPAT","baseUrl":"$selfHostedBase","model":"devstral-small-2-24b"}""",
        )
        assertEquals(0, legacy.firstTokenTimeoutSec)
        assertEquals(0, legacy.idleTimeoutSec)
        val t = StreamTimeoutPolicy.resolve(legacy, onDeviceModel = false)
        assertEquals(600_000, t.firstTokenMs)
        assertEquals(120_000, t.idleMs)
    }

    @Test
    fun `self-hosted endpoints never fall back to the fixed 90s initial window`() {
        val t = StreamTimeoutPolicy.resolve(config(baseUrl = selfHostedBase), onDeviceModel = false)
        assertTrue("expected > 90s, got ${t.firstTokenMs}", t.firstTokenMs > 90_000)
        assertEquals(StreamTimeoutPolicy.SELF_HOSTED_FIRST_TOKEN_SEC * 1000L, t.firstTokenMs)
        assertEquals(StreamTimeoutPolicy.SELF_HOSTED_IDLE_SEC * 1000L, t.idleMs)
    }

    @Test
    fun `explicit custom timeout values are respected`() {
        val t = StreamTimeoutPolicy.resolve(config(first = 45, idle = 90, baseUrl = selfHostedBase), onDeviceModel = false)
        assertEquals(45_000, t.firstTokenMs)
        assertEquals(90_000, t.idleMs)
    }

    @Test
    fun `explicit self-hosted defaults are preserved`() {
        val t = StreamTimeoutPolicy.resolve(
            config(
                first = StreamTimeoutPolicy.SELF_HOSTED_FIRST_TOKEN_SEC,
                idle = StreamTimeoutPolicy.SELF_HOSTED_IDLE_SEC,
                baseUrl = selfHostedBase,
            ),
            onDeviceModel = false,
        )
        assertEquals(600_000, t.firstTokenMs)
        assertEquals(120_000, t.idleMs)
    }

    @Test
    fun `hosted providers keep the fast default budgets`() {
        val t = StreamTimeoutPolicy.resolve(config(baseUrl = hostedBase), onDeviceModel = false)
        assertEquals(90_000, t.firstTokenMs)
        assertEquals(90_000, t.idleMs)
    }

    @Test
    fun `built-in and account providers keep the fast hosted budget`() {
        val harness = StreamTimeoutPolicy.resolve(HarnessProvider.config, onDeviceModel = false)
        assertEquals(90_000, harness.firstTokenMs)
        assertEquals(90_000, harness.idleMs)
        val chatgpt = StreamTimeoutPolicy.resolve(
            ProviderConfig(
                "chatgpt:abc", "ChatGPT", ProviderType.OPENAI_RESPONSES,
                "https://chatgpt.com/backend-api", "gpt-5",
            ),
            onDeviceModel = false,
        )
        assertEquals(90_000, chatgpt.firstTokenMs)
        assertEquals(90_000, chatgpt.idleMs)
    }

    @Test
    fun `hosted providers with explicit values still honour them`() {
        val t = StreamTimeoutPolicy.resolve(config(first = 120, idle = 30, baseUrl = hostedBase), onDeviceModel = false)
        assertEquals(120_000, t.firstTokenMs)
        assertEquals(30_000, t.idleMs)
    }

    @Test
    fun `on-device models keep the long prefill budget`() {
        // Preserves the historical single 600s window on both phases.
        val t = StreamTimeoutPolicy.resolve(config(), onDeviceModel = true)
        assertEquals(600_000, t.firstTokenMs)
        assertEquals(600_000, t.idleMs)
    }

    @Test
    fun `invalid values fall back to the category default`() {
        // Too small (below MIN_SEC) on a custom endpoint -> self-hosted default.
        val tooSmallCustom = StreamTimeoutPolicy.resolve(config(first = 1, idle = 0, baseUrl = selfHostedBase), onDeviceModel = false)
        assertEquals(600_000, tooSmallCustom.firstTokenMs)
        assertEquals(120_000, tooSmallCustom.idleMs)
        // Too large on a hosted brand -> hosted default.
        val tooLargeHosted = StreamTimeoutPolicy.resolve(config(first = 0, idle = 999_999, baseUrl = hostedBase), onDeviceModel = false)
        assertEquals(90_000, tooLargeHosted.idleMs)
        // Invalid first token but a valid custom idle value keeps the valid one.
        val mixed = StreamTimeoutPolicy.resolve(config(first = 2, idle = 150, baseUrl = selfHostedBase), onDeviceModel = false)
        assertEquals(600_000, mixed.firstTokenMs)
        assertEquals(150_000, mixed.idleMs)
    }

    @Test
    fun `resolved self-hosted budgets drive the stream guard and retrier`() = runBlocking {
        // Ties StreamTimeoutPolicy's numbers to the guard: the resolved budgets
        // are what StreamRetrier hands the guard, so a first-token stall reports
        // exactly those deadlines. The real budgets are minutes; scale both by
        // the same factor so the test stays fast while the report stays exact.
        val resolved = StreamTimeoutPolicy.resolve(config(baseUrl = selfHostedBase), onDeviceModel = false)
        val scaled = StreamTimeouts(
            firstTokenMs = resolved.firstTokenMs / 7_500,
            idleMs = resolved.idleMs / 1_500,
        )
        assertEquals(80L, scaled.firstTokenMs)
        assertEquals(80L, scaled.idleMs)

        val stall = stallOf(
            runCatching {
                flow<StreamEvent> { awaitCancellation() }
                    .stallGuard(scaled, meaningful)
                    .collect { }
            }.exceptionOrNull(),
        )
        assertTrue("expected a first-token stall", stall != null)
        assertEquals(StallPhase.FIRST_TOKEN, stall!!.phase)
        assertEquals(scaled.firstTokenMs, stall.timeoutMs)

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
            timeouts = scaled,
        )
        assertEquals(null, result)
        assertEquals(1, retries.size)
        assertTrue(retries[0].reason.contains("initial response timed out"))
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
