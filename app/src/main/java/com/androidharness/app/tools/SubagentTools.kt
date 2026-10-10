package com.androidharness.app.tools

import kotlinx.serialization.json.JsonObject

/**
 * Delegates a task to a nested agent with its own context window. The engine
 * intercepts calls to this tool and runs the subagent loop itself. Spawning
 * is read-only; action tools inside the subagent use their own permission gates.
 */
class TaskTool : Tool {
    override val name = "task"
    override val description =
        "Run a subagent with its own context; only its final answer returns. Use for delegated " +
        "work whose raw output would bloat this conversation. Read-only unless Subagent action " +
        "tools is enabled (then it edits and runs tools in Act mode under the permission mode). " +
        "Issue ALL independent task calls in ONE message: they run concurrently. Pass `model` " +
        "(a catalog id) for a cheaper/faster model; a Sub-Agent settings model overrides it. " +
        "Subagents cannot ask questions or spawn subagents."
    override val parametersSchema = Schema.obj(
        mapOf(
            "prompt" to Schema.string(
                "The task and everything the subagent needs; it does not see this conversation.",
            ),
            "title" to Schema.string(
                "Short label on the subagent's progress, e.g. 'Find config sources'.",
            ),
            "model" to Schema.string(
                "Optional catalog model id for the subagent (default: this conversation's " +
                    "model). A model set in Sub-Agent settings takes precedence; unknown ids are " +
                    "rejected with the valid list.",
            ),
        ),
        required = listOf("prompt"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        ToolResult(false, "task is handled by the engine and cannot be executed directly.")
}
