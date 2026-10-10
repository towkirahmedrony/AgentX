package com.androidharness.app.tools

import com.androidharness.app.workspace.FsNode
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Staged tool exposure must expose only the active groups, never change a
 * tool's schema, and only ever ADD schemas as groups activate (so the tools
 * already on the wire stay in the same relative order for prompt caching).
 */
class ToolRegistryStagingTest {

    private val workspace = object : WorkspaceFs {
        override val displayPath = "/workspace"
        override val shellRoot: File? = File("/workspace")
        override val isSaf = false
        override fun resolve(path: String): FsNode = throw UnsupportedOperationException()
        override fun walk(path: String): Sequence<FsNode> = emptySequence()
    }

    private fun tool(toolName: String, readOnly: Boolean, available: Boolean = true) = object : Tool {
        override val name = toolName
        override val description = "$toolName description"
        override val parametersSchema: JsonObject = JsonObject(emptyMap())
        override val isReadOnly = readOnly
        override fun isAvailable(ctx: ToolContext) = available
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult(true, "")
    }

    private fun registry() = ToolRegistry(
        listOf(
            tool("read_file", readOnly = true),
            tool("write_file", readOnly = false),
            tool("git_status", readOnly = true),
            tool("git_push", readOnly = false),
            tool("web_search", readOnly = true),
            tool("read_logcat", readOnly = true),
            tool("pkg_install", readOnly = false),
            tool("codegraph_node", readOnly = true),
            tool("skill_manage", readOnly = false),
            tool("browser_navigate", readOnly = false),
            tool("custom_mcp_tool", readOnly = true),
        ),
    )

    @Test
    fun `initial groups send core and net and withhold the rest`() {
        val names = registry().schemas(groups = ToolGroups.initialGroups()).map { it.name }
        // CORE (includes unknown names and read-only git inspection) + NET.
        assertTrue(names.containsAll(listOf("read_file", "write_file", "git_status", "custom_mcp_tool", "web_search")))
        // Situational groups are withheld.
        for (withheld in listOf("git_push", "browser_navigate", "read_logcat", "pkg_install", "codegraph_node", "skill_manage")) {
            assertFalse("expected $withheld to be withheld", names.contains(withheld))
        }
        assertEquals(names, names.sorted())
    }

    @Test
    fun `activating a group only adds schemas and keeps the order of the ones already sent`() {
        val base = registry().schemas(groups = setOf(ToolGroup.CORE)).map { it.name }
        val grown = registry().schemas(groups = setOf(ToolGroup.CORE, ToolGroup.NET)).map { it.name }

        // Every previously sent tool is still present, in the same relative order.
        assertEquals(base, grown.filter { it in base.toSet() })
        assertTrue(grown.size > base.size)
        assertTrue(grown.contains("web_search"))
        assertEquals(grown, grown.sorted())
    }

    @Test
    fun `plan mode filtering intersects with the active groups`() {
        val names = registry().schemas(readOnlyOnly = true, groups = setOf(ToolGroup.CORE, ToolGroup.GIT)).map { it.name }
        assertEquals(listOf("custom_mcp_tool", "git_status", "read_file"), names)
    }

    @Test
    fun `description overrides still apply inside the exposed groups`() {
        val schema = registry()
            .schemas(groups = setOf(ToolGroup.NET), descriptionOverrides = mapOf("web_search" to "Custom."))
            .single()
        assertEquals("web_search", schema.name)
        assertEquals("Custom.", schema.description)
    }

    @Test
    fun `availability filtering still applies under staging`() {
        val registry = ToolRegistry(listOf(tool("read_file", readOnly = true, available = false), tool("git_status", readOnly = true)))
        val names = registry.schemas(context = ToolContext(workspace), groups = ToolGroups.initialGroups()).map { it.name }
        assertEquals(listOf("git_status"), names)
    }

    @Test
    fun `run-scoped extras are never withheld`() {
        val registry = registry().withExtra(listOf(tool("my_mcp_tool", readOnly = true)))
        val names = registry.schemas(groups = ToolGroups.initialGroups()).map { it.name }
        assertTrue(names.contains("my_mcp_tool"))
    }

    @Test
    fun `a withheld tool is still resolvable so a direct call is executed, not rejected as unknown`() {
        val registry = registry()
        // Not in the initial schema set...
        assertFalse(registry.schemas(groups = ToolGroups.initialGroups()).any { it.name == "git_push" })
        // ...but still present, so the engine's deferred-call recovery executes it.
        assertTrue(registry.get("git_push") != null)
    }

    @Test
    fun `search finds withheld tools by name, description and group`() {
        val registry = registry()
        assertTrue(registry.search("git_push").any { it.name == "git_push" })
        assertTrue(registry.search("pkg_install").isNotEmpty())
        assertTrue(registry.search("browser").any { it.name == "browser_navigate" })
        // An empty query returns everything (the model asked for the full list).
        assertEquals(registry.all().map { it.name }, registry.search("").map { it.name })
    }
}
