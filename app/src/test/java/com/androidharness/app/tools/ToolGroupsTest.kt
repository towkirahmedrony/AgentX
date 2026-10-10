package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The staged-exposure group table is the single source of truth for which tool
 * schemas are sent up front. These tests pin the invariants the agent loop and
 * the token budget rely on.
 */
class ToolGroupsTest {

    @Test
    fun `group ids are unique and stable`() {
        val ids = ToolGroup.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(ids.size, ToolGroups.all.size)
    }

    @Test
    fun `known tools map to their group`() {
        assertEquals(ToolGroup.GIT, ToolGroups.groupFor("git_push"))
        assertEquals(ToolGroup.GIT, ToolGroups.groupFor("git_commit"))
        assertEquals(ToolGroup.NET, ToolGroups.groupFor("web_search"))
        assertEquals(ToolGroup.NET, ToolGroups.groupFor("http_request"))
        assertEquals(ToolGroup.DIAGNOSTICS, ToolGroups.groupFor("read_logcat"))
        assertEquals(ToolGroup.DIAGNOSTICS, ToolGroups.groupFor("env_status"))
        assertEquals(ToolGroup.PACKAGES, ToolGroups.groupFor("pkg_install"))
        assertEquals(ToolGroup.CODEGRAPH, ToolGroups.groupFor("codegraph_node"))
        assertEquals(ToolGroup.SKILLS_ADMIN, ToolGroups.groupFor("skill_manage"))
        assertEquals(ToolGroup.BROWSER, ToolGroups.groupFor("browser_navigate"))
    }

    @Test
    fun `read-only git inspection and unknown names stay in the always-sent core group`() {
        // Everyday inspection must never be withheld.
        assertEquals(ToolGroup.CORE, ToolGroups.groupFor("git_status"))
        assertEquals(ToolGroup.CORE, ToolGroups.groupFor("git_diff"))
        assertEquals(ToolGroup.CORE, ToolGroups.groupFor("git_log"))
        // MCP tools, run-scoped extras and tool_search have no entry: they must
        // be treated as CORE so they can never be withheld.
        assertEquals(ToolGroup.CORE, ToolGroups.groupFor("custom_mcp_tool"))
        assertEquals(ToolGroup.CORE, ToolGroups.groupFor("tool_search"))
        assertEquals(ToolGroup.CORE, ToolGroups.groupFor("read_file"))
    }

    @Test
    fun `initial groups cover the always-needed set and withhold the situational ones`() {
        val initial = ToolGroups.initialGroups()
        assertTrue(initial.contains(ToolGroup.CORE))
        assertTrue(initial.contains(ToolGroup.NET))
        assertFalse(initial.contains(ToolGroup.GIT))
        assertFalse(initial.contains(ToolGroup.BROWSER))
        assertFalse(initial.contains(ToolGroup.DIAGNOSTICS))
        assertFalse(initial.contains(ToolGroup.PACKAGES))
        assertFalse(initial.contains(ToolGroup.CODEGRAPH))
        assertFalse(initial.contains(ToolGroup.SKILLS_ADMIN))
    }

    @Test
    fun `group ids resolve back to their enum value`() {
        assertEquals(ToolGroup.BROWSER, ToolGroups.byId("browser"))
        assertEquals(ToolGroup.SKILLS_ADMIN, ToolGroups.byId(" SKILLS_ADMIN "))
        assertEquals(null, ToolGroups.byId("nope"))
    }
}
