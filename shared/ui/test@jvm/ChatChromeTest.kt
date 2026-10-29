package kinuvo.ui

import kinuvo.ui.navigation.*
import com.composables.ui.theme.LocalInteractionMode
import com.composables.ui.theme.InteractionMode
import kinuvo.protocol.AgentInfo
import kinuvo.protocol.AgentSettings
import kinuvo.app.session.SessionList
import kinuvo.app.connection.Server
import kinuvo.app.navigation.Route
import kinuvo.app.navigation.home
import kinuvo.app.home.Today
import kinuvo.ui.home.HomeActions
import kinuvo.ui.home.HomeContent
import kinuvo.ui.home.HomeUi
import kinuvo.ui.navigation.History
import kinuvo.ui.navigation.Shell
import kinuvo.ui.navigation.ShellState
import kinuvo.ui.navigation.ShellActions
import kinuvo.ui.navigation.ShellChrome
import kinuvo.ui.session.ServerMenuState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kinuvo.app.session.ChatItem
import kinuvo.client.ConnectionState
import kinuvo.protocol.SessionInfo
import kinuvo.ui.design.HarnessTheme
import kinuvo.ui.design.LocalPopovers
import kinuvo.ui.design.Popovers
import kinuvo.ui.session.ChatActions
import kinuvo.ui.session.ChatContent
import kinuvo.app.session.ChatState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatChromeTest {
    @Test fun phoneTodayNeverOffersBackAndItsMenuAndPillOpenTheDrawer() = javax.swing.SwingUtilities.invokeAndWait {
        var drawerOpened = false
        ImageComposeScene(390, 844, Density(1f), content = {
            HarnessTheme(dark = true) {
                CompositionLocalProvider(LocalInteractionMode provides InteractionMode.Touch, LocalHomeChrome provides HomeChrome({ drawerOpened = true }, wide = false)) {
                    HomeContent(HomeUi(Today(kotlinx.datetime.LocalDate(2026, 10, 27))), HomeActions(), wide = false)
                }
            }
        }).use { scene ->
            var time = 0L
            fun frame() { repeat(5) { scene.render(time.also { time += 200_000_000 }) } }
            frame()
            assertNull(scene.node("Back"))
            scene.click("Home, Today")
            assertTrue(drawerOpened)
            drawerOpened = false
            scene.click("Home and agents")
            assertTrue(drawerOpened)
        }
    }

    @Test fun phoneTaskSessionBackReturnsToItsTaskAndPillStillOpensAgentSettings() = javax.swing.SwingUtilities.invokeAndWait {
        val task = Route.Task("review")
        val history = History(mutableStateListOf(Route.Home, task, Route.Chat("echo", "review-run")), mutableStateListOf()).apply { compact = true }
        var settingsOpened = false
        ImageComposeScene(390, 844, Density(1f), content = {
            HarnessTheme(dark = true) {
                CompositionLocalProvider(LocalInteractionMode provides InteractionMode.Touch) {
                    ChatContent(ChatState(agent = AgentInfo("echo", "Echo", "host"), loaded = true),
                        ConnectionState.Connected("Test"), null, ChatActions(onSettings = { settingsOpened = true }), wide = false, onToggleShell = {},
                        onBack = history::back.takeIf { history.backStack.getOrNull(history.backStack.lastIndex - 1)?.home == true })
                }
            }
        }).use { scene ->
            repeat(5) { scene.render(it * 200_000_000L) }
            assertNotNull(scene.node("Back"))
            assertNull(scene.node("Agents and sessions"))
            scene.click("Echo settings")
            assertTrue(settingsOpened)
            scene.click("Back")
            assertEquals(task, history.backStack.last())
        }
    }

    @Test fun desktopRenameOpensAndSaves() = javax.swing.SwingUtilities.invokeAndWait {
        var renamed: String? = null
        val info = SessionInfo("s1", "main", "Original title")
        ImageComposeScene(1100, 850, Density(1f), content = {
            HarnessTheme(dark = false) {
                val popovers = remember { Popovers() }
                CompositionLocalProvider(LocalPopovers provides popovers) {
                    Box(Modifier.fillMaxSize()) {
                        ChatContent(ChatState(selected = info.id, sessions = listOf(info), loaded = true),
                            ConnectionState.Connected("Test"), null, ChatActions(onRename = { renamed = it }))
                        popovers.Host()
                    }
                }
            }
        }).use { scene ->
            var time = 0L
            fun frame() { repeat(5) { scene.render(time.also { time += 200_000_000 }) } }
            frame()
            scene.click("More"); frame()
            scene.click("Rename"); frame()
            assertNotNull(scene.node("Rename chat"))
            scene.screenshot("desktop-rename", time)
            val input = scene.nodes().first { it.config.getOrNull(SemanticsProperties.EditableText)?.text == info.title }
            input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Trip plans"))
            frame()
            scene.click("Save"); frame()
            assertEquals("Trip plans", renamed)
            assertNull(scene.node("Rename chat"))
        }
    }

    @Test fun connectionSnackbarDoesNotMoveMessagesAndClearsAfterRecovery() = javax.swing.SwingUtilities.invokeAndWait {
        runTest {
            val connection = mutableStateOf<ConnectionState>(ConnectionState.Connected("Test"))
            ImageComposeScene(390, 844, Density(1f), coroutineContext = UnconfinedTestDispatcher(testScheduler), content = {
                HarnessTheme(dark = false) {
                    ChatContent(ChatState(loaded = true, items = listOf(ChatItem.User("u1", "Hello"))),
                        connection.value, null, ChatActions())
                }
            }).use { scene ->
                fun frame() { repeat(5) { scene.render(testScheduler.currentTime * 1_000_000); testScheduler.advanceTimeBy(100) } }
                frame()
                val message = scene.node("Hello")!!.boundsInRoot
                connection.value = ConnectionState.Disconnected("Connection lost")
                frame()
                assertNull(scene.node("Reconnecting…"))
                testScheduler.advanceTimeBy(3_000); frame()
                assertNotNull(scene.node("Reconnecting…"))
                assertEquals(message, scene.node("Hello")!!.boundsInRoot)
                testScheduler.advanceTimeBy(5_000); frame()
                assertNotNull(scene.node("Offline · Connection lost. Retrying…"))
                assertEquals(message, scene.node("Hello")!!.boundsInRoot)
                scene.screenshot("chat-offline-snackbar", testScheduler.currentTime * 1_000_000)
                connection.value = ConnectionState.Connected("Test")
                frame()
                assertNotNull(scene.node("Connected"))
                testScheduler.advanceTimeBy(2_000); repeat(4) { frame() }
                assertNull(scene.node("Connected"))
            }
        }
    }

    @Test fun phoneAgentPillUsesTheSpaceAndSettingsStayInTheComposer() = javax.swing.SwingUtilities.invokeAndWait {
        val agent = AgentInfo("echo", "Echo", "host")
        val session = SessionInfo("journal", agent.id, "Evening journal")
        ImageComposeScene(390, 844, Density(1f), content = {
            HarnessTheme(dark = true) {
                CompositionLocalProvider(LocalInteractionMode provides InteractionMode.Touch) {
                    ChatContent(ChatState(agent = agent, selected = session.id, sessions = listOf(session), loaded = true,
                        defaults = AgentSettings("model", "medium", "default", "host")),
                        ConnectionState.Connected("Test"), null, ChatActions(), wide = false, onToggleShell = {})
                }
            }
        }).use { scene ->
            repeat(5) { scene.render(it * 200_000_000L) }
            assertNotNull(scene.node("Agents and sessions"))
            assertNull(scene.node("Back"))
            assertTrue(scene.node("Echo settings")!!.boundsInRoot.width >= 170f)
            val settings = scene.node("Ask before acting in this Session: default")!!
            assertTrue(settings.boundsInRoot.top > 700f)
            assertNull(scene.node("Session settings"))
        }
    }

    @Test fun phoneRailKeepsServerImmediatelyAboveSettingsAtTheFoot() = javax.swing.SwingUtilities.invokeAndWait {
        val agent = AgentInfo("echo", "Echo", "host")
        val server = Server.Remote("ws://home/ws", "test", "Home Server", "home")
        val route = Route.Chat(agent.id, "journal")
        ImageComposeScene(342, 844, Density(1f), content = {
            HarnessTheme(dark = true) {
                CompositionLocalProvider(LocalInteractionMode provides InteractionMode.Touch) {
                    Shell(ShellState(listOf(agent), agent, mapOf(agent.id to SessionList(emptyList())), view = route,
                        servers = ServerMenuState(listOf(server), server)), ShellActions(), remember { ShellChrome(mutableStateOf(true)) }, fill = true)
                }
            }
        }).use { scene ->
            repeat(5) { scene.render(it * 200_000_000L) }
            val settings = scene.node("Settings")!!.boundsInRoot
            val picker = scene.node("Server: Home Server")!!.boundsInRoot
            assertTrue(settings.bottom >= 828f)
            assertTrue(settings.top - picker.bottom in 0f..12f)
            assertEquals(48f, picker.width)
        }
    }

    @Test fun narrowDesktopHeaderLeavesRoomForTheSessionTitle() = javax.swing.SwingUtilities.invokeAndWait {
        val agent = AgentInfo("echo", "Echo", "host")
        val session = SessionInfo("journal", agent.id, "Evening journal", routine = kinuvo.protocol.RoutineRef("journal", "Evening journal"))
        ImageComposeScene(286, 900, Density(1f), content = {
            HarnessTheme(dark = true) {
                CompositionLocalProvider(LocalInteractionMode provides InteractionMode.Pointer) {
                    ChatContent(ChatState(agent = agent, selected = session.id, sessions = listOf(session), loaded = true),
                        ConnectionState.Connected("Test"), null, ChatActions(), wide = true, onToggleShell = {})
                }
            }
        }).use { scene ->
            repeat(5) { scene.render(it * 200_000_000L) }
            assertTrue(scene.node("Evening journal")!!.boundsInRoot.width >= 80f)
            assertNotNull(scene.node("Echo's settings"))
            assertNotNull(scene.node("Routine run"))
            assertNull(scene.node("Statistics"), "Statistics live in the More menu")
            assertNotNull(scene.node("More"))
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    }

    private fun ImageComposeScene.node(label: String) = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true ||
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }

    private fun ImageComposeScene.click(label: String) {
        var target = requireNotNull(node(label)) { "Missing $label" }
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
        target.config[SemanticsActions.OnClick].action!!.invoke()
    }

    private fun ImageComposeScene.screenshot(name: String, time: Long) {
        File("build/screens").apply { mkdirs() }.resolve("$name.png")
            .writeBytes(render(time).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
