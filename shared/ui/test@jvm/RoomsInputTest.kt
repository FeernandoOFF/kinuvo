package kinuvo.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kinuvo.app.rooms.RoomsState
import kinuvo.protocol.*
import kinuvo.ui.design.HarnessTheme
import kinuvo.ui.rooms.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsInputTest {
    @Test fun delayedDraftUpdatesKeepNewerTypingAndSuccessfulSendClearsTheField() = javax.swing.SwingUtilities.invokeAndWait {
        runTest {
            val room = Room("team", "Team", listOf("planner", "builder"), "planner")
            val session = RoomSession("session", room.id)
            val state = mutableStateOf(RoomsState(selected = room, view = RoomView(session)))
            var reported = ""
            ImageComposeScene(1000, 1500, Density(1f), coroutineContext = UnconfinedTestDispatcher(testScheduler), content = {
                HarnessTheme {
                    RoomsContent(emptyList(), state.value, RoomsActions(text = { reported = it }), {})
                }
            }).use { scene ->
                fun frame() { repeat(5) { scene.render(testScheduler.currentTime * 1_000_000); testScheduler.advanceTimeBy(100) } }
                frame()
                scene.type("Check t"); frame()
                assertEquals("Check t", reported)
                scene.type("Check th"); frame()
                assertEquals("Check th", reported)
                state.value = state.value.copy(text = "Check t"); frame()
                assertEquals("Check th", scene.input().config[SemanticsProperties.EditableText].text)
                assertEquals("Check th", reported)
                state.value = state.value.copy(text = reported); frame()
                scene.type("Check the plan"); frame()
                state.value = state.value.copy(text = reported); frame()
                state.value = state.value.copy(text = ""); frame()
                assertEquals("", scene.input().config[SemanticsProperties.EditableText].text)
                scene.type("Private draft"); frame()
                val other = room.copy(id = "other")
                state.value = state.value.copy(selected = other, view = RoomView(session.copy(roomId = other.id))); frame()
                assertEquals("", scene.input().config[SemanticsProperties.EditableText].text)
            }
        }
    }

    @Test fun submittingKeepsTheComposerUntilSuccessAndSwitchingSessionsUsesTheirDrafts() = javax.swing.SwingUtilities.invokeAndWait {
        runTest {
            val room = Room("team", "Team", listOf("planner", "builder"))
            val first = RoomSession("first", room.id)
            val second = RoomSession("second", room.id)
            val state = mutableStateOf(RoomsState(selected = room, view = RoomView(first)))
            var submitted = ""
            ImageComposeScene(1000, 900, Density(1f), coroutineContext = UnconfinedTestDispatcher(testScheduler), content = {
                HarnessTheme {
                    RoomsContent(emptyList(), state.value, RoomsActions(
                        text = { state.value = state.value.copy(text = it) },
                        send = { submitted = state.value.text; state.value = state.value.copy(submitting = true) },
                    ), {})
                }
            }).use { scene ->
                fun frame() { repeat(5) { scene.render(testScheduler.currentTime * 1_000_000); testScheduler.advanceTimeBy(100) } }
                frame()
                scene.type("Keep until accepted"); frame()
                scene.click("Send"); frame()
                assertEquals("Keep until accepted", submitted)
                assertEquals("Keep until accepted", scene.input().config[SemanticsProperties.EditableText].text)
                state.value = state.value.copy(submitting = false, error = "Try again"); frame()
                assertEquals("Keep until accepted", scene.input().config[SemanticsProperties.EditableText].text)
                state.value = state.value.copy(view = RoomView(second), text = "Second draft"); frame()
                assertEquals("Second draft", scene.input().config[SemanticsProperties.EditableText].text)
                state.value = state.value.copy(view = RoomView(first), text = "Keep until accepted"); frame()
                assertEquals("Keep until accepted", scene.input().config[SemanticsProperties.EditableText].text)
                state.value = state.value.copy(text = ""); frame()
                assertEquals("", scene.input().config[SemanticsProperties.EditableText].text)
            }
        }
    }

    private fun ImageComposeScene.click(label: String) {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        var target = semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }.first {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
        }
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
        target.config[SemanticsActions.OnClick].action!!.invoke()
    }

    private fun ImageComposeScene.input(): SemanticsNode {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }.single {
            it.config.getOrNull(SemanticsProperties.EditableText) != null
        }
    }

    private fun ImageComposeScene.type(text: String) {
        input().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))
    }
}
