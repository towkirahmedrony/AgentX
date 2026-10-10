package com.androidharness.app.prompt

/** The filesystem shape of the active workspace, as far as the prompt cares. */
enum class WorkspaceKind {
    /** Remote host reached over SSH. */
    SSH,

    /** Local workspace with the full Linux toolchain ready. */
    SHELL_READY,

    /** Local workspace falling back to Android's toybox sh. */
    SHELL_TOYBOX,

    /** No real shell root (cloud/SAF picked folder). */
    NO_SHELL,
}

/** Shizuku availability, mirrored from [com.androidharness.app.data.env.ShizukuState]. */
enum class ShizukuFact { GRANTED, RUNNING_NO_PERMISSION, NOT_RUNNING, ABSENT }

/**
 * The runtime information required to decide which prompt sections apply and
 * to resolve their dynamic values.
 *
 * Everything here is already-gathered state: the composer never touches the
 * filesystem or settings, which is what makes the assembled prompt testable.
 */
data class PromptFacts(
    val workspaceKind: WorkspaceKind,
    val workspacePath: String,
    val workspaceRoot: String = "",
    val shizuku: ShizukuFact = ShizukuFact.ABSENT,
    val fullAccess: Boolean = false,
    val planMode: Boolean = false,
    /** Rendered `<available_skills>` body, or null when no skill is enabled. */
    val skillsIndex: String? = null,
    val agentsMd: String? = null,
    val memory: String? = null,
    val memoryTopics: List<String> = emptyList(),
    val todos: String? = null,
    val repositoryMap: String? = null,
    /** Catalog mode for the Skills section; false keeps the verbose default. */
    val compactSkillsCatalog: Boolean = false,
) {
    fun matches(trigger: PromptTrigger): Boolean = when (trigger) {
        PromptTrigger.ALWAYS -> true
        PromptTrigger.WORKSPACE_SSH -> workspaceKind == WorkspaceKind.SSH
        PromptTrigger.WORKSPACE_SHELL_READY -> workspaceKind == WorkspaceKind.SHELL_READY
        PromptTrigger.WORKSPACE_SHELL_TOYBOX -> workspaceKind == WorkspaceKind.SHELL_TOYBOX
        PromptTrigger.WORKSPACE_NO_SHELL -> workspaceKind == WorkspaceKind.NO_SHELL
        PromptTrigger.NOT_SSH -> workspaceKind != WorkspaceKind.SSH
        PromptTrigger.SHIZUKU_GRANTED ->
            workspaceKind != WorkspaceKind.SSH && shizuku == ShizukuFact.GRANTED
        PromptTrigger.SHIZUKU_NO_PERMISSION ->
            workspaceKind != WorkspaceKind.SSH && shizuku == ShizukuFact.RUNNING_NO_PERMISSION
        PromptTrigger.SHIZUKU_NOT_RUNNING ->
            workspaceKind != WorkspaceKind.SSH && shizuku == ShizukuFact.NOT_RUNNING
        PromptTrigger.FULL_ACCESS -> fullAccess && workspaceKind != WorkspaceKind.SSH
        PromptTrigger.PLAN_MODE -> planMode
        PromptTrigger.SKILLS_PRESENT -> !skillsIndex.isNullOrBlank()
        PromptTrigger.AGENTS_MD_PRESENT -> agentsMd != null
        PromptTrigger.MEMORY_PRESENT -> memory != null
        PromptTrigger.MEMORY_TOPICS_PRESENT -> memoryTopics.isNotEmpty()
        PromptTrigger.TODOS_PRESENT -> !todos.isNullOrBlank()
        PromptTrigger.REPO_MAP_PRESENT -> !repositoryMap.isNullOrBlank()
        // Sub-agent sections are resolved by PromptComposer.subagentSystemPrompt,
        // never by the main assemble pass.
        PromptTrigger.SUBAGENT_ACTION, PromptTrigger.SUBAGENT_READONLY -> false
    }
}
