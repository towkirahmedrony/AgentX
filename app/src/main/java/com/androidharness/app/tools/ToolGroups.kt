package com.androidharness.app.tools

import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Code-mode tool groups that stay out of the request until needed.
 * A group turns on from keywords in user messages, from earlier calls to its
 * tools, or from a tools_enable call. All three are derived from history, so
 * the tool list only grows within a chat and prompt caching stays stable.
 */
object ToolGroups {
    const val ENABLE_TOOL = "tools_enable"

    private val members: Map<String, Set<String>> = linkedMapOf(
        "browser" to setOf(
            "browser_navigate", "browser_type", "browser_click", "browser_read", "browser_wait_for",
            "browser_get_logs", "browser_eval", "browser_scroll", "browser_screenshot",
            "browser_open_external", "browser_refresh", "browser_get_url", "browser_back",
            "browser_get_dom", "browser_forward",
        ),
        "git" to setOf(
            "git_checkout", "git_push", "git_branch_manage", "git_log", "git_pull",
            "git_show", "git_commit", "git_diff", "git_branch", "git_status",
        ),
        "github" to setOf("github_api", "github_search", "github_file"),
        "codegraph" to setOf(
            "codegraph_node", "codegraph_explore", "codegraph_affected", "codegraph_impact", "codegraph_sync",
        ),
        "artifact" to setOf("create_artifact", "read_artifact", "offer_code_handoff"),
        "pkg" to setOf("pkg_install", "pkg_search", "pkg_list"),
        "history" to setOf("file_history", "file_restore"),
        "memory" to setOf(
            "memory_write", "memory_read", "memory_search", "search_chats",
            "recent_chats", "read_chat_snippet", "read_chat_text",
        ),
        "device" to setOf(
            "read_logcat", "doctor", "env_status", "shell_background", "bg_list", "bg_kill",
        ),
    )

    private val triggers: Map<String, Regex> = mapOf(
        "browser" to Regex("""\b(browser|screenshot|localhost|webview|web ?page|preview|dom)\b|https?://|ব্রাউজার|স্ক্রিনশট|প্রিভিউ""", RegexOption.IGNORE_CASE),
        "git" to Regex("""\b(git|commit|commits|push|pull|branch|merge|rebase|checkout|stash)\b|কমিট|পুশ|ব্রাঞ্চ|মার্জ""", RegexOption.IGNORE_CASE),
        "github" to Regex("""\b(github|pull request|issues?|gh)\b|গিটহাব""", RegexOption.IGNORE_CASE),
        "codegraph" to Regex("""\b(codegraph|call graph|callers?|impact analysis|dependents)\b""", RegexOption.IGNORE_CASE),
        "artifact" to Regex("""\b(artifacts?|handoff)\b|আর্টিফ্যাক্ট""", RegexOption.IGNORE_CASE),
        "pkg" to Regex("""\b(install|pkg|apt|packages?)\b|ইনস্টল|ইন্সটল|প্যাকেজ""", RegexOption.IGNORE_CASE),
        "history" to Regex("""\b(restore|undo|revert|file history|previous version)\b|রিস্টোর|আনডু""", RegexOption.IGNORE_CASE),
        "memory" to Regex("""\b(memory|memories|chat history|previous chat|earlier chat|last time)\b|মেমোরি|আগের চ্যাট|মনে রাখ""", RegexOption.IGNORE_CASE),
        "device" to Regex("""\b(logcat|crash|crashes|doctor|diagnos\w*|background|dev server|daemon)\b|ক্র্যাশ|লগক্যাট""", RegexOption.IGNORE_CASE),
    )

    private val owner: Map<String, String> =
        members.flatMap { (group, names) -> names.map { it to group } }.toMap()

    fun names(): Set<String> = members.keys

    fun filter(
        all: List<ToolSchema>,
        userTexts: List<String>,
        calledTools: List<String>,
        enableArgs: List<String>,
    ): List<ToolSchema> {
        val on = mutableSetOf<String>()
        calledTools.forEach { name -> owner[name]?.let { on += it } }
        enableArgs.forEach { args -> members.keys.forEach { g -> if (args.contains("\"$g\"")) on += g } }
        val text = userTexts.joinToString("\n")
        triggers.forEach { (g, re) -> if (re.containsMatchIn(text)) on += g }
        return all.filter { s ->
            val g = owner[s.name]
            g == null || g in on
        }
    }
}

class ToolsEnableTool : Tool {
    override val name = ToolGroups.ENABLE_TOOL
    override val description =
        "Load more tool groups for this chat; they appear on the next step. Groups: browser (web page automation), " +
            "git, github, codegraph, artifact, pkg (managed packages), history (file history/restore), " +
            "memory (memory notes and past chats), device (logcat, doctor, env status, background processes)."
    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("groups") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "Group names to enable")
            }
        }
        putJsonArray("required") { add("groups") }
    }
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = args["groups"]?.toString().orEmpty()
        val known = ToolGroups.names().filter { raw.contains("\"$it\"") }
        return if (known.isEmpty()) {
            ToolResult(false, "Unknown group. Available: " + ToolGroups.names().joinToString(", "))
        } else {
            ToolResult(true, "Enabled: " + known.joinToString(", ") + ". Their tools are available from the next step.")
        }
    }
}
