package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageData
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.ToolSchema
import java.util.Base64
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Unified context accounting for one provider request.
 *
 * Every number this object produces is an APPROXIMATION. The app has no
 * provider tokenizer on device, so nothing here may be presented as exact
 * model usage; the authoritative number is the `Usage` event the provider
 * itself reports (see [ContextBudget]'s callers and the context panel). What
 * this object does guarantee is *coverage*: it accounts for every component the
 * app actually puts on the wire, so an estimate can no longer silently ignore
 * the system prompt, the tool schemas, tool-call arguments, replayed provider
 * reasoning or image payloads.
 *
 * Components counted, each exactly once (no component is counted twice, and
 * nothing that is already inside [systemPrompt] or [estimateToolsTokens] is
 * added again):
 *
 *  1. system instructions, including the workspace docs, memory, skills
 *     catalog and repository map the composer folded into that one string;
 *  2. tool schemas as serialized, including their JSON envelope;
 *  3. conversation history: message text, tool-call ids/names/arguments,
 *     provider reasoning carried back for stateless replay;
 *  4. images / multimodal payloads, which are binary and therefore NOT
 *     approximated from their base64 length;
 *  5. a fixed per-request framing allowance for what no payload models.
 *
 * Estimation method ([estimateTextTokens]) is deliberately safety-biased: it
 * separates ASCII from non-ASCII (CJK and emoji tokenize far worse than
 * English) and uses a slightly pessimistic ASCII ratio, so the estimate errs
 * high. An over-estimate only makes the agent compact a little earlier; an
 * under-estimate is what actually breaks a run.
 */
object ContextBudget {

    // ---- limits ------------------------------------------------------------

    /** Below this a context window cannot hold a useful agent request. */
    const val MIN_LIMIT_TOKENS = 8_192

    /**
     * Used only when NO limit can be determined at all: neither the user's
     * configured value nor a model catalog entry is available. Chosen because
     * it is a common floor for current hosted models; the point is that the app
     * never falls back to "unlimited".
     */
    const val FALLBACK_LIMIT_TOKENS = 128_000

    // ---- safety margin -----------------------------------------------------

    /** Share of the window held back for estimation error and model overshoot. */
    const val DEFAULT_SAFETY_MARGIN_PERCENT = 10
    const val MIN_SAFETY_MARGIN_PERCENT = 1
    const val MAX_SAFETY_MARGIN_PERCENT = 50
    const val MIN_SAFETY_MARGIN_TOKENS = 1_024
    const val MAX_SAFETY_MARGIN_TOKENS = 32_768

    // ---- framing / overhead ------------------------------------------------

    /** Role markers, request envelope and similar framing no payload models. */
    const val REQUEST_OVERHEAD_TOKENS = 256

    /** Providers that take an explicit thinking budget also raise `max_tokens`. */
    const val OUTPUT_BUDGET_HEADROOM = 4_096

    /** The usable input budget is never driven to (or below) zero. */
    const val MIN_USABLE_INPUT_TOKENS = 1_024

    // ---- images ------------------------------------------------------------

    /** Longest edge both major vision APIs keep after their own downscale. */
    const val MAX_IMAGE_EDGE = 1_568
    const val IMAGE_TILE_EDGE = 512

    /**
     * Upper bound for a single image, matching the worst case of the tile model
     * at [MAX_IMAGE_EDGE]. Used when the image's dimensions cannot be parsed.
     */
    const val IMAGE_TOKEN_CEILING = 3_300

    // ---- framing per element ----------------------------------------------

    /** Per-message role/separator framing the provider adds. */
    const val MESSAGE_FRAMING_TOKENS = 4

    /** `{"type":"function","function":{...}}` envelope around one tool schema. */
    const val TOOL_FRAMING_TOKENS = 16

    /**
     * ASCII characters per token. English text averages nearer 4.0; this is
     * deliberately lower so the estimate is pessimistic rather than optimistic.
     */
    private const val ASCII_CHARS_PER_TOKEN = 3.6

    /**
     * Non-ASCII characters per token. Majority-CJK text is commonly near 1.0
     * and older tokenizers are worse, so one token per character is the
     * conservative choice.
     */
    private const val NON_ASCII_CHARS_PER_TOKEN = 1.0

    // ---- results -----------------------------------------------------------

    /**
     * What was counted for one request, and whether it was counted exactly.
     * [exact] is false for every value produced here; it exists so callers can
     * never accidentally claim provider-exact accounting.
     */
    data class Accounting(
        val systemTokens: Int,
        val toolsTokens: Int,
        val messagesTokens: Int,
        val imagesTokens: Int,
        val overheadTokens: Int = REQUEST_OVERHEAD_TOKENS,
        val exact: Boolean = false,
    ) {
        val inputTokens: Int
            get() = systemTokens + toolsTokens + messagesTokens + imagesTokens + overheadTokens
    }

    /**
     * The resolved request budget: the window in use, how much of it is spoken
     * for, and therefore how much input the next request may occupy.
     */
    data class Budget(
        /** The context window actually used, after bounding by model metadata. */
        val limitTokens: Int,
        /** False only when [FALLBACK_LIMIT_TOKENS] had to stand in. */
        val limitKnown: Boolean,
        /** Output room held back: the provider will not accept more input than this allows. */
        val reservedOutputTokens: Int,
        /** Estimation-error allowance held back on top of the output reservation. */
        val safetyMarginTokens: Int,
    ) {
        /** Input the next request may occupy before compaction is required. */
        val usableInputTokens: Int
            get() = (limitTokens - reservedOutputTokens - safetyMarginTokens)
                .coerceAtLeast(MIN_USABLE_INPUT_TOKENS)

        /**
         * True when the window cannot even hold the output reservation and the
         * margin, so no input budget exists and compaction cannot fix it.
         */
        val exhaustedByReservations: Boolean
            get() = limitTokens - reservedOutputTokens - safetyMarginTokens <= 0

        fun exceeds(estimatedInputTokens: Int): Boolean = estimatedInputTokens > usableInputTokens

        /** Share of the window the estimate occupies, for diagnostics (0f..1f+). */
        fun usageFraction(estimatedInputTokens: Int): Float =
            if (limitTokens <= 0) 0f else estimatedInputTokens.toFloat() / limitTokens.toFloat()
    }

    // ---- limit resolution --------------------------------------------------

    /**
     * Resolves the window in use.
     *
     * A model-catalog value is a hard fact about the model, so it bounds the
     * user's setting rather than being replaced by it: `min(configured, known)`.
     * A user may configure a smaller window than the model allows (to save
     * money); they cannot legitimately configure a larger one. When only one of
     * the two is usable, that one wins. [FALLBACK_LIMIT_TOKENS] is returned,
     * with `known = false`, only when neither is usable, so an unset or invalid
     * limit never degrades into an assumed-unlimited window.
     */
    fun resolveLimit(configuredTokens: Int, knownModelTokens: Long?): Pair<Int, Boolean> {
        val known = knownModelTokens?.takeIf { it in MIN_LIMIT_TOKENS.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        val configured = configuredTokens.takeIf { it >= MIN_LIMIT_TOKENS }
        return when {
            known != null && configured != null -> min(configured, known) to true
            known != null -> known to true
            configured != null -> configured to true
            else -> FALLBACK_LIMIT_TOKENS to false
        }
    }

    /**
     * Output room to hold back. Providers in the explicit-budget family raise
     * `max_tokens` to at least `thinkingBudget + OUTPUT_BUDGET_HEADROOM` when
     * thinking is on, so reserving only [maxOutputTokens] would understate what
     * the same request must fit alongside its input. Effort-string providers
     * (OpenAI-style) do not change the output cap, and on-device models never
     * take a budget.
     */
    fun resolveOutputReservation(
        maxOutputTokens: Int,
        thinking: ThinkingLevel,
        providerType: ProviderType?,
    ): Int {
        val configured = maxOutputTokens.coerceAtLeast(0)
        val budgeted = when (providerType) {
            ProviderType.ANTHROPIC, ProviderType.GEMINI -> thinking.budgetTokens(configured)
            else -> 0
        }
        return if (budgeted > 0) max(configured, budgeted + OUTPUT_BUDGET_HEADROOM) else configured
    }

    /**
     * Estimation-error allowance: a share of the window, kept inside sane
     * bounds so a tiny window still gets a real margin and a huge one does not
     * reserve tens of thousands of tokens for nothing.
     */
    fun resolveSafetyMarginTokens(limitTokens: Int, percent: Int): Int {
        val share = (limitTokens.toLong() * percent.coerceIn(MIN_SAFETY_MARGIN_PERCENT, MAX_SAFETY_MARGIN_PERCENT) / 100L)
        return share.coerceIn(MIN_SAFETY_MARGIN_TOKENS.toLong(), MAX_SAFETY_MARGIN_TOKENS.toLong()).toInt()
    }

    fun resolve(
        configuredLimitTokens: Int,
        knownModelTokens: Long? = null,
        maxOutputTokens: Int,
        thinking: ThinkingLevel = ThinkingLevel.OFF,
        providerType: ProviderType? = null,
        safetyMarginPercent: Int = DEFAULT_SAFETY_MARGIN_PERCENT,
    ): Budget {
        val (limit, known) = resolveLimit(configuredLimitTokens, knownModelTokens)
        return Budget(
            limitTokens = limit,
            limitKnown = known,
            reservedOutputTokens = resolveOutputReservation(maxOutputTokens, thinking, providerType),
            safetyMarginTokens = resolveSafetyMarginTokens(limit, safetyMarginPercent),
        )
    }

    // ---- estimation --------------------------------------------------------

    /**
     * Conservative token estimate for text.
     *
     * This is NOT `length / 4`. ASCII and non-ASCII text are counted
     * separately because their token densities differ by roughly a factor of
     * four, and both ratios are chosen to over-estimate. The result is
     * approximate by construction and must always be labelled as such.
     */
    fun estimateTextTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var ascii = 0
        var nonAscii = 0
        for (ch in text) {
            if (ch.code < 0x80) ascii++ else nonAscii++
        }
        val tokens = ascii / ASCII_CHARS_PER_TOKEN + nonAscii / NON_ASCII_CHARS_PER_TOKEN
        return ceil(tokens).toInt().coerceAtLeast(1)
    }

    /** One tool call as it is replayed to the provider, reasoning included. */
    fun estimateToolCallTokens(call: ToolCallData): Int =
        estimateTextTokens(call.id) +
            estimateTextTokens(call.name) +
            estimateTextTokens(call.argumentsJson) +
            call.responseReasoning.sumOf { estimateTextTokens(it.toString()) }

    /** History text, tool-call payloads and framing, excluding images. */
    fun estimateMessagesTokens(history: List<ChatMessage>): Int =
        history.sumOf { message ->
            estimateTextTokens(message.text) +
                message.toolCalls.sumOf { estimateToolCallTokens(it) } +
                (message.toolCallId?.let { estimateTextTokens(it) } ?: 0) +
                MESSAGE_FRAMING_TOKENS
        }

    /**
     * Image cost, modelled the way the vision APIs count it (a tile grid plus a
     * base fee) and cross-checked against an area model, taking the larger of
     * the two. Base64 length is deliberately never used as a token proxy: a
     * 1 MB payload is ~1.4 M characters but only a few thousand tokens, so
     * counting characters would over-trim the history by two orders of
     * magnitude. When dimensions cannot be read the worst case is assumed.
     */
    fun estimateImageTokens(image: ImageData): Int {
        val dims = ImageDimensions.read(image) ?: return IMAGE_TOKEN_CEILING
        val width = min(dims.first, MAX_IMAGE_EDGE)
        val height = min(dims.second, MAX_IMAGE_EDGE)
        if (width <= 0 || height <= 0) return IMAGE_TOKEN_CEILING
        val tiles = ceilDiv(width, IMAGE_TILE_EDGE) * ceilDiv(height, IMAGE_TILE_EDGE)
        val tileModel = 85 + 170 * tiles
        val areaModel = ceil(width.toDouble() * height.toDouble() / 750.0).toInt()
        return max(tileModel, areaModel).coerceIn(1, IMAGE_TOKEN_CEILING)
    }

    fun estimateImagesTokens(history: List<ChatMessage>): Int =
        history.sumOf { message -> message.imageData.sumOf { estimateImageTokens(it) } }

    /**
     * Tool schemas as sent: name, description, parameter JSON, plus the
     * per-tool envelope the provider wraps them in.
     */
    fun estimateToolsTokens(tools: List<ToolSchema>): Int =
        tools.sumOf { tool ->
            TOOL_FRAMING_TOKENS +
                estimateTextTokens(tool.name) +
                estimateTextTokens(tool.description) +
                estimateTextTokens(tool.parametersJson.toString())
        }

    /**
     * Input room left for the conversation once the request's fixed components
     * are paid for: output room, safety margin, system prompt, tool schemas and
     * request framing.
     *
     * This is the number [ContextHygiene.trimToBudget] must be given, and it is
     * why a flat reserve is wrong: the system prompt alone (skills catalog,
     * workspace docs, memory, repository map) routinely exceeds the fixed
     * 8_000 tokens the old code subtracted for everything that is not history.
     * Never negative, so a window too small for its own reservations reports
     * zero instead of a nonsensical negative budget.
     */
    fun historyBudgetTokens(
        budget: Budget,
        systemPrompt: String,
        tools: List<ToolSchema>,
    ): Int = (
        budget.limitTokens -
            budget.reservedOutputTokens -
            budget.safetyMarginTokens -
            estimateTextTokens(systemPrompt) -
            estimateToolsTokens(tools) -
            REQUEST_OVERHEAD_TOKENS
        ).coerceAtLeast(0)

    fun accounting(
        history: List<ChatMessage>,
        systemPrompt: String,
        tools: List<ToolSchema>,
    ): Accounting = Accounting(
        systemTokens = estimateTextTokens(systemPrompt),
        toolsTokens = estimateToolsTokens(tools),
        messagesTokens = estimateMessagesTokens(history),
        imagesTokens = estimateImagesTokens(history),
    )

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

    /**
     * Image dimensions, parsed from the binary header only.
     *
     * PNG, JPEG and GIF are covered directly. Anything else (WebP, HEIC, an
     * unexpected container) returns null and the caller falls back to
     * [IMAGE_TOKEN_CEILING], which is conservative rather than wrong.
     * Pure JVM: no Android APIs, so it is unit-testable.
     */
    internal object ImageDimensions {

        /** Only the header is ever needed; never decode a whole photo. */
        private const val HEADER_BYTES = 8 * 1024

        fun read(image: ImageData): Pair<Int, Int>? {
            val head = decodePrefix(image.base64) ?: return null
            return when {
                isPng(head) -> png(head)
                isJpeg(head) -> jpeg(head)
                isGif(head) -> gif(head)
                else -> null
            }
        }

        private fun decodePrefix(base64: String): ByteArray? {
            if (base64.isEmpty()) return null
            // Decode only a bounded prefix; trim to a whole 4-character group so
            // the decoder never sees a truncated quantum.
            val usable = min(base64.length, HEADER_BYTES * 2)
            val group = usable - (usable % 4)
            if (group <= 0) return null
            return runCatching { Base64.getDecoder().decode(base64.substring(0, group)) }.getOrNull()
        }

        private fun isPng(bytes: ByteArray): Boolean =
            bytes.size >= 24 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() &&
                bytes[12] == 'I'.code.toByte() && bytes[13] == 'H'.code.toByte() &&
                bytes[14] == 'D'.code.toByte() && bytes[15] == 'R'.code.toByte()

        private fun png(bytes: ByteArray): Pair<Int, Int>? {
            val width = beInt(bytes, 16)
            val height = beInt(bytes, 20)
            return valid(width, height)
        }

        private fun isJpeg(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

        /**
         * Walks the JPEG marker chain to the first start-of-frame, which is
         * where the dimensions live. Segment lengths drive the walk, so an
         * EXIF/ICC block before the frame is skipped correctly.
         */
        private fun jpeg(bytes: ByteArray): Pair<Int, Int>? {
            var i = 2
            while (i + 9 < bytes.size) {
                if (bytes[i] != 0xFF.toByte()) {
                    i++
                    continue
                }
                val marker = bytes[i + 1].toInt() and 0xFF
                // Padding and standalone markers carry no length field.
                if (marker == 0xFF || marker == 0x01 || marker in 0xD0..0xD9) {
                    i += 2
                    continue
                }
                val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                if (length < 2) return null
                val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isFrame) {
                    val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                    val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                    return valid(width, height)
                }
                i += 2 + length
            }
            return null
        }

        private fun isGif(bytes: ByteArray): Boolean =
            bytes.size >= 10 &&
                bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()

        private fun gif(bytes: ByteArray): Pair<Int, Int>? {
            val width = leShort(bytes, 6)
            val height = leShort(bytes, 8)
            return valid(width, height)
        }

        private fun beInt(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 0xFF) shl 24) or
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                (bytes[at + 3].toInt() and 0xFF)

        private fun leShort(bytes: ByteArray, at: Int): Int =
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at].toInt() and 0xFF)

        private fun valid(width: Int, height: Int): Pair<Int, Int>? =
            if (width in 1..100_000 && height in 1..100_000) width to height else null
    }
}
