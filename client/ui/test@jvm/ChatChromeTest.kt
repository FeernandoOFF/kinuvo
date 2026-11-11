package kinuvo.ui

import kinuvo.ui.navigation.*
import com.composables.ui.theme.LocalInteractionMode
import com.composables.ui.theme.InteractionMode
import kinuvo.protocol.agent.*
import kinuvo.protocol.approval.*
import kinuvo.protocol.browser.*
import kinuvo.protocol.capability.*
import kinuvo.protocol.execution.*
import kinuvo.protocol.routine.*
import kinuvo.protocol.screen.*
import kinuvo.protocol.session.*
import kinuvo.protocol.agent.AgentInfo
import kinuvo.protocol.agent.AgentSettings
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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kinuvo.app.screen.LiveViewModel
import kinuvo.app.screen.ScreensRepository
import kinuvo.ui.screen.BrowserDockHost
import kinuvo.ui.screen.BrowserDocks
import kinuvo.ui.session.BrowserDock
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.KoinIsolatedContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kinuvo.app.session.ChatRow
import kinuvo.ui.session.StepsView
import kinuvo.app.session.ChatItem
import kinuvo.client.ConnectionState
import kinuvo.protocol.session.SessionInfo
import kinuvo.ui.design.HarnessTheme
import kinuvo.ui.design.LocalPopovers
import kinuvo.ui.design.Popovers
import kinuvo.ui.session.ChatActions
import kinuvo.ui.session.ChatContent
import kinuvo.app.session.ChatState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatChromeTest {
    @Test fun outputToolAttachmentsOfferPreviewAndDownload() = javax.swing.SwingUtilities.invokeAndWait {
        val attachment = Attachment("file", "output.png", "image/png", 8)
        var loaded: String? = null
        var previewed: String? = null
        var downloaded: String? = null
        ImageComposeScene(600, 600, Density(1f), coroutineContext = Dispatchers.Main, content = {
            HarnessTheme(dark = true) {
                StepsView(ChatRow.Steps(listOf(ChatItem.ToolCall("tool", "t", "Image output", "", result = "", attachments = listOf(attachment)))),
                    active = false, actions = ChatActions(onLoadAttachment = { loaded = it }, onPreviewAttachment = { previewed = it }, onSaveAttachment = { downloaded = it }))
            }
        }).use { scene ->
            var time = 0L
            fun frame() { repeat(5) { scene.render(time.also { time += 200_000_000 }) } }
            frame(); scene.click("Image output"); frame()
            assertEquals("file", loaded)
            assertNotNull(scene.node("output.png"))
            scene.click("output.png"); frame()
            assertEquals("file", previewed)
            scene.click("Save output.png"); frame()
            assertEquals("file", downloaded)
        }
    }

    @Test fun phoneTodayNeverOffersBackAndItsMenuAndPillOpenTheDrawer() = javax.swing.SwingUtilities.invokeAndWait {
        var drawerOpened = false
        ImageComposeScene(390, 844, Density(1f), coroutineContext = Dispatchers.Main, content = {
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
            scene.click("Today")
            assertTrue(drawerOpened)
            drawerOpened = false
            scene.click("Home and agents")
            assertTrue(drawerOpened)
        }
    }

    @Test fun phoneTaskSessionBackReturnsToItsTaskAndPillStillOpensAgentSettings() = javax.swing.SwingUtilities.invokeAndWait {
        val task = Route.Task("review")
        val history = History(mutableStateListOf(Route.Home, task, Route.Chat(AgentId("echo"), SessionId("review-run"))), mutableStateListOf()).apply { compact = true }
        var settingsOpened = false
        ImageComposeScene(390, 844, Density(1f), coroutineContext = Dispatchers.Main, content = {
            HarnessTheme(dark = true) {
                CompositionLocalProvider(LocalInteractionMode provides InteractionMode.Touch) {
                    ChatContent(ChatState(agent = AgentInfo(AgentId("echo"), "Echo", "host"), loaded = true),
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
        val info = SessionInfo(SessionId("s1"), AgentId("main"), "Original title")
        ImageComposeScene(1100, 850, Density(1f), coroutineContext = Dispatchers.Main, content = {
            HarnessTheme(dark = false) {
                val popovers = remember { Popovers() }
                CompositionLocalProvider(LocalPopovers provides popovers) {
                    Box(Modifier.fillMaxSize()) {
                        ChatContent(ChatState(selected = info.id, sessions = listOf(info), loaded = true, items = listOf(ChatItem.User("u", "Hello"))),
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
            assertNotNull(scene.node("Rename Session"))
            scene.screenshot("desktop-rename", time)
            val input = scene.nodes().first { it.config.getOrNull(SemanticsProperties.EditableText)?.text == info.title }
            input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Trip plans"))
            frame()
            scene.click("Save"); frame()
            assertEquals("Trip plans", renamed)
            assertNull(scene.node("Rename Session"))
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
        val agent = AgentInfo(AgentId("echo"), "Echo", "host")
        val session = SessionInfo(SessionId("journal"), agent.id, "Evening journal")
        ImageComposeScene(390, 844, Density(1f), coroutineContext = Dispatchers.Main, content = {
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

    @Test fun emptyChatShowsBrowserWithoutSessionMenu() = javax.swing.SwingUtilities.invokeAndWait {
        val agent = AgentInfo(AgentId("echo"), "Echo", "host")
        for (touch in listOf(true, false)) {
            ImageComposeScene(390, 844, Density(1f), coroutineContext = Dispatchers.Main, content = {
                HarnessTheme(dark = true) {
                    CompositionLocalProvider(LocalInteractionMode provides if (touch) InteractionMode.Touch else InteractionMode.Pointer) {
                        ChatContent(ChatState(agent = agent, loaded = true), ConnectionState.Connected("Test"), null,
                            ChatActions(onBrowser = {}), wide = !touch)
                    }
                }
            }).use { scene ->
                repeat(5) { scene.render(it * 200_000_000L) }
                assertNotNull(scene.node("Browser"))
                assertNull(scene.node("More"))
                assertNull(scene.node("Statistics"))
            }
        }
    }

    @Test fun dockedBrowserStaysOpenAcrossSessionsOfItsAgent() = javax.swing.SwingUtilities.invokeAndWait {
        val screens = object : ScreensRepository {
            override val connected = flowOf(false)
            override val frames = emptyFlow<ScreenFrame>()
            override val status = emptyFlow<ScreenStreamStatus>()
            override val changes = emptyFlow<BrowserOwner?>()
            override suspend fun device() = "device"
            override suspend fun list(owner: BrowserOwner?) = emptyList<ScreenInfo>()
            override suspend fun watch(ref: ScreenRef, thumbnail: Boolean) = error("unused")
            override suspend fun unwatch(id: String) {}
            override suspend fun ack(frame: ScreenFrameAck) {}
            override suspend fun control(request: ScreenControlRequest) = error("unused")
            override suspend fun input(request: ScreenInputRequest) = error("unused")
            override suspend fun open(request: ScreenOpen) = error("unused")
            override suspend fun command(request: ScreenCommand) {}
        }
        val koin = koinApplication { modules(module { viewModel { (_: Route) -> LiveViewModel(screens) } }) }
        val docks = BrowserDocks()
        var session by mutableStateOf("first")
        var dock: BrowserDock? = null
        ImageComposeScene(1200, 800, Density(1f), coroutineContext = Dispatchers.Main, content = {
            KoinIsolatedContext(koin) { HarnessTheme(dark = true) {
                key(session) { BrowserDockHost(Route.Browser(AgentId("echo"), SessionId(session)), docks, {}) { dock = it } }
            } }
        }).use { scene ->
            scene.render(0)
            dock!!.toggle()
            repeat(3) { scene.render((it + 1) * 100_000_000L) }
            assertNotNull(scene.node("Close Browser"))
            session = "second"
            repeat(3) { scene.render((it + 4) * 100_000_000L) }
            assertTrue(dock!!.open)
            assertNotNull(scene.node("Close Browser"))
        }
        docks.viewModelStore.clear()
    }

    @Test fun phoneRailKeepsServerImmediatelyAboveSettingsAtTheFoot() = javax.swing.SwingUtilities.invokeAndWait {
        val agent = AgentInfo(AgentId("echo"), "Echo", "host")
        val server = Server.Remote("ws://home/ws", "test", "Home Server", "home")
        val route = Route.Chat(agent.id, SessionId("journal"))
        ImageComposeScene(342, 844, Density(1f), coroutineContext = Dispatchers.Main, content = {
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
        val agent = AgentInfo(AgentId("echo"), "Echo", "host")
        val session = SessionInfo(SessionId("journal"), agent.id, "Evening journal", routine = kinuvo.protocol.routine.RoutineRef("journal", "Evening journal"))
        ImageComposeScene(286, 900, Density(1f), coroutineContext = Dispatchers.Main, content = {
            HarnessTheme(dark = true) {
                CompositionLocalProvider(LocalInteractionMode provides InteractionMode.Pointer) {
                    ChatContent(ChatState(agent = agent, selected = session.id, sessions = listOf(session), loaded = true, items = listOf(ChatItem.User("u", "Hello"))),
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

    @Test fun externalRequestsAndNoticesRenderWithoutNativeSettings() = javax.swing.SwingUtilities.invokeAndWait {
        val agent = AgentInfo(AgentId("a"), "External", "external", executionKind = ExecutionKind.Acp)
        val prompt = PendingInteraction.Questions("q", ToolCallId("t"), "Travel details", listOf(Question("seat", "Seat", QuestionKind.Text)))
        val caps = SessionCapabilities(server = ServerCapabilities("s"), execution = ExecutionCapabilities())
        ImageComposeScene(1000, 1400, Density(1f), coroutineContext = Dispatchers.Main, content = {
            HarnessTheme(dark = true) {
                ChatContent(ChatState(agent = agent, selected = SessionId("s"), loaded = true, capabilities = caps,
                    defaults = AgentSettings("model", "medium", "default", "provider"),
                    items = listOf(ChatItem.Notice("n", "history_gap", "Earlier history is unavailable")),
                    pending = listOf(prompt, PendingInteraction.Unsupported("other", "Another request"))),
                    ConnectionState.Connected("Test"), null, ChatActions(), wide = true)
            }
        }).use { scene ->
            repeat(5) { scene.render(it * 200_000_000L) }
            assertNotNull(scene.node("Travel details"))
            assertNotNull(scene.node("Another request"))
            assertNotNull(scene.node("Earlier history is unavailable"))
            assertNull(scene.node("Ask before acting in this Session: default"))
            assertNull(scene.node("Waiting"))
            assertTrue(scene.node("Travel details")!!.boundsInRoot.bottom < scene.node("Message")!!.boundsInRoot.top)
            File("build/screens").mkdirs()
            File("build/screens/composer-connected-notices.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test fun recoverableSessionHasNoResumeCardAndKeepsComposerAvailable() = javax.swing.SwingUtilities.invokeAndWait {
        val caps = SessionCapabilities(server = ServerCapabilities("s"), chat = CapabilityState.unavailable("Connection ended", "session_lost"), execution = ExecutionCapabilities(resume = true))
        ImageComposeScene(900, 650, Density(1f), coroutineContext = Dispatchers.Main, content = {
            HarnessTheme(dark = true) {
                ChatContent(ChatState(agent = AgentInfo(AgentId("a"), "Wren", "external", executionKind = ExecutionKind.Acp), selected = SessionId("s"), loaded = true,
                    capabilities = caps, phase = ExecutionPhase.Lost, resume = true, items = listOf(ChatItem.Assistant("m", "Saved reply"))),
                    ConnectionState.Connected("Test"), null, ChatActions(), wide = true)
            }
        }).use { scene ->
            repeat(5) { scene.render(it * 200_000_000L) }
            assertNotNull(scene.node("Message"))
            assertNull(scene.node("Resume session"))
            assertNull(scene.node("Session ended"))
            assertNull(scene.node("Resume this Session to keep chatting."))
            assertNull(scene.node("Also shown on your other Devices with access. The first answer wins."))
            File("build/screens").mkdirs()
            File("build/screens/session-implicit-resume.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test fun phoneChatInformationIsAttachedToComposer() = javax.swing.SwingUtilities.invokeAndWait {
        val caps = SessionCapabilities(server = ServerCapabilities("s"), chat = CapabilityState.unavailable("Codex Work is offline", "host_offline"))
        ImageComposeScene(390, 760, Density(1f), coroutineContext = Dispatchers.Main, content = {
            HarnessTheme(dark = true) {
                ChatContent(ChatState(agent = AgentInfo(AgentId("a"), "Wren", "external", executionKind = ExecutionKind.Acp), selected = SessionId("s"), loaded = true,
                    capabilities = caps), ConnectionState.Connected("Test"), null, ChatActions(), wide = false)
            }
        }).use { scene ->
            repeat(5) { scene.render(it * 200_000_000L) }
            val explanation = assertNotNull(scene.node("Codex Work is offline"))
            val action = assertNotNull(scene.node("Check again"))
            assertTrue(explanation.boundsInRoot.bottom <= action.boundsInRoot.top)
            assertTrue(action.boundsInRoot.bottom < scene.node("Message")!!.boundsInRoot.top)
            File("build/screens").mkdirs()
            File("build/screens/composer-connected-phone.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test fun archivedSessionShowsHistoryWithoutResume() = javax.swing.SwingUtilities.invokeAndWait {
        val caps = SessionCapabilities(server = ServerCapabilities("s"), chat = CapabilityState.unavailable("Saved history only", "archived"), execution = ExecutionCapabilities(resume = true))
        ImageComposeScene(1000, 1000, Density(1f), coroutineContext = Dispatchers.Main, content = {
            HarnessTheme(dark = true) {
                ChatContent(ChatState(agent = AgentInfo(AgentId("a"), "Archived", "external", executionKind = ExecutionKind.Acp), selected = SessionId("s"), loaded = true,
                    capabilities = caps, phase = ExecutionPhase.Lost, resume = true, items = listOf(ChatItem.Assistant("m", "Saved reply"))),
                    ConnectionState.Connected("Test"), null, ChatActions(), wide = true)
            }
        }).use { scene ->
            repeat(5) { scene.render(it * 200_000_000L) }
            assertNotNull(scene.node("Saved reply"))
            assertNull(scene.node("Resume session"))
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
