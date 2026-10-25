package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    /**
     * Fixtures are assembled from parts and kept deliberately low-entropy.
     * GitHub push protection reads test sources too and refuses to accept any
     * literal that looks like a live key, real or not, so the body is filler
     * that is still long enough to exercise the real formats.
     */
    private val filler = "Aa0".repeat(20)

    private val anthropic = "sk-ant-" + "api03-" + filler
    private val openaiProject = "sk-proj-" + filler
    private val openaiService = "sk-svcacct-" + filler
    private val groq = "gsk_" + filler
    private val tavily = "tvly-dev-" + filler
    private val gitlab = "glpat-" + filler
    private val stripe = "sk_live_" + filler
    private val telegram = "1234567890:" + filler

    @Test
    fun `common secret shapes are stripped`() {
        val raw = """
            token=sk-abcDEF1234567890xyz
            AWS AKIAIOSFODNN7EXAMPLE
            Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig
            GEMINI AIzaSyA-not-a-real-key-0123456789abcd
            password=hunter2
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC
            -----END PRIVATE KEY-----
        """.trimIndent()
        val out = SecretRedactor.redact(raw)
        assertFalse(out.contains("sk-abcDEF"))
        assertFalse(out.contains("AKIAIOSFODNN7EXAMPLE"))
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(out.contains("AIzaSyA-not-a-real-key"))
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("MIIEvQIBADANBgkqhkiG9w0BAQE"))
        assertTrue(out.contains("[redacted]"))
    }

    @Test
    fun `github tokens are stripped everywhere they appear`() {
        val raw = """
            export GH_TOKEN=gho_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8
            clone https://x-access-token:ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ123456@github.com/o/r.git
            remote: github_pat_AAAA0000bbbb1111CCCC2222dddd3333EEEE4444
        """.trimIndent()
        val out = SecretRedactor.redact(raw)
        assertFalse(out.contains("gho_A1b2C3d4"))
        assertFalse(out.contains("ghp_ABCDEFGHIJKLMNOP"))
        assertFalse(out.contains("github_pat_AAAA0000"))
        // the host and path survive the userinfo redaction
        assertTrue(out.contains("https://[redacted]@github.com/o/r.git"))
    }

    @Test
    fun `ordinary code is left alone`() {
        val src = "fun main() { println(\"hello\") }\nval api = 1"
        assertTrue(SecretRedactor.redact(src) == src)
    }

    @Test
    fun `plain urls without userinfo are untouched`() {
        val src = "https://github.com/towkirahmedrony/AgentX.git https://example.com/a?b=1"
        assertTrue(SecretRedactor.redact(src) == src)
    }

    // ------------------------------------------------------------------
    // Vendor prefixes. The old sk- rule needed pure alphanumerics right
    // after the dash, so every hyphenated key family walked straight out.
    // ------------------------------------------------------------------

    @Test
    fun `hyphenated api keys are stripped`() {
        val raw = "ANTHROPIC_API_KEY=$anthropic\nOPENAI_API_KEY=$openaiProject\nOPENAI=$openaiService"
        val out = SecretRedactor.redact(raw)
        assertFalse(out.contains(anthropic))
        assertFalse(out.contains(openaiProject))
        assertFalse(out.contains(openaiService))
        assertFalse(out.contains("sk-ant-"))
        assertFalse(out.contains("sk-proj-"))
        assertFalse(out.contains("sk-svcacct-"))
    }

    @Test
    fun `vendor prefixed keys are stripped`() {
        val xai = "xai-" + filler
        val slack = "xoxb-" + filler
        val huggingFace = "hf_" + filler
        val raw = """
            GROQ_API_KEY=$groq
            TAVILY_API_KEY=$tavily
            XAI=$xai
            GITLAB=$gitlab
            STRIPE=$stripe
            SLACK=$slack
            HF=$huggingFace
            TG=$telegram
        """.trimIndent()
        val out = SecretRedactor.redact(raw)
        for (secret in listOf(groq, tavily, xai, gitlab, stripe, slack, huggingFace, telegram)) {
            assertFalse("leaked: $secret", out.contains(secret))
        }
    }

    // ------------------------------------------------------------------
    // The boundary bug. `\b` before a name never holds after the underscore
    // in ANTHROPIC_API_KEY, so whole env dumps used to pass through.
    // ------------------------------------------------------------------

    @Test
    fun `env dump values are stripped but names stay readable`() {
        val raw = """
            ANTHROPIC_API_KEY=$anthropic
            OPENAI_API_KEY=$openaiProject
            GROQ_API_KEY=$groq
            TAVILY_API_KEY=$tavily
            AWS_SECRET_ACCESS_KEY=$filler
            SECRET_KEY_BASE=$filler
            DATABASE_PASSWORD=hunter2hunter2hunter2
        """.trimIndent()
        val out = SecretRedactor.redact(raw)
        for (secret in listOf(anthropic, openaiProject, groq, tavily, filler, "hunter2hunter2hunter2")) {
            assertFalse("leaked: $secret", out.contains(secret))
        }
        // the names survive so the model knows what it lost
        assertTrue(out.contains("ANTHROPIC_API_KEY=[redacted]"))
        assertTrue(out.contains("GROQ_API_KEY=[redacted]"))
        assertTrue(out.contains("SECRET_KEY_BASE=[redacted]"))
    }

    @Test
    fun `json and shell forms are stripped`() {
        val raw = """
            {"anthropic_api_key": "$anthropic", "note": "x"}
            export GROQ_API_KEY="$groq"
            -H "x-api-key: $anthropic"
            DATABASE_URL=postgres://appuser:hunter2sword@db.internal:5432/app
        """.trimIndent()
        val out = SecretRedactor.redact(raw)
        assertFalse(out.contains(anthropic))
        assertFalse(out.contains(groq))
        assertFalse(out.contains("hunter2sword"))
        // the host and port stay so the model can still describe the target
        assertTrue(out.contains("db.internal:5432/app"))
    }

    // ------------------------------------------------------------------
    // False positives. Over-redaction breaks the agent: it has to feed
    // commit SHAs, hashes and paths back into later commands.
    // ------------------------------------------------------------------

    @Test
    fun `hashes shas and base64 survive`() {
        val raw = """
            commit 6f1b2c9a4d8e7f0a1b2c3d4e5f6a7b8c9d0e1f2a
            sha256 e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
            data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8AAAwAB/AGtQ0oAAAAASUVORK5CYII=
        """.trimIndent()
        assertEquals(raw, SecretRedactor.redact(raw))
    }

    @Test
    fun `paths and prose survive`() {
        val raw = """
            /data/data/com.androidharness.app/files/workspace/src/main/kotlin/Main.kt
            PWD=/home/sanuu/sanukey/coding/AgentX
            The quick brown fox jumps over the lazy dog and then wanders off again.
            http://127.0.0.1:8080/health
        """.trimIndent()
        assertEquals(raw, SecretRedactor.redact(raw))
    }

    @Test
    fun `identifiers that merely contain a keyword survive`() {
        val raw = """
            monkey = "abcdefghijklmnopqrstuv"
            keyName = "someLongIdentifierValue"
            keyboard_layout = "QWERTYUIOPASDFGHJKLZXC"
            tokenizer = "notasecretvalueatall"
            fun configure(apiKey: String) { }
            val secretManager = SecretManager(context)
        """.trimIndent()
        assertEquals(raw, SecretRedactor.redact(raw))
    }

    @Test
    fun `redaction is idempotent`() {
        val raw = "ANTHROPIC_API_KEY=$anthropic\nGROQ_API_KEY=$groq\npassword=hunter2"
        val once = SecretRedactor.redact(raw)
        assertEquals(once, SecretRedactor.redact(once))
    }
}