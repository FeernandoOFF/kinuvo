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
                    RoomsContent(emptyList(), state.value, RoomsActions(edit = { reported = it.text }), {})
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
                        edit = { state.value = state.value.copy(text = it.text, recipients = it.recipients) },
                        sendDraft = { submitted = it.text; state.value = state.value.copy(submitting = true) },
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

    @Test fun headerRevealsTheRoomAndRenamesInPlaceAtPhoneWidth() = javax.swing.SwingUtilities.invokeAndWait {
        val room = Room("macbook", "Macbook", listOf("iris", "milo"))
        val session = RoomSession("intro", room.id, title = "Hey guys, what is your name and what tools do yo")
        var revealed = 0
        var details = 0
        val renamed = mutableListOf<String>()
        ImageComposeScene(320, 844, Density(1f), content = {
            HarnessTheme { com.composeunstyled.TooltipHost {
                RoomsContent(emptyList(), RoomsState(selected = room, view = RoomView(session)), RoomsActions(
                    onReveal = { revealed++ }, onContext = { details++ }, rename = { renamed += it },
                ), {})
            } }
        }).use { scene ->
            var time = 0L
            fun frame() { repeat(8) { time += 150_000_000; scene.render(time) } }
            fun rename(text: String) {
                scene.click(session.title); frame()
                val field = scene.titleInput()
                assertEquals(session.title, field.config[SemanticsProperties.EditableText].text)
                field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))
                frame()
                scene.titleInput().config[SemanticsActions.OnImeAction].action!!.invoke()
                frame()
            }
            frame()
            scene.click("Show Macbook in sidebar"); scene.click("Show Session details")
            assertEquals(1, revealed); assertEquals(1, details)
            rename("Introductions")
            rename("   ")
            assertEquals(listOf("Introductions"), renamed)
        }
    }

    @Test fun acceptingMentionAndSendingImmediatelyUsesTheTokenSnapshot() = javax.swing.SwingUtilities.invokeAndWait {
        val agents = listOf(AgentInfo("atlas", "Atlas", "local"), AgentInfo("pip", "Pip", "local"))
        val room = Room("team", "Team", agents.map { it.id })
        var sent: kinuvo.app.rooms.RoomDraft? = null
        ImageComposeScene(390, 844, Density(1f), content = {
            HarnessTheme { RoomsContent(agents, RoomsState(selected = room, view = RoomView(RoomSession("s", room.id))),
                RoomsActions(sendDraft = { sent = it }), {}) }
        }).use { scene ->
            var time = 0L
            fun frame() { repeat(6) { time += 150_000_000; scene.render(time) } }
            frame()
            scene.type("@At"); frame()
            scene.click("@Atlas")
            scene.click("Send")
            assertEquals(listOf("atlas"), sent?.recipients)
            assertEquals("[@Atlas](trigger:room-member:atlas)", sent?.text?.trim())
            frame()
            scene.type(""); frame()
            scene.type("@Atlas plain"); frame()
            scene.click("Send")
            assertEquals(emptyList(), sent?.recipients)
        }
    }

    @Test fun userBubblesOnlyRenderAuthoredTextAndInlineMentions() = javax.swing.SwingUtilities.invokeAndWait {
        val agents = listOf(AgentInfo("atlas", "Atlas", "local"), AgentInfo("pip", "Pip", "local"))
        val room = Room("team", "Team", agents.map { it.id })
        val messages = listOf(
            RoomMessage("broadcast", text = "Hello everyone", recipients = room.members),
            RoomMessage("legacy", text = "Review this", recipients = listOf("atlas")),
            RoomMessage("mentioned", text = "[@Atlas](trigger:room-member:atlas) please review", recipients = listOf("atlas")),
        )
        ImageComposeScene(1000, 900, Density(1f), content = {
            HarnessTheme { RoomsContent(agents, RoomsState(selected = room, view = RoomView(RoomSession("s", room.id, messages, title = "Review"))), RoomsActions(), {}) }
        }).use { scene ->
            repeat(6) { scene.render(it * 150_000_000L) }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            val texts = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            assertTrue(texts.any { it == "Hello everyone" })
            assertTrue(texts.any { it == "Review this" })
            assertTrue(texts.any { it.contains("@Atlas") && it.contains("please review") })
            assertFalse(texts.any { it == "@Atlas" || it.contains("@Pip") || it == "You" || it.startsWith("To ") })
        }
    }

    private fun ImageComposeScene.click(label: String) {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        var target = semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }.first {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
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

    private fun ImageComposeScene.titleInput(): SemanticsNode {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }.single {
            it.config.getOrNull(SemanticsProperties.EditableText) != null && it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Session name") == true
        }
    }

    private fun ImageComposeScene.type(text: String) {
        input().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))
    }
}
