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
