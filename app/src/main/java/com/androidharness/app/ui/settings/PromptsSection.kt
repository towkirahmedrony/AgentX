package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.agent.AgentMode
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.AppSettings
import com.androidharness.app.prompt.PromptComposer
import com.androidharness.app.prompt.PromptDefaults
import com.androidharness.app.prompt.PromptOverrides
import com.androidharness.app.prompt.PromptScope
import com.androidharness.app.prompt.PromptSection
import com.androidharness.app.prompt.PromptValidation
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.ui.common.formatTokenCount
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Settings → Prompts. Edits the editable prompt sections and per-tool
 * descriptions, previews the assembled prompt for the current runtime
 * conditions, and always shows which rules are protected. Every change goes
 * through [com.androidharness.app.data.SettingsRepository]; protected sections
 * are shown read-only and never persisted.
 */
@Composable
internal fun PromptsSection(
    container: AppContainer,
    settings: AppSettings,
    scope: CoroutineScope,
) {
    val overrides = settings.promptOverrides
    var confirmRestoreAll by remember { mutableStateOf(false) }
    var modeName by rememberSaveable { mutableStateOf(AgentMode.ACT.name) }
    val mode = runCatching { AgentMode.valueOf(modeName) }.getOrDefault(AgentMode.ACT)

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("System prompts", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "Edit how the agent is instructed. Defaults stay in the app source; " +
                        "your changes are stored as sparse overrides and survive updates. " +
                        "Rules marked Protected cannot be changed or removed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PromptSectionGroup("Core", PromptScope.CORE, container, overrides, scope)
        PromptSectionGroup("Environment", PromptScope.ENVIRONMENT, container, overrides, scope)
        PromptToolsCard(container, overrides, scope)
        PromptSkillsCard(container, settings, overrides, scope)
        PromptSectionGroup("Sub-agents", PromptScope.SUBAGENT, container, overrides, scope)
        PromptSectionGroup("Workspace documents", PromptScope.WORKSPACE, container, overrides, scope)
        PromptPreviewCard(container, settings, overrides, mode) { modeName = it.name }

        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Restore defaults", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Clears every prompt and tool-description override. Providers, sessions, " +
                        "permissions and credentials are not affected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { confirmRestoreAll = true }) {
                    Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restore all prompt defaults")
                }
            }
        }
    }

    if (confirmRestoreAll) {
        AlertDialog(
            onDismissRequest = { confirmRestoreAll = false },
            title = { Text("Restore all prompt defaults?") },
            text = {
                Text(
                    "This removes every editable prompt and tool-description change and turns the " +
                        "compact skills catalog back off. Protected rules were never changed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestoreAll = false
                    scope.launch { container.settings.resetAllPrompts() }
                }) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRestoreAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PromptSectionGroup(
    title: String,
    scopeKey: PromptScope,
    container: AppContainer,
    overrides: PromptOverrides,
    scope: CoroutineScope,
) {
    val sections = remember(scopeKey) { PromptDefaults.ALL_SECTIONS.filter { it.scope == scopeKey } }
    if (sections.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        sections.forEach { section ->
            PromptSectionCard(section, overrides, container, scope)
        }
    }
}

@Composable
private fun PromptSectionCard(
    section: PromptSection,
    overrides: PromptOverrides,
    container: AppContainer,
    scope: CoroutineScope,
) {
    val overrideText = overrides.sectionOverride(section.id)
    val disabled = overrides.isDisabled(section.id)
    var expanded by remember { mutableStateOf(false) }
    var draft by remember(section.id, overrideText) { mutableStateOf(overrideText ?: section.defaultText) }
    val problem = remember(section.id, draft) {
        if (section.protected) null else PromptValidation.validateSectionText(section, draft)
    }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(section.title, style = MaterialTheme.typography.bodyLarge)
                        if (section.protected) {
                            Spacer(Modifier.width(8.dp))
                            ProtectedBadge()
                        }
                        if (overrideText != null && !section.protected) {
                            Spacer(Modifier.width(8.dp))
                            Badge("Edited")
                        }
                        if (disabled) {
                            Spacer(Modifier.width(8.dp))
                            Badge("Off")
                        }
                    }
                    Text(
                        section.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!section.protected && section.allowDisable) {
                    Switch(
                        checked = !disabled,
                        onCheckedChange = { enabled ->
                            scope.launch { container.settings.setPromptSectionEnabled(section.id, enabled) }
                        },
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = if (expanded) "Close editor" else "Edit ${section.title}",
                        tint = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (expanded) {
                if (section.protected) {
                    Text(
                        "Protected: this text is fixed and always assembled from the app source.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ReadOnlyText(section.defaultText)
                } else {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                        label = { Text("Section text") },
                        isError = problem != null,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = HarnessMono),
                    )
                    if (problem != null) {
                        Text(problem, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    } else if (section.requiredPlaceholders.isNotEmpty()) {
                        Text(
                            "Keep: " + section.requiredPlaceholders.joinToString(" ") { "{{$it}}" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            enabled = problem == null && draft != (overrideText ?: section.defaultText),
                            onClick = {
                                val value = if (draft == section.defaultText) null else draft
                                scope.launch { container.settings.setPromptSectionOverride(section.id, value) }
                            },
                        ) { Text("Save") }
                        TextButton(
                            enabled = overrideText != null || disabled,
                            onClick = {
                                draft = section.defaultText
                                scope.launch { container.settings.resetPromptSection(section.id) }
                            },
                        ) { Text("Reset") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptToolsCard(
    container: AppContainer,
    overrides: PromptOverrides,
    scope: CoroutineScope,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tools by remember { mutableStateOf<List<com.androidharness.app.llm.ToolSchema>>(emptyList()) }
    var editing by remember { mutableStateOf<com.androidharness.app.llm.ToolSchema?>(null) }

    LaunchedEffect(overrides.toolDescriptions) {
        val workspace = runCatching { container.workspace.currentOnce() }.getOrNull() ?: return@LaunchedEffect
        tools = runCatching {
            container.registry.schemas(
                context = ToolContext(workspace),
                descriptionOverrides = overrides.toolDescriptions,
            )
        }.getOrDefault(emptyList())
    }

    val filtered = remember(tools, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) tools else tools.filter {
            it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Tools", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Edit a tool's description without touching its parameters, availability or behavior. " +
                        "${tools.size} tools available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search tools") },
                )
                val shown = filtered.take(80)
                shown.forEach { tool ->
                    val custom = overrides.toolOverride(tool.name) != null
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                                if (custom) {
                                    Spacer(Modifier.width(8.dp))
                                    Badge("Edited")
                                }
                            }
                            Text(
                                tool.description.take(140),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { editing = tool }) { Text("Edit") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
                if (filtered.size > shown.size) {
                    Text(
                        "Showing ${shown.size} of ${filtered.size}. Refine the search to narrow the list.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (filtered.isEmpty()) {
                    Text("No tools match.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    editing?.let { tool ->
        ToolDescriptionDialog(tool, overrides, container, scope) { editing = null }
    }
}

@Composable
private fun ToolDescriptionDialog(
    tool: com.androidharness.app.llm.ToolSchema,
    overrides: PromptOverrides,
    container: AppContainer,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val overrideText = overrides.toolOverride(tool.name)
    val defaultText = remember(tool.name) { container.registry.get(tool.name)?.description ?: tool.description }
    var draft by remember(tool.name, overrideText) { mutableStateOf(overrideText ?: defaultText) }
    var showSchema by remember { mutableStateOf(false) }
    val problem = remember(draft) { PromptValidation.validateToolDescription(draft) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tool.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp),
                    label = { Text("Description") },
                    isError = problem != null,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = HarnessMono),
                )
                if (problem != null) {
                    Text(problem, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { showSchema = !showSchema }) {
                    Text(if (showSchema) "Hide parameters" else "View parameters (read-only)")
                }
                if (showSchema) {
                    ReadOnlyText(tool.parametersJson.toString())
                }
                Text(
                    "Editing the description never changes parameter names, types, required fields or execution.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null && draft != (overrideText ?: defaultText),
                onClick = {
                    val value = if (draft == defaultText) null else draft
                    scope.launch { container.settings.setToolDescriptionOverride(tool.name, value) }
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = overrideText != null,
                    onClick = {
                        scope.launch { container.settings.resetToolDescription(tool.name) }
                        onDismiss()
                    },
                ) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun PromptSkillsCard(
    container: AppContainer,
    settings: AppSettings,
    overrides: PromptOverrides,
    scope: CoroutineScope,
) {
    val sections = remember { PromptDefaults.ALL_SECTIONS.filter { it.scope == PromptScope.SKILLS } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Skills", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionsOnly(sections, overrides, container, scope)
        SettingsPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Compact skills catalog", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "List skill names without their trigger descriptions to shorten the prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.compactSkillsCatalog,
                    onCheckedChange = { scope.launch { container.settings.setCompactSkillsCatalog(it) } },
                )
            }
        }
    }
}

/** Renders a fixed list of section cards (used by the Skills group). */
@Composable
private fun SectionsOnly(
    sections: List<PromptSection>,
    overrides: PromptOverrides,
    container: AppContainer,
    scope: CoroutineScope,
) {
    sections.forEach { section -> PromptSectionCard(section, overrides, container, scope) }
}

@Composable
private fun PromptPreviewCard(
    container: AppContainer,
    settings: AppSettings,
    overrides: PromptOverrides,
    mode: AgentMode,
    onModeChange: (AgentMode) -> Unit,
) {
    var preview by remember { mutableStateOf<PromptComposer.ComposedPrompt?>(null) }
    var failed by remember { mutableStateOf(false) }
    var fullText by remember { mutableStateOf(false) }

    LaunchedEffect(overrides, mode, settings.permissionMode, settings.repoMapEnabled) {
        val workspace = runCatching { container.workspace.currentOnce() }.getOrNull()
        if (workspace == null) {
            preview = null
            failed = true
            return@LaunchedEffect
        }
        val result = runCatching {
            container.engine.previewPrompt(
                workspace,
                mode,
                settings.permissionMode == PermissionMode.FULL_ACCESS,
                settings.repoMapEnabled,
            )
        }
        preview = result.getOrNull()
        failed = result.isFailure
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Preview", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = listOf(AgentMode.ACT, AgentMode.PLAN)
                    modes.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = mode == item,
                            onClick = { onModeChange(item) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        ) { Text(if (item == AgentMode.ACT) "Act" else "Plan") }
                    }
                }
                val current = preview
                if (current == null) {
                    Text(
                        if (failed) "Open a workspace to preview the assembled prompt."
                        else "Assembling preview…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "${current.charCount} characters · ≈ ${formatTokenCount(current.estimatedTokens.toLong())} tokens (estimated: chars ÷ 4)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { fullText = !fullText }) {
                        Text(if (fullText) "Hide assembled prompt" else "Show assembled prompt")
                    }
                    if (fullText) ReadOnlyText(current.text)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    current.sections.forEach { composed ->
                        PreviewRow(composed)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(composed: PromptComposer.ComposedSection) {
    val dim = if (composed.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(composed.title, style = MaterialTheme.typography.bodySmall, color = dim)
                if (composed.protected) {
                    Spacer(Modifier.width(6.dp))
                    ProtectedBadge()
                }
                if (composed.overridden) {
                    Spacer(Modifier.width(6.dp))
                    Badge("Edited")
                }
                if (composed.disabled) {
                    Spacer(Modifier.width(6.dp))
                    Badge("Off")
                }
                if (!composed.active && !composed.disabled) {
                    Spacer(Modifier.width(6.dp))
                    Badge("Inactive")
                }
            }
            if (composed.problem != null) {
                Text(composed.problem, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            if (composed.active) "≈ ${formatTokenCount(composed.estimatedTokens.toLong())} tok" else "—",
            style = MaterialTheme.typography.labelSmall,
            color = dim,
        )
    }
}

@Composable
private fun ProtectedBadge() = PromptChip(
    text = "Protected",
    background = MaterialTheme.colorScheme.tertiaryContainer,
    content = MaterialTheme.colorScheme.onTertiaryContainer,
    leading = { Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer) },
)

@Composable
private fun Badge(
    text: String,
    background: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) = PromptChip(text = text, background = background, content = content)

@Composable
private fun PromptChip(
    text: String,
    background: Color,
    content: Color,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(color = background, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(3.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = content)
        }
    }
}

@Composable
private fun ReadOnlyText(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.heightIn(max = 320.dp).padding(10.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = HarnessMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
