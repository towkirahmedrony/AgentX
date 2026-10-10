package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Deterministic, provider-free fixtures shared by the context-budget tests.
 * No Android API is touched, so these run as plain JVM unit tests.
 */
internal object ChatMessageFixtures {

    fun user(text: String = "u") = ChatMessage(role = Role.USER, text = text)

    fun assistant(text: String = "a") = ChatMessage(role = Role.ASSISTANT, text = text)

    fun assistantCalls(vararg ids: String, arguments: String = "{}") = ChatMessage(
        role = Role.ASSISTANT,
        text = "",
        toolCalls = ids.map { ToolCallData(id = it, name = "shell", argumentsJson = arguments) },
    )

    fun tool(id: String, text: String = "result", isError: Boolean = false) = ChatMessage(
        role = Role.TOOL,
        text = text,
        toolCallId = id,
        toolName = "shell",
        isError = isError,
    )

    /** A compaction summary exactly as the engine inserts one. */
    fun summary(text: String) = ContextHygiene.summaryMessage(text)

    fun schema(name: String, description: String) =
        ToolSchema(name = name, description = description, parametersJson = JsonObject(emptyMap<String, JsonElement>()))
}
