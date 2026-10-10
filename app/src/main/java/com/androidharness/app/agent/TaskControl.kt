package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.llm.ProviderConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Serializable
data class QueuedPrompt(val id: String = UUID.randomUUID().toString(), val text: String)

@Serializable
data class TaskLimits(val tokens: Long = 0, val cost: Double = 0.0, val minutes: Long = 0) {
    init { require(tokens >= 0 && cost.isFinite() && cost >= 0 && minutes in 0..525600) }
}

@Serializable
data class TaskRecord(
    val status: String = "idle",
    val reason: String? = null,
    val provider: ProviderConfig? = null,
    val workspacePath: String = "",
    val mode: String = "ACT",
    val thinking: String = "OFF",
    val maxOutput: Int = 32768,
    val maxContext: Int = 1000000,
    val maxIterations: Int = 0,
    val turnId: String = "",
    val initialPrompt: String = "",
    val initialPromptId: String = "",
    val images: List<com.androidharness.app.core.ImageRef> = emptyList(),
    val queue: List<QueuedPrompt> = emptyList(),
    val pins: String = "",
    val summaryOverride: String? = null,
    val contextAfter: Long = 0,
    val limits: TaskLimits = TaskLimits(),
    val usedTokens: Long = 0,
    val usedCost: Double = 0.0,
    val elapsedMs: Long = 0,
    val autoContinue: Boolean = false,
    val autoContinueLimit: Int = 3,
    val autoContinueAttempts: Int = 0,
    /**
     * How the last run ended, as a [StopKind] name, and the numbers behind it.
     * Null while a run is in flight or before the first stop, so "no outcome
     * recorded" is never confused with a particular ending.
     */
    val outcome: String? = null,
    val outcomeDetail: String? = null,
    /**
     * A limit the user configured that could NOT be enforced (today: a cost
     * ceiling with no reliable price for the model). Informational: it is not a
     * stop reason, but silently ignoring a ceiling the user asked for would be
     * worse than continuing without it.
     */
    val limitsNotice: String? = null,
) {
    val resumable: Boolean get() = status == "running" || status == "paused" || status == "interrupted"
}

/** Private app storage, atomic replacement, no credentials. Writes finish before actions proceed. */
class TaskControlStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val flows = mutableMapOf<String, MutableStateFlow<TaskRecord>>()
    @Synchronized fun flow(id: String): MutableStateFlow<TaskRecord> = flows.getOrPut(id) {
        val file = file(id)
        MutableStateFlow(if (file.exists()) runCatching { json.decodeFromString<TaskRecord>(file.readText()).let { saved ->
                // Only disk restoration means a running task lost its process.
                if (saved.status == "running") saved.copy(status = "interrupted") else saved
            } }
            .getOrElse { TaskRecord(status = "paused", reason = "Saved task settings could not be read. Chat history is still available.") }
            else TaskRecord())
    }
    @Synchronized fun update(id: String, transform: (TaskRecord) -> TaskRecord): TaskRecord {
        val flow = flow(id)
        val next = transform(flow.value)
        directory.mkdirs()
        val target = file(id)
        val tmp = File(directory, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        check(tmp.renameTo(target)) { "Could not save task progress" }
        flow.value = next
        return next
    }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(directory, "$id.json")
    }
}

class TaskPaused(val reasonText: String, val kind: StopKind) : RuntimeException(reasonText)

/** Shared by the parent, compaction, and every subagent through coroutine context. */
class TaskBudget(
    private val limits: TaskLimits,
    private var tokens: Long,
    private var cost: Double,
    private val priorMs: Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TaskBudget>
    private val started = clock()

    /** Requests the provider reported usage for. */
    private var reportedRequests = 0

    /** Tokens charged because a request came back with no usage at all. */
    private var unsourcedTokens = 0L

    /**
     * Why a configured cost ceiling could not be enforced, if that happened.
     * Non-null disables cost enforcement: a monetary ceiling must never be
     * decided by a price the app guessed from the model's name.
     */
    private var costAdvice: String? = null

    @Synchronized fun add(input: Int, output: Int, dollars: Double?) {
        tokens += input.toLong() + output
        reportedRequests++
        cost += dollars ?: 0.0
    }

    /**
     * Charges one request the provider never reported usage for.
     *
     * Without this a token ceiling is unenforceable against a provider that
     * omits usage, because an unreported request would contribute zero and the
     * ceiling would never be reached — an unknown treated as a measurement of
     * nothing. The caller supplies a documented conservative figure instead.
     */
    @Synchronized fun chargeUnsourced(conservativeTokens: Long) {
        val charge = conservativeTokens.coerceAtLeast(0L)
        tokens += charge
        unsourcedTokens += charge
    }

    /** Records that a costed request had no reliable price. */
    @Synchronized fun markCostUnavailable(reason: String) {
        if (costAdvice == null) costAdvice = reason
    }

    @Synchronized fun usage(): Triple<Long, Double, Long> = Triple(tokens, cost, priorMs + clock() - started)

    /** Active time of this run, including time consumed before a resume. */
    @Synchronized fun elapsedMs(): Long = priorMs + clock() - started

    /**
     * Active time left before the configured time limit, or null when the user
     * set no time limit. Never negative: a spent budget reports zero rather than
     * a negative remainder.
     */
    @Synchronized fun remainingTimeMs(): Long? =
        if (limits.minutes <= 0) null else (limits.minutes * 60_000 - elapsedMs()).coerceAtLeast(0L)

    /** Whether the accumulated token figure is reported, estimated, or both. */
    @Synchronized fun usageStatus(): UsageStatus = when {
        reportedRequests > 0 && unsourcedTokens > 0 -> UsageStatus.MIXED
        unsourcedTokens > 0 -> UsageStatus.ESTIMATED
        reportedRequests > 0 -> UsageStatus.ACTUAL
        else -> UsageStatus.UNKNOWN
    }

    @Synchronized fun costAdvice(): String? = costAdvice

    @Synchronized fun check() {
        val u = usage()
        val reached: Pair<String, StopKind>? = when {
            limits.tokens > 0 && u.first >= limits.tokens ->
                "Token limit reached (${RunStop.usageLabel(usageStatus())} usage)" to StopKind.TOKEN_LIMIT
            // Only enforced against prices the app can stand behind; a guessed
            // price must never pause a run.
            limits.cost > 0 && costAdvice == null && u.second >= limits.cost ->
                "Estimated cost limit reached" to StopKind.COST_LIMIT
            limits.minutes > 0 && u.third >= limits.minutes * 60000 ->
                "Time limit reached" to StopKind.TIME_LIMIT
            else -> null
        }
        if (reached != null) throw TaskPaused(reached.first, reached.second)
    }
}

/** Close orphan tool calls without replaying operations whose outcome is unknown. */
object RunRecovery {
    const val CONTINUE_PROMPT = "Resume the interrupted task from saved progress. Completed tool results are authoritative. Inspect uncertain outcomes before further actions; do not repeat completed operations."
    fun missingResults(messages: List<ChatMessage>): List<ChatMessage> {
        val results = messages.filter { it.role == Role.TOOL }.mapNotNull { it.toolCallId }.toSet()
        return messages.filter { it.role == Role.ASSISTANT && it.toolCallId == null }
            .flatMap { it.toolCalls }.filter { it.id !in results }.map {
                ChatMessage(role = Role.TOOL, toolCallId = it.id, toolName = it.name, isError = true,
                    text = "Interrupted: outcome unknown. Inspect the current state before taking another action. Do not blindly repeat this operation.")
            }
    }
    fun context(messages: List<ChatMessage>, record: TaskRecord): List<ChatMessage> {
        val slice = messages.filter { it.createdAt >= record.contextAfter }
        val history = ContextHygiene.forModel(slice)
        return if (record.summaryOverride == null) history else
            listOf(ContextHygiene.summaryMessage(record.summaryOverride)) + history.filterNot {
                it.role == Role.SYSTEM && (it.text.startsWith(AgentEngine.COMPACTION_PREFIX) ||
                    it.text.startsWith(ContextHygiene.COMPACTION_NOTICE_PREFIX))
            }
    }
}

/** Successful writes from the interrupted task are never executed twice. */
class RecoveryLedger(messages: List<ChatMessage>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RecoveryLedger>
    private val outcomes = messages.filter { it.role == Role.TOOL }.associateBy { it.toolCallId }
    private val calls = messages.filter { it.role == Role.ASSISTANT && it.toolCallId == null }
        .flatMap { it.toolCalls }.associateBy { signature(it) }
    fun result(call: com.androidharness.app.core.ToolCallData): com.androidharness.app.tools.ToolResult? {
        val original = calls[signature(call)] ?: return null
        val outcome = outcomes[original.id]
        if (outcome != null && !outcome.isError) return com.androidharness.app.tools.ToolResult(true,
            "Already completed before interruption; not repeated.\n${outcome.text}")
        if (outcome == null || outcome.text.startsWith("Interrupted: outcome unknown"))
            return com.androidharness.app.tools.ToolResult(false,
                "This operation has an uncertain outcome from interruption. Inspect current state and choose a safe next step; it was not repeated.")
        return null
    }
    private fun signature(call: com.androidharness.app.core.ToolCallData): String =
        call.name + ":" + runCatching { Json.parseToJsonElement(call.argumentsJson).toString() }.getOrDefault(call.argumentsJson)
}
