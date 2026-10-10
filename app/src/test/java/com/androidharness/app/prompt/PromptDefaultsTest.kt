package com.androidharness.app.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptDefaultsTest {

    @Test
    fun `section ids are unique and stable-looking`() {
        val ids = PromptDefaults.ALL_SECTIONS.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        ids.forEach { assertTrue("bad id: $it", PromptValidation.isSectionId(it)) }
    }

    @Test
    fun `every section has a title and description for the UI`() {
        PromptDefaults.ALL_SECTIONS.forEach {
            assertTrue(it.id, it.title.isNotBlank())
            assertTrue(it.id, it.description.isNotBlank())
            assertTrue(it.id, it.defaultText.isNotEmpty())
        }
    }

    @Test
    fun `the protected rule set covers the security critical instructions`() {
        val protectedIds = PromptDefaults.ALL_SECTIONS.filter { it.protected }.map { it.id }.toSet()
        val expected = setOf(
            "core.containment",
            "core.parallel_reads",
            "core.mutation_order",
            "core.grounding",
            "core.deterministic_failure",
            "core.no_invent",
            "core.plan_mode",
            "env.ssh",
            "env.saf",
            "env.shell_rules",
            "env.tmp_access",
            "env.full_access",
            "skills.mandatory",
            "skills.catalog",
            "workspace.agents_md",
            "workspace.memory",
            "workspace.memory_topics",
            "workspace.todos",
            "workspace.repo_map",
            "subagent.action",
            "subagent.read_only",
            "subagent.common",
        )
        assertTrue("missing protected sections: ${expected - protectedIds}", protectedIds.containsAll(expected))
    }

    @Test
    fun `protected sections can never be disabled`() {
        PromptDefaults.ALL_SECTIONS.filter { it.protected }.forEach {
            assertTrue(it.id, !it.allowDisable)
        }
    }

    @Test
    fun `placeholder sections declare their required placeholders`() {
        assertNotNull(PromptDefaults.section("core.identity"))
        assertTrue(PromptDefaults.section("core.identity")!!.requiredPlaceholders.contains("workspace_path"))
        assertTrue(PromptDefaults.section("env.ssh")!!.requiredPlaceholders.contains("workspace_root"))
        assertTrue(PromptDefaults.section("skills.catalog")!!.requiredPlaceholders.contains("skills_index"))
    }
}
