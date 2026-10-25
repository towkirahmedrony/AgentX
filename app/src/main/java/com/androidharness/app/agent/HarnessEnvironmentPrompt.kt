package com.androidharness.app.agent

import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.JsonPrimitive

/** Built from the same schemas supplied to the provider, never from a second tool catalog. */
internal object HarnessEnvironmentPrompt {
    const val SKILL = "androidharness-environment"
    fun value(value: String): String = JsonPrimitive(com.androidharness.app.tools.SecretRedactor.redact(value)).toString()

    fun toolIndex(tools: List<ToolSchema>): String = buildString {
        append("\n## Tools available for this request\n")
        append("The tool schemas attached to this request are authoritative. Use their exact names and parameters; a tool described in a guide may be absent here. Availability is not approval to mutate files, install packages, publish, or access another workspace.\n")
        if (tools.isEmpty()) { append("No tools are available for this request.\n"); return@buildString }
        tools.map { it.name }.distinct().sorted().groupBy(::family).forEach { (family, names) ->
            append("- $family: ").append(names.joinToString(", ") { value(it) }).append('\n')
        }
    }

    fun family(name: String): String = when {
        name.startsWith("mcp__") -> "Connected MCP extensions"
        name.startsWith("codegraph_") -> "CodeGraph"
        name.startsWith("git_") || name.startsWith("github_") -> "Git and GitHub"
        name.startsWith("browser_") || name in setOf("web_fetch", "web_search", "http_request") -> "Web and browser"
        name.startsWith("skill") -> "Skills"
        name in setOf("shell", "shell_background", "bg_list", "bg_kill", "env_status", "doctor", "read_logcat") || name.startsWith("pkg_") -> "Execution and environment"
        name.startsWith("memory_") || name in setOf("remember", "recall", "search_chats", "recent_chats", "read_chat_snippet") -> "Memory and conversation recall"
        name in setOf("task", "ask_user", "todo_write") -> "Coordination"
        name in setOf("create_artifact", "read_artifact", "read_tool_output", "read_chat_text", "offer_code_handoff") -> "Conversation and artifacts"
        else -> "Files and other tools"
    }

    fun skillGuide(content: String?): String = if (content == null) "" else "\n## AgentX operating guide\nFor workspace, path, execution-tier, package or build details, load the ${SKILL} skill with skill_view; the live environment section below stays authoritative.\n"
}
