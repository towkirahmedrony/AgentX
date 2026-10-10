package com.androidharness.app.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ToolDescriptionOverrideTest {

    private fun tool(toolName: String, readOnly: Boolean) = object : Tool {
        override val name = toolName
        override val description = "$toolName default description"
        override val parametersSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("title", "$toolName-schema")
        }
        override val isReadOnly = readOnly
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult(true, "")
    }

    private fun registry() = ToolRegistry(listOf(tool("alpha", true), tool("beta", false), tool("gamma", true)))

    @Test
    fun `an override changes only the description`() {
        val before = registry().schemas()
        val after = registry().schemas(descriptionOverrides = mapOf("beta" to "Custom beta wording."))

        assertEquals(before.map { it.name }, after.map { it.name })
        assertEquals(before.map { it.parametersJson }, after.map { it.parametersJson })

        val betaBefore = before.first { it.name == "beta" }
        val betaAfter = after.first { it.name == "beta" }
        assertEquals("beta default description", betaBefore.description)
        assertEquals("Custom beta wording.", betaAfter.description)
        assertEquals(betaBefore.parametersJson, betaAfter.parametersJson)

        // Every other tool is untouched.
        assertEquals(
            before.filterNot { it.name == "beta" }.map { it.name to it.description },
            after.filterNot { it.name == "beta" }.map { it.name to it.description },
        )
    }

    @Test
    fun `unknown names and blank values are ignored`() {
        val base = registry().schemas()
        val after = registry().schemas(
            descriptionOverrides = mapOf("missing" to "nope", "alpha" to "   "),
        )
        assertEquals(base.map { it.description }, after.map { it.description })
    }

    @Test
    fun `tool order stays stable so prompt caching is not disturbed`() {
        val order = registry().schemas().map { it.name }
        val overridden = registry().schemas(descriptionOverrides = mapOf("gamma" to "Custom.")).map { it.name }
        assertEquals(order, overridden)
        assertEquals(listOf("alpha", "beta", "gamma"), order)
    }

    @Test
    fun `read-only filtering for PLAN mode still applies with overrides`() {
        val planTools = registry().schemas(
            readOnlyOnly = true,
            descriptionOverrides = mapOf("alpha" to "Custom alpha."),
        )
        assertEquals(listOf("alpha", "gamma"), planTools.map { it.name })
        assertEquals("Custom alpha.", planTools.first().description)
        assertNotEquals(planTools.map { it.name }, registry().schemas().map { it.name })
    }
}
