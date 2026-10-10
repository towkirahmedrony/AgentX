package com.androidharness.app.tools

import com.androidharness.app.data.env.CodeGraphCommandResult
import com.androidharness.app.data.env.CodeGraphManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private fun CodeGraphCommandResult.asToolResult(): ToolResult = ToolResult(ok, output)

class CodeGraphExploreTool(private val codeGraph: CodeGraphManager) : Tool {
    override fun isAvailable(ctx: ToolContext): Boolean = codeGraph.isAvailable(ctx.workspace)
    override val name = "codegraph_explore"
    override val description =
        "Explore the indexed codebase with CodeGraph: relevant symbols' current source plus " +
            "caller/callee paths. Prefer it over grep or broad file reads when CodeGraph is enabled."
    override val parametersSchema = Schema.obj(
        mapOf("query" to Schema.string("Symbols, files, or a natural-language code question to explore.")),
        required = listOf("query"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (query.isEmpty()) throw ToolFailure("Missing required argument: query")
        return codeGraph.explore(ctx.workspace, query).asToolResult()
    }
}

class CodeGraphNodeTool(private val codeGraph: CodeGraphManager) : Tool {
    override fun isAvailable(ctx: ToolContext): Boolean = codeGraph.isAvailable(ctx.workspace)
    override val name = "codegraph_node"
    override val description =
        "Read one indexed symbol with its callers/callees, or an indexed file with line numbers and dependents."
    override val parametersSchema = Schema.obj(
        mapOf(
            "name" to Schema.string("Symbol name. Optional when file is provided."),
            "file" to Schema.string("Workspace-relative file path. Optional when name is provided."),
            "offset" to Schema.integer("Optional 1-based first line for file mode."),
            "limit" to Schema.integer("Optional maximum lines for file mode."),
        ),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args["name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val file = args["file"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        if (name == null && file == null) throw ToolFailure("codegraph_node needs name or file")
        return codeGraph.node(
            ctx.workspace,
            name,
            file,
            args["offset"]?.jsonPrimitive?.intOrNull,
            args["limit"]?.jsonPrimitive?.intOrNull,
        ).asToolResult()
    }
}

class CodeGraphImpactTool(private val codeGraph: CodeGraphManager) : Tool {
    override fun isAvailable(ctx: ToolContext): Boolean = codeGraph.isAvailable(ctx.workspace)
    override val name = "codegraph_impact"
    override val description = "Analyze what indexed code is affected by changing a symbol."
    override val parametersSchema = Schema.obj(
        mapOf(
            "symbol" to Schema.string("Symbol to analyze."),
            "depth" to Schema.integer("Dependency traversal depth, 1-10. Default 2."),
        ),
        required = listOf("symbol"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val symbol = args["symbol"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (symbol.isEmpty()) throw ToolFailure("Missing required argument: symbol")
        val depth = args["depth"]?.jsonPrimitive?.intOrNull ?: 2
        return codeGraph.impact(ctx.workspace, symbol, depth).asToolResult()
    }
}

class CodeGraphAffectedTool(private val codeGraph: CodeGraphManager) : Tool {
    override fun isAvailable(ctx: ToolContext): Boolean = codeGraph.isAvailable(ctx.workspace)
    override val name = "codegraph_affected"
    override val description = "Find tests affected by changed source files, using the CodeGraph dependency graph."
    override val parametersSchema = Schema.obj(
        mapOf(
            "files" to Schema.array(Schema.string("Workspace-relative changed source file."), "Changed source files."),
            "depth" to Schema.integer("Dependency traversal depth, 1-10. Default 5."),
        ),
        required = listOf("files"),
    )
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val files = args["files"]?.jsonArray?.mapNotNull { element ->
            element.jsonPrimitive.content.trim().takeIf { it.isNotEmpty() }
        }.orEmpty()
        if (files.isEmpty()) throw ToolFailure("codegraph_affected needs at least one file")
        val depth = args["depth"]?.jsonPrimitive?.intOrNull ?: 5
        return codeGraph.affected(ctx.workspace, files, depth).asToolResult()
    }
}

class CodeGraphSyncTool(private val codeGraph: CodeGraphManager) : Tool {
    override fun isAvailable(ctx: ToolContext): Boolean = codeGraph.isAvailable(ctx.workspace)
    override val name = "codegraph_sync"
    override val description = "Sync the workspace's existing CodeGraph index with files changed since the last index."
    override val parametersSchema = Schema.obj(emptyMap())
    override val isReadOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        codeGraph.syncWorkspace(ctx.workspace).asToolResult()
}
