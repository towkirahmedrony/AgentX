package com.androidharness.app.llm

/** Rough list prices ($ per 1M tokens) for common model families, backed by models.dev. */
object ModelPrices {
    data class Cost(
        val input: Double, // $/1M prompt tokens
        val output: Double, // $/1M completion tokens
        /** Cache-read multiplier vs input price (0.1x to 0.5x). */
        val cacheRead: Double = 0.25,
        /** Cache-write multiplier vs input price (1.0x to 1.25x). */
        val cacheWrite: Double = 1.0,
    )

    private val table = listOf(
        // prefix to the *input* (input $/M, output $/M, cache-read $/M multiplier, cache-write $/M multiplier)
        "claude-opus-4" to Cost(15.0, 75.0, cacheRead = 0.1, cacheWrite = 1.25),
        "claude-opus" to Cost(15.0, 75.0, cacheRead = 0.1, cacheWrite = 1.25),
        "claude-sonnet-4.5" to Cost(3.0, 15.0, cacheRead = 0.1, cacheWrite = 1.25),
        "claude-sonnet-4" to Cost(3.0, 15.0, cacheRead = 0.1, cacheWrite = 1.25),
        "claude-sonnet" to Cost(3.0, 15.0, cacheRead = 0.1, cacheWrite = 1.25),
        "claude-3-7-sonnet" to Cost(3.0, 15.0, cacheRead = 0.1, cacheWrite = 1.25),
        "claude-3-5-sonnet" to Cost(3.0, 15.0, cacheRead = 0.1, cacheWrite = 1.25),
        "claude-haiku" to Cost(0.80, 4.0, cacheRead = 0.1, cacheWrite = 1.25),
        "gpt-4o-mini" to Cost(0.15, 0.60, cacheRead = 0.5, cacheWrite = 1.0),
        "gpt-4o" to Cost(2.50, 10.0, cacheRead = 0.5, cacheWrite = 1.0),
        "gpt-4.1-mini" to Cost(0.40, 1.60, cacheRead = 0.5, cacheWrite = 1.0),
        "gpt-4.1" to Cost(2.0, 8.0, cacheRead = 0.5, cacheWrite = 1.0),
        "gpt-5-mini" to Cost(0.25, 2.0, cacheRead = 0.25, cacheWrite = 1.0),
        "gpt-5" to Cost(1.25, 10.0, cacheRead = 0.25, cacheWrite = 1.0),
        "o1-mini" to Cost(1.10, 4.40, cacheRead = 0.5, cacheWrite = 1.0),
        "o1" to Cost(15.0, 60.0, cacheRead = 0.5, cacheWrite = 1.0),
        "o3-mini" to Cost(1.10, 4.40, cacheRead = 0.5, cacheWrite = 1.0),
        "o3" to Cost(10.0, 40.0, cacheRead = 0.5, cacheWrite = 1.0),
        "o4-mini" to Cost(1.10, 4.40, cacheRead = 0.5, cacheWrite = 1.0),
        "gemini-2.5-flash" to Cost(0.30, 2.50, cacheRead = 0.25, cacheWrite = 1.0),
        "gemini-2.5-pro" to Cost(1.25, 10.0, cacheRead = 0.25, cacheWrite = 1.0),
        "gemini-2.0-flash" to Cost(0.10, 0.40, cacheRead = 0.25, cacheWrite = 1.0),
        "gemini-1.5-pro" to Cost(1.25, 5.0, cacheRead = 0.25, cacheWrite = 1.0),
        "gemini-1.5-flash" to Cost(0.075, 0.30, cacheRead = 0.25, cacheWrite = 1.0),
        "deepseek-chat" to Cost(0.28, 0.42, cacheRead = 0.25, cacheWrite = 1.0),
        "deepseek-v3" to Cost(0.28, 0.42, cacheRead = 0.25, cacheWrite = 1.0),
        "deepseek-r1" to Cost(0.55, 2.19, cacheRead = 0.25, cacheWrite = 1.0),
        "llama-3.3-70b" to Cost(0.59, 0.79, cacheRead = 0.25, cacheWrite = 1.0),
        "llama-3.1-8b" to Cost(0.05, 0.08, cacheRead = 0.25, cacheWrite = 1.0),
        "llama-3.1-70b" to Cost(0.59, 0.79, cacheRead = 0.25, cacheWrite = 1.0),
        "llama-3.1-405b" to Cost(2.50, 5.0, cacheRead = 0.25, cacheWrite = 1.0),
        "qwen3" to Cost(0.30, 1.20, cacheRead = 0.25, cacheWrite = 1.0),
        "qwen-2.5-coder" to Cost(0.30, 1.20, cacheRead = 0.25, cacheWrite = 1.0),
        "mistral-large" to Cost(2.0, 6.0, cacheRead = 0.25, cacheWrite = 1.0),
        "mistral-small" to Cost(0.20, 0.60, cacheRead = 0.25, cacheWrite = 1.0),
        "codestral" to Cost(0.30, 0.90, cacheRead = 0.25, cacheWrite = 1.0),
        "grok-2" to Cost(2.0, 10.0, cacheRead = 0.25, cacheWrite = 1.0),
        "grok-beta" to Cost(5.0, 15.0, cacheRead = 0.25, cacheWrite = 1.0),
    )

    /**
     * Where a rate came from. A monetary ceiling may only be enforced against a
     * rate the app can stand behind, which is why the provenance travels with
     * the number instead of being discarded at the call site.
     */
    enum class PriceSource {
        /** On-device model: inference is free, and that is exact. */
        LOCAL,

        /** models.dev catalog: the provider's own published rate. */
        CATALOG,

        /** Version-controlled list of published list prices. */
        TABLE,

        /** Guessed from keywords in the model name. Show it, never enforce it. */
        HEURISTIC,
    }

    /** A rate together with its provenance. */
    data class PricedCost(val cost: Cost, val source: PriceSource) {
        /**
         * True when the rate is real enough to pause a run over. A keyword
         * guess is not: enforcing a cost ceiling against an invented price would
         * stop a user's work for a number the app made up.
         */
        val reliable: Boolean get() = source != PriceSource.HEURISTIC
    }

    /** The rate for [model], with its provenance. */
    fun priceFor(model: String, providerKey: String? = null): PricedCost {
        if (com.androidharness.app.local.LocalModelCatalog.isLocal(model)) {
            return PricedCost(Cost(0.0, 0.0, 0.0, 0.0), PriceSource.LOCAL)
        }
        // 1. Check live / cached ModelsDev catalog
        ModelsDev.findCost(providerKey, model)?.let {
            val cr = if (it.input > 0) (it.cacheRead / it.input) else 0.25
            val cw = if (it.input > 0) (it.cacheWrite / it.input) else 1.0
            return PricedCost(Cost(it.input, it.output, cr, cw), PriceSource.CATALOG)
        }

        // 2. Check offline static table
        val normalized = model.lowercase().replace(":", "-").replace("/", "-")
        val hit = table.firstOrNull { (prefix, _) ->
            val pNorm = prefix.lowercase().replace(":", "-").replace("/", "-")
            normalized.contains(pNorm) || pNorm.contains(normalized)
        }?.second
        if (hit != null) return PricedCost(hit, PriceSource.TABLE)

        // 3. Fallback heuristic based on model name keywords
        return PricedCost(heuristicFor(normalized), PriceSource.HEURISTIC)
    }

    fun costFor(model: String, providerKey: String? = null): Cost = priceFor(model, providerKey).cost

    private fun heuristicFor(normalized: String): Cost {
        return when {
            normalized.contains("free") ->
                Cost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0)
            normalized.contains("flash") || normalized.contains("mini") || normalized.contains("nano") || normalized.contains("8b") ->
                Cost(input = 0.15, output = 0.60, cacheRead = 0.25, cacheWrite = 1.0)
            normalized.contains("opus") || normalized.contains("large") || normalized.contains("max") || normalized.contains("405b") ->
                Cost(input = 5.0, output = 25.0, cacheRead = 0.25, cacheWrite = 1.0)
            normalized.contains("pro") || normalized.contains("sonnet") || normalized.contains("r1") || normalized.contains("70b") ->
                Cost(input = 1.5, output = 6.0, cacheRead = 0.25, cacheWrite = 1.0)
            else ->
                Cost(input = 0.50, output = 2.0, cacheRead = 0.25, cacheWrite = 1.0)
        }
    }

    /**
     * Estimated cost in USD for display. May rest on a keyword guess, which is
     * fine for a label and not fine for enforcement — callers that intend to act
     * on the number must consult [priceFor] and check
     * [PricedCost.reliable] first.
     */
    fun estimate(
        model: String,
        totalInputTokens: Long,
        outputTokens: Long,
        cachedTokens: Long = 0,
        cacheWriteTokens: Long = 0,
        providerKey: String? = null,
    ): Double? {
        val cost = costFor(model, providerKey)
        val uncached = (totalInputTokens - cachedTokens - cacheWriteTokens).coerceAtLeast(0)
        return (uncached / 1_000_000.0 * cost.input) +
            (cachedTokens / 1_000_000.0 * cost.input * cost.cacheRead) +
            (cacheWriteTokens / 1_000_000.0 * cost.input * cost.cacheWrite) +
            (outputTokens / 1_000_000.0 * cost.output)
    }
}
