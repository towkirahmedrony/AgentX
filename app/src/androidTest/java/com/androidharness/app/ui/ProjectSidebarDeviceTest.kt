package com.androidharness.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.ThemeMode
import com.androidharness.app.data.WorkspaceLayoutMode
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProjectSidebarDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun projectTreeSearchActionsAndToolsMatchTheApprovedLayout() {
        val now = System.currentTimeMillis()
        val projects = listOf(ProjectEntity("a", "AgentX", "APP", null, now),
            ProjectEntity("b", "Frame", "APP", null, now), ProjectEntity("c", "SanuFlix", "APP", null, now))
        fun chat(id: String, title: String, project: String, minutes: Long, plan: String? = null) =
            SessionEntity(id, title, now, now - minutes * 60_000, projectId = project, pendingPlan = plan)
        val chats = listOf(chat("picker", "A better model picker", "a", 12), chat("release", "Prepare the next release", "a", 60),
            chat("sidebar", "Give the sidebar more room", "a", 0), chat("stream", "Investigate streaming retries", "a", 24),
            chat("storage", "Review storage cleanup", "a", 48, "Review this cleanup plan"), chat("menu", "Fix the mobile menu", "b", 80))
        val chosen = AtomicReference<String?>()
        val projectChat = AtomicReference<String?>()
        val action = AtomicReference<Pair<String, SidebarChatAction>?>()
        val destination = AtomicReference<String?>()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val dark = InstrumentationRegistry.getArguments().getString("dark") == "true"
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                activity.setContent {
                HarnessTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) {
                        var projectsView by remember { mutableStateOf(false) }
                        var projectPins by remember { mutableStateOf(emptySet<String>()) }
                        var projectArchives by remember { mutableStateOf(emptySet<String>()) }
                        var query by remember { mutableStateOf("") }
                        var pinned by remember { mutableStateOf(setOf("picker", "release")) }
                        var archived by remember { mutableStateOf(emptySet<String>()) }
                        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            Box(Modifier.widthIn(max = 360.dp).fillMaxHeight()) {
                                ProjectNavigationSidebar(projects, chats, "sidebar", "a", pinned, archived, query, { query = it },
                                    emptyList(), false, {}, activity = { sidebarActivity(it.id == "stream", it.pendingPlan) },
                                    onNewChat = { destination.set("New chat") }, onChat = { chosen.set(it) }, onMessage = {},
                                    onProjectChat = { projectChat.set(it.id) }, onProjects = { destination.set("Projects") },
                                    onChatActions = { chosen.set("actions:${it.id}") },
                                    onAction = { session, value ->
                                        action.set(session.id to value)
                                        if (value == SidebarChatAction.PIN) pinned = if (session.id in pinned) pinned - session.id else pinned + session.id
                                        if (value == SidebarChatAction.ARCHIVE) archived = archived + session.id
                                    }, onBuild = { destination.set("Build") }, onTerminal = { destination.set("Terminal") },
                                    onAutomation = { destination.set("Scheduled tasks") }, onProviders = { destination.set("Providers") },
                                    onSettings = { destination.set("Settings") }, workspaceLabel = "Workspaces for this chat", workspaceSummary = "1 workspace · Primary: AgentX",
                                    onWorkspaces = { destination.set("Workspaces") }, onCollapse = { destination.set("Collapse") }, currentRoute = null,
                                    projectsView = projectsView, onProjectsViewChange = { projectsView = it },
                                    projectPins = projectPins, projectArchives = projectArchives,
                                    onProjectAction = { project, value ->
                                        if (value == SidebarProjectAction.PIN) projectPins = if (project.id in projectPins) projectPins - project.id else projectPins + project.id
                                        if (value == SidebarProjectAction.ARCHIVE) projectArchives = if (project.id in projectArchives) projectArchives - project.id else projectArchives + project.id
                                    })
                            }
                        }
                    }
                }
            } }
            await { find("AgentX icon") != null }
            assertWithin("AgentX icon")
            screenshot("chats-view")
            assertNull(find("Collapse AgentX"))
            scrollTo("Working"); assertNotNull(find("Working"))
            scrollTo("Needs you"); assertNotNull(find("Needs you"))
            tap("Projects")
            scrollTo("Expand AgentX")
            screenshot("projects-view")
            tap("Expand AgentX")
            screenshot("projects-expanded")
            scrollTo("Expand Frame"); tap("Expand Frame")
            scrollTo("Actions for Fix the mobile menu"); tap("Actions for Fix the mobile menu"); tap("Chat details")
            assertEquals("menu" to SidebarChatAction.DETAILS, action.get())
            assertNull(chosen.get())
            tap("Actions for Fix the mobile menu"); tap("Pin")
            await { action.get() == ("menu" to SidebarChatAction.PIN) }
            scrollTo("Actions for project SanuFlix"); tap("Actions for project SanuFlix"); tap("New chat in SanuFlix")
            assertEquals("c", projectChat.get())
            assertNull(chosen.get())
            scrollTo("Actions for project Frame"); tap("Actions for project Frame"); tap("Pin project")
            tap("Actions for project Frame"); tap("Archive project")
            await { find("Actions for project Frame") == null }
            tap("Show archived projects")
            scrollTo("Actions for project Frame")
            screenshot("archived-projects")
            tap("Actions for project Frame"); tap("Restore project")
            await { find("Actions for project Frame") == null }
            tap("Back to active projects")
            tap("Chats")
            search("Fix the mobile menu")
            scrollTo("Open Fix the mobile menu")
            assertNull(find("Open Give the sidebar more room"))
            tap("Open Fix the mobile menu")
            assertEquals("menu", chosen.get())
            search("")
            scrollTo("Tools"); tap("Tools"); scrollTo("Terminal"); tap("Terminal")
            assertEquals("Terminal", destination.get())
            scrollTo("Scheduled tasks"); tap("Scheduled tasks")
            assertEquals("Scheduled tasks", destination.get())
            scrollTo("Workspaces for this chat"); tap("Workspaces for this chat")
            assertEquals("Workspaces", destination.get())
            screenshot("projects-first-tools")
            scrollTo("Tools"); tap("Tools")
            scrollTo("Providers"); assertWithin("Providers"); tap("Providers")
            assertEquals("Providers", destination.get())
        }
    }

    @Test fun realDrawerCreatesInTheChosenProjectAndSearchReturnsToTheOriginalChat() = runBlocking {
        val saved = c.settings.settings.first()
        val previous = c.workspace.currentProjectOnce()
        val root = File(c.appContext.cacheDir, "sidebar-${System.nanoTime()}").apply { mkdirs() }
        val a = c.workspace.addShellProject(File(root, "Sidebar Harness").apply { mkdirs() }.path, activate = false)
        val b = c.workspace.addShellProject(File(root, "Sidebar Frame").apply { mkdirs() }.path, activate = false)
        val original = c.sessions.createSession("Original sidebar chat", a.id)
        c.sessions.addMessage(original, ChatMessage(Role.USER, "Unique sidebar search message anchor"))
        c.workspace.setActiveProject(b.id)
        c.settings.setSidebarProjects(false)
        c.settings.setWorkspaceLayout(WorkspaceLayoutMode.PHONE)
        c.settings.setOnboardingDone(true); c.settings.setResumeLastChat(true); c.settings.setLastActiveSessionId(original)
        val activity = instrumentation.startActivitySync(Intent(c.appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            await { find("Open navigation") != null }
            assertNull("A closed drawer must not expose its search field", find("Search chats and messages"))
            tap("Open navigation")
            await { find("AgentX icon") != null }
            assertSearchInactive(activity)
            tap("Search chats and messages")
            await { searchHasFocus() && keyboardVisible(activity) }
            search("Unique sidebar search message anchor")
            tap("Collapse sidebar")
            await { find("Open navigation") != null }
            tap("Open navigation")
            await { find("AgentX icon") != null }
            assertSearchInactive(activity)
            assertEquals("", nodes().first { it.isEditable && it.isVisibleToUser }.text?.toString().orEmpty())
            search("")
            tap("Projects")
            scrollTo("Actions for project ${b.name}"); tap("Actions for project ${b.name}"); tap("New chat in ${b.name}")
            await { runBlocking { c.settings.settings.first().lastActiveSessionId?.let { it != original && c.sessions.session(it)?.projectId == b.id } == true } }
            val created = requireNotNull(c.settings.settings.first().lastActiveSessionId)
            assertEquals(a.id, c.sessions.session(original)?.projectId)
            assertEquals(b.id, c.sessions.session(created)?.projectId)
            tap("Open navigation"); search("Unique sidebar search message anchor")
            await { find("Messages") != null }
            tap("Original sidebar chat")
            await { runBlocking { c.settings.settings.first().lastActiveSessionId == original } }
            assertEquals(a.id, c.sessions.session(original)?.projectId)
            tap("Open navigation")
            tap("Projects")
            scrollTo("Actions for project ${b.name}"); tap("Actions for project ${b.name}"); tap("Pin project")
            await { runBlocking { b.id in c.settings.settings.first().pinnedProjects } }
            tap("Actions for project ${b.name}"); tap("Archive project")
            await { runBlocking { b.id in c.settings.settings.first().archivedProjects } }
            assertEquals(b.id, c.sessions.session(created)?.projectId)
            tap("Undo")
            await { runBlocking { b.id !in c.settings.settings.first().archivedProjects } }
            assertTrue(c.settings.settings.first().sidebarProjects)
            tap("Chats")
            scrollTo("Actions for Original sidebar chat"); tap("Actions for Original sidebar chat"); tap("Pin")
            await { runBlocking { original in c.settings.settings.first().pinnedSessions } }
            tap("Actions for Original sidebar chat"); tap("Archive")
            await { runBlocking { original in c.settings.settings.first().archivedSessions } }
            tap("Undo")
            await { runBlocking { original !in c.settings.settings.first().archivedSessions } }
            tap("Actions for Original sidebar chat"); tap("Delete")
            await { find("Delete chat?") != null }
            tap("Keep chat")
            assertNotNull(c.sessions.session(original))
            screenshot("real-drawer-actions")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            c.sessions.sessions.first().filter { it.projectId in setOf(a.id, b.id) }.forEach { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(a); c.workspace.deleteProject(b); c.workspace.setActiveProject(previous.id)
            c.settings.setProjectPinned(b.id, false); c.settings.setProjectArchived(b.id, false)
            c.settings.setSidebarProjects(saved.sidebarProjects)
            c.settings.restorePortable(saved); c.settings.setOnboardingDone(saved.onboardingDone); c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            root.deleteRecursively()
        }
    }

    private fun searchHasFocus(): Boolean = nodes().any {
        it.isVisibleToUser && it.isEditable && it.isFocused &&
            (it.contentDescription?.toString() == "Search chats and messages" ||
                it.parent?.contentDescription?.toString() == "Search chats and messages")
    }

    private fun keyboardVisible(activity: MainActivity): Boolean {
        var visible = false
        instrumentation.runOnMainSync {
            visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun assertSearchInactive(activity: MainActivity) {
        val deadline = SystemClock.uptimeMillis() + 1_500
        do {
            assertNotNull("The drawer must stay open", find("Search chats and messages"))
            assertFalse("Opening the drawer must not focus chat search", searchHasFocus())
            assertFalse("Opening the drawer must not open the keyboard", keyboardVisible(activity))
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
    }

    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) { add(node); for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String): AccessibilityNodeInfo? = nodes().firstOrNull {
        it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text)
    }
    private fun tap(text: String) {
        scrollTo(text)
        val node = requireNotNull(find(text))
        var target = node
        while (!target.isClickable && !target.isCheckable && !target.isSelected) target = target.parent ?: break
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val rect = Rect().also(node::getBoundsInScreen)
            instrumentation.uiAutomation.executeShellCommand("input tap ${rect.centerX()} ${rect.centerY()}").close()
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(220)
    }
    private fun search(text: String) {
        scrollTo("Search chats and messages")
        val editable = nodes().first { it.isEditable && it.isVisibleToUser }
        assertTrue(editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        instrumentation.waitForIdleSync(); SystemClock.sleep(300)
    }
    private fun scrollTo(text: String) {
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            for (attempt in 0 until 18) {
                if (find(text) != null) return
                val list = nodes().firstOrNull { it.isScrollable && it.actionList.any { action -> action.id == direction } } ?: break
                if (!list.performAction(direction)) break
                instrumentation.waitForIdleSync(); SystemClock.sleep(150)
            }
        }
        screenshot("failure"); error("Missing sidebar control: $text; nodes=${nodes().map { it.text ?: it.contentDescription }.filterNotNull()}")
    }
    private fun assertWithin(text: String) {
        val rect = Rect().also(requireNotNull(find(text))::getBoundsInScreen)
        val metrics = instrumentation.targetContext.resources.displayMetrics
        assertTrue("$text outside screen: $rect", rect.left >= 0 && rect.top >= 0 && rect.right <= metrics.widthPixels && rect.bottom <= metrics.heightPixels)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(100) }
        screenshot("failure"); error("Sidebar condition timed out")
    }
    private fun screenshot(name: String) {
        val tag = InstrumentationRegistry.getArguments().getString("variant") ?: "default"
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val dir = File(c.appContext.getExternalFilesDir(null), "sidebar-evidence").apply { mkdirs() }
        File(dir, "$tag-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
