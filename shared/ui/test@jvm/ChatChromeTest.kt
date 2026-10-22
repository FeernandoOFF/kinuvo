package kinuvo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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

    @Test fun managementDestinationsAreReachableFromSettingsWithoutPinnedRows() = javax.swing.SwingUtilities.invokeAndWait {
        val agent = kinuvo.protocol.AgentInfo("pip", "Pip", "local")
        val capabilities = kinuvo.protocol.ServerCapabilities("home", features = setOf(kinuvo.protocol.ServerFeature.Knowledge, kinuvo.protocol.ServerFeature.Computer))
        var knowledge = 0
        var computers = 0
        ImageComposeScene(320, 844, Density(1f), content = {
            HarnessTheme { com.composeunstyled.TooltipHost {
                kinuvo.ui.navigation.Shell(kinuvo.ui.navigation.ShellState(agent = agent, agents = listOf(agent), capabilities = capabilities),
                    kinuvo.ui.navigation.ShellActions(onKnowledge = { knowledge++ }, onComputers = { computers++ }),
                    kinuvo.ui.navigation.ShellChrome(remember { mutableStateOf(true) }))
            } }
        }).use { scene ->
            var time = 0L
            fun frame() { repeat(6) { time += 150_000_000; scene.render(time) } }
            frame()
            assertNull(scene.node("Knowledge"))
            assertNull(scene.node("Computers"))
            scene.click("Settings ${kinuvo.ui.design.shortcut(",")}"); frame()
            scene.click("Knowledge"); frame()
            assertEquals(1, knowledge)
            scene.click("Settings ${kinuvo.ui.design.shortcut(",")}"); frame()
            scene.click("Computers"); frame()
            assertEquals(1, computers)
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
