package kinuvo.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.composables.ui.theme.InteractionMode
import com.composables.ui.theme.LocalInteractionMode
import kinuvo.app.artifacts.ArtifactsState
import kinuvo.app.navigation.Route
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.artifact.ArtifactData
import kinuvo.protocol.artifact.ArtifactInfo
import kinuvo.ui.artifacts.ArtifactsActions
import kinuvo.ui.artifacts.ArtifactsContent
import kinuvo.ui.artifacts.LocalNativeViews
import kinuvo.ui.design.KinuvoTheme
import kinuvo.ui.design.FULL_SCREEN_RELAY
import kinuvo.ui.artifacts.artifactDocument
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ArtifactFullScreenTest {
    private val info = ArtifactInfo("deck", "Deck", "html", AgentId("main"), null, 0, 0)
    private val state = ArtifactsState(Route.Artifacts(info.id, fullScreen = true), open = ArtifactData(info, "<section>Slide</section>"))

    private class Viewer(val scene: ImageComposeScene, val scheduler: TestCoroutineScheduler) {
        private var time = 0L
        fun pass(ms: Long) {
            repeat(10) {
                scheduler.advanceTimeBy(ms / 10)
                scheduler.runCurrent()
                scene.render((time + it * ms / 10) * 1_000_000)
            }
            time += ms
            scheduler.runCurrent()
            scene.render(time * 1_000_000)
        }
        fun shown(): Boolean {
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            return scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                .any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Exit full screen") == true }
        }
    }

    private fun viewer(touch: Boolean = false, actions: ArtifactsActions = ArtifactsActions(), test: Viewer.() -> Unit) = javax.swing.SwingUtilities.invokeAndWait {
        val scheduler = TestCoroutineScheduler()
        ImageComposeScene(1000, 700, Density(1f), coroutineContext = StandardTestDispatcher(scheduler), content = {
            CompositionLocalProvider(LocalNativeViews provides false, LocalInteractionMode provides if (touch) InteractionMode.Touch else InteractionMode.Pointer) {
                KinuvoTheme(dark = false) { ArtifactsContent(state, emptyList(), 0, actions) }
            }
        }).use { Viewer(it, scheduler).apply { pass(200) }.test() }
    }

    @Test fun controlsHideThenComeBackFromTheTopEdgeAndBothCorners() = viewer {
        assertTrue(shown())
        pass(4000)
        assertFalse(shown())
        for (point in listOf(Offset(500f, 4f), Offset(20f, 100f), Offset(980f, 100f))) {
            scene.sendPointerEvent(PointerEventType.Move, point, type = PointerType.Mouse)
            pass(100)
            assertTrue(shown(), "controls at $point")
            scene.sendPointerEvent(PointerEventType.Move, Offset(500f, 400f), type = PointerType.Mouse)
            pass(4000)
            assertFalse(shown(), "hidden again after $point")
        }
    }

    @Test fun pointerBelowTheTopLeavesControlsHidden() = viewer {
        pass(4000)
        scene.sendPointerEvent(PointerEventType.Move, Offset(500f, 400f), type = PointerType.Mouse)
        pass(100)
        assertFalse(shown())
    }

    @Test fun tappingTheTopEdgeBringsControlsBackOnPhones() = viewer(touch = true) {
        pass(4000)
        assertFalse(shown())
        scene.sendPointerEvent(PointerEventType.Press, Offset(500f, 10f), type = PointerType.Touch)
        scene.sendPointerEvent(PointerEventType.Release, Offset(500f, 10f), type = PointerType.Touch)
        pass(100)
        assertTrue(shown())
    }

    @Test fun escapeLeavesFullScreen() {
        var left = false
        viewer(actions = ArtifactsActions(onFullScreen = { left = !it })) {
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
            assertTrue(left)
        }
    }

    @Test fun fullScreenPagesRelayEscapeAndTopPointerMoves() {
        assertTrue("kinuvo:top" !in artifactDocument("<p>x</p>"))
        val page = artifactDocument("<p>x</p>", fullScreen = true)
        assertTrue("kinuvo:top" in page && "kinuvo:away" in page && "stopImmediatePropagation" in page)
        assertTrue(FULL_SCREEN_RELAY.startsWith("addEventListener('keydown'"))
    }
}
