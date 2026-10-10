package com.androidharness.app.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerTest {

    private fun localLinux(
        path: String = "/work",
        shizuku: ShizukuFact = ShizukuFact.ABSENT,
        fullAccess: Boolean = false,
        planMode: Boolean = false,
        repositoryMap: String? = null,
        skillsIndex: String? = null,
        agentsMd: String? = null,
        memory: String? = null,
        memoryTopics: List<String> = emptyList(),
        todos: String? = null,
    ) = PromptFacts(
        workspaceKind = WorkspaceKind.SHELL_READY,
        workspacePath = path,
        workspaceRoot = path,
        shizuku = shizuku,
        fullAccess = fullAccess,
        planMode = planMode,
        repositoryMap = repositoryMap,
        skillsIndex = skillsIndex,
        agentsMd = agentsMd,
        memory = memory,
        memoryTopics = memoryTopics,
        todos = todos,
    )

    /**
     * The refactor must not change a single byte of the default prompt. This
     * pins the exact assembled text for a common runtime shape; the expected
     * value was transcribed from the historical AgentEngine.systemPrompt.
     */
    @Test
    fun `default prompt is byte-for-byte identical to the historical output`() {
        val expected = "You are AgentX, an autonomous coding agent running inside an Android app.\n" +
            "The user's workspace is: /work" +
            "\n\nRules:\n- All tool paths are relative to the workspace root. Paths outside the workspace are blocked." +
            "\n- Use list_dir/search_files/grep/read_file to explore before making changes." +
            "\n- Browser screenshots save under .harness/screenshots/ as timestamped JPEGs (e.g. .harness/screenshots/20260903_120000.jpg). To look up past screenshots, list the directory with list_dir path=\".harness/screenshots\". Use read_image with the screenshot path or filename to inspect visual content." +
            "\n- A message may reference files as @path (for example @src/Main.kt): the user is pointing at those exact files, so read them before acting on the request." +
            "\n- Prefer edit_file/multi_edit for targeted changes to existing files; use write_file to create or fully rewrite files; use apply_patch for multi-file diffs." +
            "\n- Use todo_write to track multi-step work and keep statuses current." +
            "\n- Use ask_user whenever a decision is genuinely the user's to make instead of guessing." +
            "\n- Independent read-only tool calls may run concurrently. Mutating, interactive, and browser-state calls keep strict ordering. Never make one read-only call depend on the result of another call in the same batch; split dependent work into another tool round." +
            "\n- Mutating calls see the workspace after earlier ordered calls complete. A patch or diff pre-computed before an earlier edit can be stale by the time it runs, so build patches right before applying them." +
            "\n- Text files follow the POSIX convention: the last line ends with a newline terminator. When patching or editing, never add an extra empty line for the file's final newline." +
            "\n- When reporting file properties like newlines or byte counts, verify with a byte check (file_info or shell tail -c 3 | xxd) rather than inferring from line counts." +
            "\n- For Android logs, exceptions, or app crash investigations, use read_logcat instead of raw shell logcat commands. It supports level, tag, package_name, and buffer filtering." +
            "\n- For broad exploration whose raw output would flood this conversation (finding all usages, mapping a codebase, comparing many files), delegate to the task tool: it runs a read-only subagent and returns only the final answer. When several independent explorations are needed, issue ALL task calls in the SAME message: they run concurrently." +
            "- The shell tool runs a full Linux environment (bash, git, python, node and more) with the workspace as its working directory. Call commands by their plain names (python3, git, node, ls, \u2026): the harness launches them correctly on every execution tier. Use shell_background for long-running servers. If a required CLI package is missing (e.g. ripgrep, jq, clang, rust, tmux, tree, openjdk-17), search for it with pkg_search and install it with pkg_install (do NOT run 'apt' or 'pkg' directly in shell). Package installation will always prompt the user with a confirmation warning before downloading.\n" +
            "- Shell environment rules (IMPORTANT): always call commands by plain name (ls, grep, head, python3, git, node\u2026). NEVER work around the environment yourself: do not invoke /system/bin/linker64, /apex/.../linker64, or /system/bin/toybox directly, and do not craft alternate PATHs. The harness already makes every toolchain binary runnable in every tier. If a basic command fails with \"Permission denied\" or exit code 126/127, the environment is misconfigured on this device: run the env_status tool once, tell the user what it reports, and stop retrying command variants.\n" +
            "- /data/local/tmp is readable only by the shell user: never try to inspect it from the app tier, and never conclude Shizuku/toolchain state from files there; use env_status.\n" +
            "- After tool calls complete, either continue with more tool calls or give the user the actual outcome. Lead with the result, not process narration. Mention only meaningful progress.\n" +
            "- Ground every completion claim in tool results you actually received. Never say tests/builds/checks passed unless the corresponding tool call succeeded. If a check failed or was not run, say that plainly.\n" +
            "- Keep simple asks simple. For coding work, the final answer should usually cover: what changed, what you verified, and anything still unresolved. Omit empty sections and routine implementation chatter.\n" +
            "- If a deterministic tool call fails, inspect the current state and change the inputs before retrying. Do not repeat the same failing call unchanged. Edit failures should trigger a fresh read of the affected file.\n" +
            "- Never invent file contents you have not read.\n" +
            "- Browser automation: when previewing or interacting with web projects via browser tools, start a local dev/http server using shell_background (e.g. python3 -m http.server 8000, npm run dev, or similar) and navigate to http://localhost:<port>.\n" +
            "- Mobile UI formatting: responses are displayed on a phone touchscreen. Format text cleanly and compactly. Use standard markdown tables (| Col 1 | Col 2 |) or concise bullet lists rather than wide ASCII terminal boxes. When creating, editing, or serving HTML/web projects, host them on localhost via shell_background and include an explicit preview directive `::web-preview{target=\"http://localhost:<port>\"}` to offer the user a one-tap in-app web preview button.\n"

        assertEquals(expected, PromptComposer.compose(localLinux()).text)
    }

    @Test
    fun `browser-only guidance follows browser tool availability`() {
        // Default facts keep the full guidance (the historical default prompt).
        val withBrowser = PromptComposer.compose(localLinux()).text
        assertTrue(withBrowser.contains("Browser screenshots save under"))
        assertTrue(withBrowser.contains("Browser automation:"))

        // With no browser tools in the run those two sections are omitted, but
        // every other instruction stays.
        val noBrowser = PromptComposer.compose(localLinux().copy(browserTools = false)).text
        assertFalse(noBrowser.contains("Browser screenshots save under"))
        assertFalse(noBrowser.contains("Browser automation:"))
        assertTrue(noBrowser.contains("Use list_dir/search_files/grep/read_file"))
        assertTrue(noBrowser.contains("Paths outside the workspace are blocked."))
    }

    @Test
    fun `workspace shapes swap the environment section`() {
        val ssh = PromptComposer.compose(
            localLinux().copy(workspaceKind = WorkspaceKind.SSH, workspaceRoot = "ubuntu@host:/srv"),
        ).text
        assertTrue(ssh.contains("- This is an SSH workspace at ubuntu@host:/srv."))
        assertFalse(ssh.contains("Shell environment rules"))
        assertFalse(ssh.contains("runs a full Linux environment"))

        val toybox = PromptComposer.compose(localLinux().copy(workspaceKind = WorkspaceKind.SHELL_TOYBOX)).text
        assertTrue(toybox.contains("Android's toybox sh"))
        assertFalse(toybox.contains("runs a full Linux environment"))

        val saf = PromptComposer.compose(localLinux().copy(workspaceKind = WorkspaceKind.NO_SHELL)).text
        assertTrue(saf.contains("no real filesystem path (cloud/SAF)"))
        assertFalse(saf.contains("runs a full Linux environment"))
    }

    @Test
    fun `plan mode and full access add their protected sections`() {
        val plan = PromptComposer.compose(localLinux(planMode = true)).text
        assertTrue(plan.contains("PLAN MODE IS ACTIVE"))
        assertFalse(plan.contains("FULL ACCESS MODE is active"))

        val full = PromptComposer.compose(localLinux(fullAccess = true)).text
        assertTrue(full.contains("FULL ACCESS MODE is active"))
        assertFalse(full.contains("PLAN MODE IS ACTIVE"))

        // Full access never claims to lift an SSH host's sandbox.
        val sshFull = PromptComposer.compose(
            localLinux(fullAccess = true).copy(workspaceKind = WorkspaceKind.SSH),
        ).text
        assertFalse(sshFull.contains("FULL ACCESS MODE is active"))
    }

    @Test
    fun `shizuku guidance follows the runtime state`() {
        assertTrue(PromptComposer.compose(localLinux(shizuku = ShizukuFact.GRANTED)).text.contains("Shizuku is connected"))
        assertTrue(
            PromptComposer.compose(localLinux(shizuku = ShizukuFact.RUNNING_NO_PERMISSION)).text
                .contains("hasn't been granted access yet"),
        )
        assertTrue(
            PromptComposer.compose(localLinux(shizuku = ShizukuFact.NOT_RUNNING)).text
                .contains("Shizuku is installed but not running"),
        )
        val absent = PromptComposer.compose(localLinux()).sections
        assertTrue(absent.none { it.id.startsWith("env.shizuku") && it.active })
    }

    @Test
    fun `optional workspace sections appear only when present`() {
        val rich = PromptComposer.compose(
            localLinux(
                repositoryMap = "app/Main.kt",
                skillsIndex = "  repo:\n    - git: Repos.\n",
                agentsMd = "Build with gradle.",
                memory = "Prefers Kotlin.",
                memoryTopics = listOf("notes", "todo"),
                todos = "Current task list (keep this updated via todo_write):\n- [pending] do it\n",
            ),
        ).text
        assertTrue(rich.contains("# Repository Map (codebase index)\napp/Main.kt"))
        assertTrue(rich.contains("<available_skills>"))
        assertTrue(rich.contains("    - git: Repos."))
        assertTrue(rich.contains("# AGENTS.md (project instructions)\nBuild with gradle."))
        assertTrue(rich.contains("# Agent memory (from previous sessions)\nPrefers Kotlin."))
        assertTrue(rich.contains("topic files on disk"))
        assertTrue(rich.contains("Current task list"))

        val plain = PromptComposer.compose(localLinux()).text
        assertFalse(plain.contains("# Repository Map"))
        assertFalse(plain.contains("<available_skills>"))
        assertFalse(plain.contains("# AGENTS.md"))
        assertFalse(plain.contains("# Agent memory"))
    }

    @Test
    fun `compact skills catalog drops descriptions`() {
        val index = "  repo:\n    - git: Repos.\n    - tags: Tags.\n"
        val verbose = PromptComposer.skillsBlock(index)
        assertTrue(verbose.contains("- git: Repos."))
        val compact = PromptComposer.skillsBlock(index, compact = true)
        assertTrue(compact.contains("- git\n"))
        assertFalse(compact.contains("Repos."))
    }

    @Test
    fun `protected sections ignore overrides and cannot be disabled`() {
        val section = PromptDefaults.section("core.containment")!!
        assertTrue(section.protected)
        val overrides = PromptOverrides(
            sections = mapOf(section.id to "totally different rules"),
            disabledSections = setOf(section.id),
        )
        val text = PromptComposer.compose(localLinux(), overrides).text
        assertTrue(text.contains("Paths outside the workspace are blocked."))
        assertFalse(text.contains("totally different rules"))
    }

    @Test
    fun `editable section override replaces the default and reset restores it`() {
        val override = PromptOverrides(sections = mapOf("core.keep_simple" to "Always answer in one line."))
        val edited = PromptComposer.compose(localLinux(), override).text
        assertTrue(edited.contains("Always answer in one line."))
        assertFalse(edited.contains("Omit empty sections and routine implementation chatter."))

        val reset = PromptComposer.compose(localLinux(), PromptOverrides()).text
        assertTrue(reset.contains("Omit empty sections and routine implementation chatter."))
    }

    @Test
    fun `editable section can be turned off`() {
        val off = PromptOverrides(disabledSections = setOf("core.browser_automation"))
        val text = PromptComposer.compose(localLinux(), off).text
        assertFalse(text.contains("Browser automation:"))
    }

    @Test
    fun `an override missing a required placeholder falls back to the default`() {
        val identity = PromptDefaults.section("core.identity")!!
        assertTrue(identity.requiredPlaceholders.contains("workspace_path"))
        val overrides = PromptOverrides(sections = mapOf(identity.id to "You are a helpful agent."))
        val text = PromptComposer.compose(localLinux(path = "/srv/project"), overrides).text
        assertTrue(text.contains("The user's workspace is: /srv/project"))
        assertFalse(text.contains("You are a helpful agent."))
    }

    @Test
    fun `unknown section ids are ignored`() {
        val overrides = PromptOverrides(
            sections = mapOf("core.does_not_exist" to "ignore me"),
            disabledSections = setOf("also.unknown"),
        )
        val composed = PromptComposer.compose(localLinux(), overrides)
        assertFalse(composed.text.contains("ignore me"))
        assertTrue(composed.text.contains("You are AgentX"))
    }

    @Test
    fun `preview metadata distinguishes active inactive and overridden sections`() {
        val override = PromptOverrides(sections = mapOf("core.keep_simple" to "Short answers only."))
        val composed = PromptComposer.compose(localLinux(planMode = false), override)
        val keepSimple = composed.sections.first { it.id == "core.keep_simple" }
        assertTrue(keepSimple.active)
        assertTrue(keepSimple.overridden)
        assertTrue(keepSimple.charCount > 0)
        assertEquals(keepSimple.text.length / 4, keepSimple.estimatedTokens)

        val planMode = composed.sections.first { it.id == "core.plan_mode" }
        assertFalse(planMode.active)
        assertEquals("", planMode.text)
        assertTrue(planMode.protected)
    }

    @Test
    fun `subagent prompts keep read-only and action instructions scoped`() {
        val readOnly = PromptComposer.subagentSystemPrompt(actionTools = false)
        assertTrue(readOnly.contains("read-only research subagent"))
        assertTrue(readOnly.contains("You must not modify anything."))
        assertFalse(readOnly.contains("You may edit files"))

        val action = PromptComposer.subagentSystemPrompt(actionTools = true)
        assertTrue(action.contains("You may edit files and run tools"))
        assertFalse(action.contains("You must not modify anything."))
        assertTrue(action.contains("You cannot ask questions or spawn subagents"))
    }
}
