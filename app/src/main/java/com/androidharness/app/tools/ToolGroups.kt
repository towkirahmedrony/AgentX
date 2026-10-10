package com.androidharness.app.tools

import kotlinx.serialization.json.JsonObject

/**
 * A coarse capability group. Staged tool exposure sends only the groups in
 * [ToolGroups.initialGroups] on the first request; the rest are withheld until
 * the model calls one of their tools (or asks for them with `tool_search`).
 *
 * Membership is centralised by tool NAME rather than declared on every Tool
 * class, so the mapping is one reviewable table and the 50+ tool
 * implementations stay untouched.
 */
enum class ToolGroup(val id: String) {
    /** Conversation, file, shell, memory, todo, subagents and tool discovery. */
    CORE("core"),
    GIT("git"),
    NET("net"),
    DIAGNOSTICS("diagnostics"),
    PACKAGES("packages"),
    CODEGRAPH("codegraph"),
    SKILLS_ADMIN("skills_admin"),
    BROWSER("browser"),
}

/**
 * Static name -> [ToolGroup] mapping.
 *
 * Anything not listed ([ToolGroup.CORE] by default) is always sent, which is
 * what keeps MCP tools, run-scoped extras and the `tool_search` meta-tool from
 * ever being withheld. Deferring a group never removes a capability: the tool
 * still exists in the registry and is executed normally if the model calls it;
 * withholding only omits its JSON schema from the request to save input tokens.
 */
object ToolGroups {

    // Read-only git inspection (git_status/git_diff/git_log) is deliberately NOT
    // listed here, so it falls into CORE and is always sent. Only the mutating
    // and advanced git operations are deferred; shell can still run any git
    // command, and a direct call to a deferred one executes normally.
    private val GIT = setOf(
        "git_show", "git_commit", "git_branch", "git_checkout",
        "git_push", "git_pull", "git_branch_manage",
    )
    private val NET = setOf("web_fetch", "web_search", "http_request")
    private val DIAGNOSTICS = setOf("env_status", "doctor", "read_logcat")
    private val PACKAGES = setOf("pkg_install", "pkg_search", "pkg_list")
    private val CODEGRAPH = setOf(
        "codegraph_node", "codegraph_explore", "codegraph_impact",
        "codegraph_affected", "codegraph_sync",
    )
    private val SKILLS_ADMIN = setOf("skills_list", "skill_manage")

    /** The group a tool belongs to. Unknown names map to [ToolGroup.CORE]. */
    fun groupFor(name: String): ToolGroup = when {
        name in GIT -> ToolGroup.GIT
        name in NET -> ToolGroup.NET
        name in DIAGNOSTICS -> ToolGroup.DIAGNOSTICS
        name in PACKAGES -> ToolGroup.PACKAGES
        name in CODEGRAPH -> ToolGroup.CODEGRAPH
        name in SKILLS_ADMIN -> ToolGroup.SKILLS_ADMIN
        name.startsWith("browser_") -> ToolGroup.BROWSER
        else -> ToolGroup.CORE
    }

    /**
     * Groups sent on the first request. CORE (conversation, files, shell,
     * memory, todo, subagents, tool discovery and read-only git inspection) and
     * NET (web research) cover ordinary coding and research without activation.
     * Everything situational (git mutations, diagnostics, packages, CodeGraph,
     * skills management, browser) is withheld and activated on demand, which is
     * where most of the token saving comes from.
     */
    fun initialGroups(): Set<ToolGroup> = setOf(ToolGroup.CORE, ToolGroup.NET)

    /** Resolves a group id ("git", "browser", …) back to its enum value. */
    fun byId(id: String): ToolGroup? = ToolGroup.entries.firstOrNull { it.id == id.trim().lowercase() }

    /** Every group, in declaration order (used to activate all groups at once). */
    val all: List<ToolGroup> = ToolGroup.entries
}

/**
 * Lets the model discover tools that were withheld from the current request.
 *
 * The engine intercepts calls to this tool (it is never executed directly, like
 * `ask_user` and `task`), returns the matching tool names and descriptions, and
 * activates their groups so the tools are callable on the next step.
 */
class ToolSearchTool : Tool {
    override val name = "tool_search"
    override val description =
        "Discover agent tools withheld from this request. Pass a keyword: a capability, a " +
            "tool name, or a group (git, web, diagnostics, packages, codegraph, skills_admin, " +
            "browser). Returns matching tool names and descriptions; the matching tools become " +
            "callable on the next step. Use it when you need a capability you cannot see."
    override val parametersSchema = Schema.obj(
        mapOf(
            "query" to Schema.string("Keyword matched against tool names, descriptions and groups."),
            "group" to Schema.string(
                "Optional exact group: core | git | net | diagnostics | packages | codegraph | skills_admin | browser.",
            ),
        ),
        required = listOf("query"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        ToolResult(false, "tool_search is handled by the engine and cannot be executed directly.")
}
