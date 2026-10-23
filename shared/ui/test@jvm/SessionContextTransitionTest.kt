package kinuvo.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.composeunstyled.TooltipHost
import kinuvo.app.computer.ComputersState
import kinuvo.app.session.*
import kinuvo.protocol.*
import kinuvo.ui.design.HarnessTheme
import kinuvo.ui.session.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*
import java.io.File
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalCoroutinesApi::class)
class SessionContextTransitionTest {
    private val scope = SessionContextScope(roomId = "room", roomSessionId = "session", memberAgentIds = listOf("a", "b"))

    @Test fun allMembersUsesSessionOutputsAndReturningToMemberRestoresPreference() = javax.swing.SwingUtilities.invokeAndWait {
        runTest {
            val state = mutableStateOf(SessionContextState(agentId = "a", agents = listOf(AgentInfo("a", "Alice", "host")), artifacts = listOf(
                ArtifactInfo("room", "Room output", "md", "b", "private", 1, 1, "session"),
                ArtifactInfo("other", "Other Session output", "md", "a", "other", 1, 1),
            )))
            ImageComposeScene(800, 1200, Density(1f), coroutineContext = UnconfinedTestDispatcher(testScheduler), content = {
                HarnessTheme { TooltipHost { SessionContextContent(state.value, scope, true, null, 1,
                    SessionContextActions(onSelectAgent = { state.value = state.value.copy(agentId = it) })) } }
            }).use { scene ->
                fun frame() { repeat(5) { scene.render(testScheduler.currentTime * 1_000_000); testScheduler.advanceTimeBy(100) } }
                frame()
                scene.click("This Session"); frame(); scene.click("All Agent Artifacts"); frame()
                assertTrue(scene.hasText("Other Session output"))
                scene.click("Alice"); frame(); scene.click("All members"); frame()
                assertTrue(scene.hasText("Room output"))
                assertFalse(scene.hasText("Other Session output"))
                File("build/screens/session-context-all-members-after-switch.png").apply { parentFile.mkdirs() }
                    .writeBytes(scene.render(testScheduler.currentTime * 1_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.click("All members"); frame(); scene.click("Alice"); frame()
                assertTrue(scene.hasText("Other Session output"))
            }
        }
    }

    @Test fun artifactTransitionStopsPreviewAndFullscreenOwnsWatchUntilReturn() = javax.swing.SwingUtilities.invokeAndWait {
        runTest {
            val artifact = ArtifactInfo("output", "Output", "md", "a", "private", 1, 1, "session")
            val context = mutableStateOf(SessionContextState(agentId = "a", artifacts = listOf(artifact)))
            val computer = ComputerInfo("computer", "Mac", "macOS", true, ComputerControl.Agent, agentId = "a", roomSessionId = "session")
            val computers = mutableStateOf(ComputersState(computers = listOf(computer), selected = computer.id))
            val fullscreen = mutableStateOf(false)
            val watches = mutableListOf<String?>()
            ImageComposeScene(800, 1200, Density(1f), coroutineContext = UnconfinedTestDispatcher(testScheduler), content = {
                HarnessTheme { TooltipHost {
                    ContextComputerWatch(computers.value, scope, context.value.agentId, context.value.artifactId == null, fullscreen.value, watches::add)
                    SessionContextContent(context.value, scope, true, null, 1, SessionContextActions(onArtifact = {
                        context.value = context.value.copy(artifactId = it, artifact = it?.let { ArtifactData(artifact, "Saved content") })
                    }))
                } }
            }).use { scene ->
                fun frame() { repeat(5) { scene.render(testScheduler.currentTime * 1_000_000); testScheduler.advanceTimeBy(100) } }
                frame(); assertEquals("computer", watches.last())
                scene.click("Output"); frame(); assertNull(watches.last())
                scene.click("Back to details"); frame(); assertEquals("computer", watches.last())
                computers.value = computers.value.copy(computers = listOf(computer.copy(control = ComputerControl.User)))
                fullscreen.value = true; frame(); assertEquals("computer", watches.last())
                scene.click("Output"); frame(); assertEquals("computer", watches.last())
                fullscreen.value = false; frame(); assertNull(watches.last())
                assertEquals(ComputerControl.User, computers.value.control)
                computers.value = computers.value.copy(computers = listOf(computer.copy(control = ComputerControl.Idle)))
                scene.click("Back to details"); frame(); assertNull(watches.last())
                computers.value = computers.value.copy(computers = listOf(computer)); frame(); assertEquals("computer", watches.last())
                scene.click("Output"); frame()
                computers.value = computers.value.copy(computers = listOf(computer.copy(control = ComputerControl.Idle))); frame()
                scene.click("Back to details"); frame(); assertNull(watches.last())
            }
            assertNull(watches.last())
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
    }
    private fun SemanticsNode.matches(text: String) = config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true || config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
    private fun ImageComposeScene.hasText(text: String) = nodes().any { it.matches(text) }
    private fun ImageComposeScene.click(text: String) {
        var node = nodes().first { it.matches(text) }
        while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node.parent)
        node.config[SemanticsActions.OnClick].action!!.invoke()
    }
}
