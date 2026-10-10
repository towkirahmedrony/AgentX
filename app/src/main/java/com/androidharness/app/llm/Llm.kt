package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ToolCallData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
enum class ProviderType(val label: String, val defaultBaseUrl: String) {
    OPENAI_COMPAT("OpenAI-compatible", "https://api.openai.com/v1"),
    OPENAI_RESPONSES("OpenAI Responses", "https://api.openai.com/v1"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta"),
}

/**
 * Canonical endpoints of the curated cloud providers.
 *
 * A saved provider whose (type, base URL) matches one of these is a known
 * hosted service and keeps the fast-fail streaming budget. Anything else, a
 * custom OpenAI-compatible endpoint, a local server (Ollama, LM Studio) or a
 * reverse proxy (ngrok, Colab), is self-hosted and gets the slow-model budget.
 *
 * This is the single source of truth: the provider picker builds its brand list
 * from these constants and the streaming timeout policy classifies saved
 * configs against them, so the two can never disagree. Classification is an
 * exact (type, base URL) match, never a URL substring guess.
 */
object HostedProviderEndpoints {
    const val OPENROUTER = "https://openrouter.ai/api/v1"
    const val ANTHROPIC = "https://api.anthropic.com"
    const val GEMINI = "https://generativelanguage.googleapis.com/v1beta"
    const val OPENAI = "https://api.openai.com/v1"
    const val GROQ = "https://api.groq.com/openai/v1"
    const val DEEPSEEK = "https://api.deepseek.com/v1"
    const val TOGETHER = "https://api.together.xyz/v1"
    const val MISTRAL = "https://api.mistral.ai/v1"

    /** Both wire and address must match, so a proxy reusing a hosted host stays custom. */
    private val PAIRS: Set<Pair<ProviderType, String>> = setOf(
        ProviderType.OPENAI_COMPAT to OPENROUTER,
        ProviderType.ANTHROPIC to ANTHROPIC,
        ProviderType.GEMINI to GEMINI,
        ProviderType.OPENAI_COMPAT to OPENAI,
        ProviderType.OPENAI_RESPONSES to OPENAI,
        ProviderType.OPENAI_COMPAT to GROQ,
        ProviderType.OPENAI_COMPAT to DEEPSEEK,
        ProviderType.OPENAI_COMPAT to TOGETHER,
        ProviderType.OPENAI_COMPAT to MISTRAL,
    )

    /** Exact configuration match; surrounding whitespace, case and a trailing slash are ignored. */
    fun isHosted(type: ProviderType, baseUrl: String): Boolean =
        (type to normalize(baseUrl)) in PAIRS

    private fun normalize(baseUrl: String): String = baseUrl.trim().trimEnd('/').lowercase()
}

/**
 * Local servers (Ollama, LM Studio, llama.cpp) need no key, and an empty
 * `Authorization` header makes some of them reject the request. A key the user
 * typed still rides out, so a reverse proxy in front of one keeps working.
 */
fun ProviderConfig.effectiveApiKey(stored: String?): String =
    stored?.takeIf { it.isNotBlank() }
        ?: if (OpenAiCompatProvider.isLocalHost(baseUrl)) OpenAiCompatProvider.LOCAL_KEY else ""

/** Wire-endpoint naming for the protocol picker (what the API actually speaks). */
val ProviderType.endpointPath: String
    get() = when (this) {
        ProviderType.OPENAI_COMPAT -> "/chat/completions"
        ProviderType.OPENAI_RESPONSES -> "/v1/responses"
        ProviderType.ANTHROPIC -> "/v1/messages"
        ProviderType.GEMINI -> ":streamGenerateContent"
    }

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val model: String,
    /**
     * Budget, in seconds, from request start to the first meaningful response
     * event. A large self-hosted model can spend minutes in cold start, model
     * load or prompt prefill before its first token. 0 means "use the default
     * for this provider category" (fast hosted APIs get a short budget, so a
     * silent gateway still surfaces quickly).
     */
    val firstTokenTimeoutSec: Int = 0,
    /**
     * Budget, in seconds, between streaming events once generation started.
     * Keep-alive frames reset it; a genuinely silent stream fails. 0 means
     * "use the default for this provider category".
     */
    val idleTimeoutSec: Int = 0,
)

data class ToolSchema(
    val name: String,
    val description: String,
    val parametersJson: JsonObject,
)

/**
 * Per-request generation options derived from user settings.
 *
 * [cacheKey] is a stable per-session identifier (the session id). Providers use
 * it where the API supports explicit cache routing, OpenAI's `prompt_cache_key`
 * and Anthropic's `metadata.user_id`, so consecutive requests of one session
 * land on the same cache shard.
 */
data class RequestOptions(
    val maxOutputTokens: Int = 32_768,
    val thinking: com.androidharness.app.agent.ThinkingLevel =
        com.androidharness.app.agent.ThinkingLevel.OFF,
    val cacheKey: String? = null,
)

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ThinkingDelta(val text: String) : StreamEvent
    data class ToolCallReady(val call: ToolCallData) : StreamEvent
    data class ToolCallBatch(val calls: List<ToolCallData>) : StreamEvent
    data class Batch(val events: List<StreamEvent>) : StreamEvent

    /**
     * One per request, emitted once the final counts are known.
     *
     * [inputTokens] is the TOTAL prompt size, uncached input + tokens served
     * from the cache + tokens written to the cache, so the cache hit rate is
     * simply `cachedInputTokens / inputTokens` for every provider.
     * [cachedInputTokens] counts cache reads only; [cacheWriteTokens]
     * (Anthropic) are billed at the 1.25x write premium and are reported
     * separately for honest cost math.
     */
    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedInputTokens: Int = 0,
        val cacheWriteTokens: Int = 0,
        val cacheReported: Boolean = false,
    ) : StreamEvent
    /** [code] is the provider's error status, even when the SSE HTTP response is 200. */
    data class Failure(val message: String, val code: Int? = null) : StreamEvent

    /**
     * Emitted once per request when the stream terminates. [finishReason] is
     * the provider's raw termination code, "stop", "length", "tool_calls",
     * Anthropic's "max_tokens"/"end_turn"/"tool_use", Gemini's "STOP"/
     * "MAX_TOKENS", or null when the server never reported one.
     */
    data class Done(val finishReason: String? = null) : StreamEvent

    /**
     * Transport liveness with no model output: an SSE comment (": ping"), an
     * `event:`/`id:`/`retry:` field, or any other keep-alive byte the parser
     * can observe. It exists so a stream guard can distinguish "the connection
     * is alive, the model is still thinking" from "the socket died". It must
     * never be treated as assistant output or as a first token.
     */
    data object Heartbeat : StreamEvent
}

/**
 * Marker payload [ProviderFactory.sseJson] emits for an SSE line that carries
 * transport activity but no model data. Content parsers see a non-chunk
 * [JsonObject] and ignore it; providers that care translate it into
 * [StreamEvent.Heartbeat].
 */
private const val ACTIVITY_MARKER = "\u0000harness-sse-activity"

internal val ACTIVITY_FRAME: JsonElement = JsonPrimitive(ACTIVITY_MARKER)

/** True for the transport-activity marker emitted for SSE keep-alive lines. */
internal fun JsonElement.isTransportActivity(): Boolean =
    this is JsonPrimitive && this.isString && this.content == ACTIVITY_MARKER

class ApiException(val code: Int, message: String) : IOException("HTTP $code: $message")

/**
 * [JsonObject] accessor that treats an explicit JSON `null` exactly like an
 * absent field. Several gateways emit `"usage": null`, `"delta": null`, etc.;
 * the strict `.jsonObject` throws on those and kills the stream.
 */
fun JsonElement?.jsonObjectOrAbsent(): JsonObject? = this as? JsonObject

/** [JsonArray] accessor treating an explicit JSON `null` like an absent field. */
fun JsonElement?.jsonArrayOrAbsent(): JsonArray? = this as? JsonArray

interface LlmProvider {
    fun streamChat(
        config: ProviderConfig,
        apiKey: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        options: RequestOptions,
    ): Flow<StreamEvent>
}

object ProviderFactory {
    val json: Json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE streams are long-lived
        .build()

    @Volatile var localProvider: LlmProvider? = null
    @Volatile var chatGptProvider: LlmProvider? = null

    fun create(config: ProviderConfig): LlmProvider = when {
        com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(config.id) ->
            checkNotNull(chatGptProvider) { "ChatGPT connection is not initialized." }
        com.androidharness.app.local.LocalModelCatalog.isLocal(config.id) || config.baseUrl.startsWith("local://") ->
            checkNotNull(localProvider) { "Local inference is not initialized." }
        config.id == HarnessProvider.ID -> HarnessProvider.create()
        else -> create(config.type)
    }

    fun create(type: ProviderType): LlmProvider = when (type) {
        ProviderType.OPENAI_COMPAT -> OpenAiCompatProvider(client, json)
        ProviderType.OPENAI_RESPONSES -> OpenAiResponsesProvider(client, json)
        ProviderType.ANTHROPIC -> AnthropicProvider(client, json)
        ProviderType.GEMINI -> GeminiProvider(client, json)
    }

    /**
     * POSTs [request] and parses the response body as server-sent events,
     * emitting the JSON payload of each `data:` line. Cancellation of the
     * collector cancels the underlying HTTP call.
     */
    fun sseJson(request: Request, httpClient: OkHttpClient = client): Flow<JsonElement> = callbackFlow {
        val call = httpClient.newCall(request)
        val reader = launch(Dispatchers.IO) {
            try {
                val response = kotlinx.coroutines.suspendCancellableCoroutine<Response> { continuation ->
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (continuation.isActive) continuation.resumeWith(Result.success(response))
                            else response.close()
                        }
                    })
                }
                response.use {
                    if (!it.isSuccessful) {
                        val errBody = try { it.body?.string()?.take(2000) } catch (_: Exception) { null }
                        throw ApiException(it.code, errBody ?: it.message)
                    }
                    val source = it.body!!.source()
                    while (true) {
                        val line = readSseLine(source) ?: break
                        // SSE comment lines (": ping") and non-data fields
                        // (event:, id:, retry:) are pure transport keep-alives.
                        // Surface them so the stream guard resets its idle
                        // timer on a live-but-quiet connection instead of
                        // treating silence as a dead socket. Blank separators
                        // between events are NOT activity, a data frame will
                        // follow and reset the guard on its own.
                        if (line.isNotEmpty() && !line.startsWith("data:")) {
                            send(ACTIVITY_FRAME)
                            continue
                        }
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        if (payload.isEmpty()) continue
                        try {
                            send(json.parseToJsonElement(payload))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: kotlinx.serialization.SerializationException) {
                            // Skip malformed keep-alive lines, never drop valid stream chunks.
                        }
                    }
                }
                close()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Reads one SSE line from [source]. Lines terminate at '\n' with one
     * immediately-preceding '\r' stripped (the CRLF form). Interior CR
     * bytes anywhere else in the body are PRESERVED, unlike
     * BufferedReader.readLine() / okio's readUtf8Line(), which treat every
     * lone CR as a line break too. Some remote hosts emit unescaped CR
     * characters inside streamed JSON strings (tool-argument content among
     * them); with a standard reader those vanish silently and files
     * written through write_file come back with their carriage returns
     * stripped (CRLF corrupted into LF).
     */
    fun readSseLine(source: okio.BufferedSource): String? {
        if (source.exhausted()) return null
        val lf = source.indexOf('\n'.code.toByte())
        return if (lf >= 0) {
            val line = source.readUtf8(lf)
            source.skip(1) // the '\n'
            if (line.endsWith("\r")) line.dropLast(1) else line
        } else {
            val tail = source.readUtf8()
            if (tail.endsWith("\r")) tail.dropLast(1) else tail
        }
    }
}
