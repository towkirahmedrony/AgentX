package com.androidharness.app.ui.github

import com.androidharness.app.R
import com.androidharness.app.ui.common.AppLinearProgressIndicator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.automation.AutomationSchedule
import com.androidharness.app.automation.AutomationTask
import com.androidharness.app.github.*
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun GitHubDialog(title: String, busy: Boolean, onDismiss: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(12.dp).fillMaxHeight(0.9f)) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                footer()
                if (busy) AppLinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.settings_action_close)) }
            }
        }
    }
}

@Composable
fun GitHubImportDialog(container: AppContainer, onDismiss: () -> Unit, onImported: () -> Unit = onDismiss,
    activateWorkspace: Boolean = true, onProjectImported: (String) -> Unit = {}) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<String>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val environment by container.linuxEnv.state.collectAsStateWithLifecycle()
    val gitToolsReady = environment is com.androidharness.app.data.env.EnvState.Ready && container.linuxEnv.gitToolsReady
    fun load() {
        busy = true
        scope.launch {
            try {
                val next = container.githubRepositories.listRepositories(page)
                repos = (repos + next).distinct(); page++; more = next.size == 30; message = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = container.github.safeMessage(e) }
            finally { busy = false }
        }
    }
    GitHubDialog(uiContext.getString(R.string.settings_github_import), busy, onDismiss) {
        Text(uiContext.getString(R.string.ui_clone_a_repository_into_its_own_private),
            style = MaterialTheme.typography.bodySmall)
        if (!gitToolsReady) {
            Text(uiContext.getString(R.string.ui_install_git_tools_once_to_import_repositories), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                busy = true; message = uiContext.getString(R.string.ui_installing_git_tools)
                scope.launch {
                    try {
                        container.linuxEnv.install(container.linuxEnv.corePackages).getOrThrow()
                        check(container.linuxEnv.gitToolsReady) { (container.linuxEnv.state.value as? com.androidharness.app.data.env.EnvState.Failed)?.message ?: uiContext.getString(R.string.ui_git_tools_could_not_be_installed_retry) }
                        message = uiContext.getString(R.string.ui_git_tools_ready)
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = container.github.safeMessage(e) }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_install_git_tools)) }
        }
        OutlinedTextField(input, onValueChange = { input = it }, label = { Text(uiContext.getString(R.string.ui_repository_url_or_owner_repository)) },
            enabled = !busy, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            busy = true; message = uiContext.getString(R.string.ui_importing_repository)
            scope.launch {
                try {
                    var projectId: String? = null
                    container.githubRepositories.importRepository(input, activateWorkspace) { projectId = it }
                    projectId?.let(onProjectImported)
                    onImported()
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = container.github.safeMessage(e) }
                finally { busy = false }
            }
        }, enabled = !busy && gitToolsReady && input.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_import_repository)) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        OutlinedButton(onClick = ::load, enabled = !busy && more, modifier = Modifier.fillMaxWidth()) {
            Text(if (repos.isEmpty()) uiContext.getString(R.string.ui_browse_my_repositories) else uiContext.getString(R.string.ui_load_more_repositories))
        }
        repos.forEach { repo ->
            OutlinedButton(onClick = { input = repo }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(repo) }
        }
    }
}

/** Entry point from chat; both OAuth and PAT connections share the same controls. */
@Composable
fun GitHubWorkspaceDialog(container: AppContainer, projectId: String, projectName: String,
    onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val connection by container.github.state.collectAsStateWithLifecycle()
    if (connection.login == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(uiContext.getString(R.string.ui_sign_in_to_github)) },
            text = { Text(uiContext.getString(R.string.ui_you_haven_t_connected_github_yet_sign)) },
            confirmButton = {
                TextButton(onClick = { onDismiss(); onOpenSettings() }) { Text(uiContext.getString(R.string.ui_sign_in)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(uiContext.getString(R.string.settings_action_close)) } },
        )
    } else {
        GitHubPublishDialog(container, projectId, projectName, onDismiss)
    }
}

@Composable
fun GitHubPublishDialog(container: AppContainer, projectId: String, projectName: String, onDismiss: () -> Unit) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val environment by container.linuxEnv.state.collectAsStateWithLifecycle()
    var fs by remember(projectId) { mutableStateOf<WorkspaceFs?>(null) }
    var state by remember(projectId) { mutableStateOf<GitHubRepoState?>(null) }
    var paths by remember(projectId) { mutableStateOf<Set<String>>(emptySet()) }
    var directory by remember(projectId) { mutableStateOf("") }
    var commitMessage by remember { mutableStateOf("") }
    var repositoryInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var preset by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val previousState = state
        val previousPaths = paths
        val previousDirectory = directory
        // Failed refreshes must not leave controls targeting an old repository.
        fs = null
        state = null
        paths = emptySet()
        directory = ""
        val project = container.workspace.projects.first().firstOrNull { it.id == projectId }
            ?: error(uiContext.getString(R.string.ui_this_workspace_was_removed))
        val captured = container.workspace.fsFor(project)
        fs = captured
        if (!container.linuxEnv.gitToolsReady) return
        val snapshot = try { container.githubRepositories.inspect(captured) }
        catch (e: GitHubOperationFailure) {
            // A plain folder or an unconnected local repository needs setup controls, not an error.
            if (e.stage == "Repository" && (e.message.orEmpty().contains("not a git repository", ignoreCase = true) ||
                    e.message.orEmpty().contains("This workspace has no GitHub origin"))) {
                return
            }
            throw e
        }
        state = snapshot
        // Refresh keeps surviving selections only within the reviewed repository/branch.
        if (previousState?.remoteUrl == snapshot.remoteUrl && previousState.branch == snapshot.branch) {
            val tree = GitChangeTree(snapshot.changes)
            paths = previousPaths.intersect(tree.paths)
            directory = tree.survivingDirectory(previousDirectory)
        }
    }
    fun act(action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = container.github.safeMessage(e)
                // A commit can succeed even when its push fails. Refresh HEAD for an immediate push retry.
                try { refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                message = failure
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(projectId) {
        busy = true
        try { refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = container.github.safeMessage(e) }
        finally { busy = false }
    }
    if (importing) {
        GitHubImportDialog(container, onDismiss = { importing = false }, onImported = onDismiss)
        return
    }
    GitHubDialog(uiContext.getString(R.string.settings_github_commit_push), busy, onDismiss, footer = {
        state?.takeIf { it.changes.isNotEmpty() }?.let { snapshot ->
            GitHubCommitFooter(paths.size, snapshot.changes.size, commitMessage, { commitMessage = it }, !busy) {
                act {
                    message = container.githubRepositories.publish(requireNotNull(fs), snapshot, paths, commitMessage, true)
                    refresh()
                }
            }
        }
    }) {
        Text(projectName, style = MaterialTheme.typography.titleMedium)
        if (environment !is com.androidharness.app.data.env.EnvState.Ready || !container.linuxEnv.gitToolsReady) {
            OutlinedButton(onClick = { act {
                container.linuxEnv.install(container.linuxEnv.corePackages).getOrThrow()
                check(container.linuxEnv.gitToolsReady) { (container.linuxEnv.state.value as? com.androidharness.app.data.env.EnvState.Failed)?.message ?: uiContext.getString(R.string.ui_git_tools_could_not_be_installed_retry) }
                refresh(); message = uiContext.getString(R.string.ui_git_tools_ready)
            } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_install_or_repair_git_tools)) }
        }
        state?.let { snapshot ->
            Text(snapshot.remoteUrl.removePrefix("https://github.com/").removeSuffix(".git"))
            Text(uiContext.getString(R.string.ui_branch, (snapshot.branch).toString()), style = MaterialTheme.typography.bodySmall)
            Text(uiContext.getString(R.string.ui_changed_files_app_history_excluded, (snapshot.changes.size).toString()), style = MaterialTheme.typography.bodySmall)
            if (snapshot.changes.isNotEmpty()) {
                GitHubChangePicker(snapshot.changes, paths, directory, { directory = it }, { paths = it }, !busy)
            } else Text(uiContext.getString(R.string.ui_no_uncommitted_changes_you_can_push_existing), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { act {
                message = container.githubRepositories.publish(requireNotNull(fs), snapshot, emptySet(), "", false)
                refresh()
            } }, enabled = !busy && snapshot.head.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_push_existing_commits)) }
            OutlinedButton(onClick = { preset = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_save_reusable_push_preset)) }
        }
        if (state == null && !busy) {
            OutlinedButton(onClick = { importing = true }, modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.settings_github_import)) }
            OutlinedTextField(repositoryInput, onValueChange = { repositoryInput = it }, label = { Text(uiContext.getString(R.string.ui_existing_github_repository)) },
                modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { act {
                container.githubRepositories.connect(requireNotNull(fs), repositoryInput)
                refresh(); message = uiContext.getString(R.string.ui_repository_connected)
            } }, enabled = fs?.shellRoot != null && container.linuxEnv.gitToolsReady && repositoryInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_connect_repository)) }
        }
        OutlinedButton(onClick = { act { refresh(); message = null } }, enabled = !busy,
            modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_refresh_repository)) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (preset && state != null) GitHubPushPresetDialog(container, projectId, projectName,
        initial = GitHubPushPreset(requireNotNull(state).remoteUrl, requireNotNull(state).branch,
            commitMessage.ifBlank { "Update from AgentX" }, paths.toList()), onDismiss = { preset = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GitHubPushPresetDialog(container: AppContainer, projectId: String, projectName: String,
    onDismiss: () -> Unit, initial: GitHubPushPreset? = null, task: AutomationTask? = null) {
    val uiContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(initial ?: task?.githubPush) }
    var title by remember { mutableStateOf(task?.title ?: uiContext.getString(R.string.ui_push_to_github, (projectName).toString())) }
    var commitMessage by remember { mutableStateOf(initial?.commitMessage ?: task?.githubPush?.commitMessage ?: "Update from AgentX") }
    var schedule by remember { mutableStateOf(task?.schedule ?: AutomationSchedule.MANUAL) }
    var hour by remember { mutableStateOf((task?.hour ?: 8).toString()) }
    var minute by remember { mutableStateOf((task?.minute ?: 0).toString()) }
    var allChanges by remember { mutableStateOf(task?.githubPush?.paths?.isEmpty() == true) }
    var busy by remember { mutableStateOf(snapshot == null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(projectId) {
        if (snapshot == null) {
            try {
                val project = container.workspace.projects.first().first { it.id == projectId }
                val state = container.githubRepositories.inspect(container.workspace.fsFor(project))
                snapshot = GitHubPushPreset(state.remoteUrl, state.branch, commitMessage, state.changes.map { it.path })
            } catch (e: Exception) { message = container.github.safeMessage(e) } finally { busy = false }
        }
    }
    GitHubDialog(if (task == null) uiContext.getString(R.string.ui_github_push_preset) else uiContext.getString(R.string.ui_edit_github_push_preset), busy, onDismiss) {
        Text("$projectName · ${snapshot?.branch.orEmpty()}", style = MaterialTheme.typography.titleMedium)
        snapshot?.let { Text(it.remoteUrl, style = MaterialTheme.typography.bodySmall) }
        Text(uiContext.getString(R.string.ui_reuse_this_repository_and_branch_in_automation),
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(title, onValueChange = { title = it }, label = { Text(uiContext.getString(R.string.ui_preset_name)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(commitMessage, onValueChange = { commitMessage = it }, label = { Text(uiContext.getString(R.string.ui_commit_message)) }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().clickable { allChanges = !allChanges }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(allChanges, onCheckedChange = null)
            Text(uiContext.getString(R.string.ui_include_all_future_changes_in_this_workspace), modifier = Modifier.weight(1f))
        }
        if (!allChanges) Text(uiContext.getString(R.string.ui_selected_paths, (snapshot?.paths?.joinToString(", ")?.ifBlank { "No paths selected. Enable future changes or select files in Commit & push." }).toString()),
            style = MaterialTheme.typography.bodySmall)
        Text(uiContext.getString(R.string.ui_schedule), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AutomationSchedule.MANUAL, AutomationSchedule.HOURLY, AutomationSchedule.DAILY).forEach { choice ->
                FilterChip(selected = schedule == choice, onClick = { schedule = choice }, label = { Text(choice.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
        if (schedule == AutomationSchedule.DAILY) {
            OutlinedTextField(hour, onValueChange = { hour = it.filter(Char::isDigit).take(2) }, label = { Text(uiContext.getString(R.string.ui_hour_0_23)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(minute, onValueChange = { minute = it.filter(Char::isDigit).take(2) }, label = { Text(uiContext.getString(R.string.ui_minute_0_59)) }, modifier = Modifier.fillMaxWidth())
        }
        if (schedule != AutomationSchedule.MANUAL) Text(uiContext.getString(R.string.ui_android_may_delay_scheduled_work_failed_or), style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            busy = true
            scope.launch {
                try {
                    val saved = requireNotNull(snapshot).copy(commitMessage = commitMessage.trim(), paths = if (allChanges) emptyList() else requireNotNull(snapshot).paths)
                    val base = task ?: AutomationTask(title = title, prompt = "Publish reviewed changes to GitHub", projectId = projectId, projectName = projectName)
                    container.automation.save(base.copy(title = title.trim(), githubPush = saved, schedule = schedule,
                        hour = hour.toIntOrNull() ?: -1, minute = minute.toIntOrNull() ?: -1, enabled = true))
                    onDismiss()
                } catch (e: Exception) { message = container.github.safeMessage(e) } finally { busy = false }
            }
        }, enabled = !busy && snapshot != null && title.isNotBlank() && commitMessage.isNotBlank() &&
            (allChanges || snapshot?.paths?.isNotEmpty() == true), modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_save_push_preset)) }
        if (task != null) TextButton(onClick = { container.automation.delete(task.id); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(uiContext.getString(R.string.ui_delete_preset)) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
