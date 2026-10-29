package kinuvo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.composables.ui.components.Text
import kinuvo.app.agent.*
import kinuvo.protocol.AgentDetails
import kinuvo.protocol.AgentInfo
import kinuvo.protocol.AgentSettings
import kinuvo.ui.agent.*
import kinuvo.ui.design.FloatingPill
import kinuvo.ui.design.HarnessTheme
import kinuvo.ui.design.Reveal
import kotlin.test.*

class AccessibilityTest {
    @Test fun revealOnlyExposesTheUncoveredLayerAndAnAccessibleCloseAction() = javax.swing.SwingUtilities.invokeAndWait {
        val open = mutableStateOf(false)
        ImageComposeScene(390, 844, Density(1f), content = {
            HarnessTheme(dark = false) {
                Reveal(open.value, { open.value = it }, behind = {
                    Box(Modifier.fillMaxSize().clickable {}.semantics { contentDescription = "Settings" })
                }) {
                    Box(Modifier.fillMaxSize().clickable {}.semantics { contentDescription = "Chat" })
                }
            }
        }).use { scene ->
            var time = 0L
            fun settle() { repeat(15) { scene.render(time.also { time += 100_000_000 }) } }
            settle()
            assertNull(scene.description("Settings"))
            assertNotNull(scene.description("Chat"))
            assertNull(scene.description("Close navigation"))
            open.value = true
            settle()
            assertNotNull(scene.description("Settings"))
            assertNull(scene.description("Chat"))
            val close = assertNotNull(scene.description("Close navigation"))
            assertEquals(Role.Button, close.config.getOrNull(SemanticsProperties.Role))
            assertTrue(close.config[SemanticsActions.OnClick].action!!.invoke())
            settle()
            assertFalse(open.value)
            assertNull(scene.description("Settings"))
            assertNotNull(scene.description("Chat"))
        }
    }

    @Test fun floatingPillExposesOnlyItsLabelAndButtonAction() = javax.swing.SwingUtilities.invokeAndWait {
        var clicks = 0
        ImageComposeScene(390, 844, Density(1f), content = {
            HarnessTheme(dark = false) {
                Row {
                    FloatingPill({ clicks++ }, "E2E Agent settings", leading = { Text("Avatar") }) {
                        Text("E2E Agent")
                        Text("E2E chat")
                    }
                }
            }
        }).use { scene ->
            repeat(5) { scene.render(it * 200_000_000L) }
            val pill = assertNotNull(scene.description("E2E Agent settings"))
            assertEquals(listOf("E2E Agent settings"), pill.config[SemanticsProperties.ContentDescription])
            assertEquals(Role.Button, pill.config[SemanticsProperties.Role])
            assertNull(pill.config.getOrNull(SemanticsProperties.Text))
            assertTrue(pill.children.isEmpty())
            assertTrue(pill.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(1, clicks)
        }
    }

    @Test fun inspectorGuardHidesPanelAndSheetUntilEditingResumes() = javax.swing.SwingUtilities.invokeAndWait {
        for (sheet in listOf(false, true)) {
            val agent = AgentInfo("echo", "Echo", "host")
            val details = AgentDetails(agent, AgentSettings("model", "medium", "default", "host"), "Identity", "Soul")
            val state = mutableStateOf(InspectorState(agent.id, details = details, draft = AgentDraft.of(details).copy(name = "Edited")))
            var resolved: GuardChoice? = null
            ImageComposeScene(390, 844, Density(1f), content = {
                HarnessTheme(dark = false) {
                    AgentInspectorContent(state.value, InspectorHost(listOf(agent), null), InspectorActions(onResolve = {
                        resolved = it
                        state.value = state.value.copy(guard = null)
                    }), sheet = sheet)
                }
            }).use { scene ->
                var time = 0L
                fun settle() { repeat(10) { scene.render(time.also { time += 100_000_000 }) } }
                settle()
                assertNotNull(scene.description("Save"))
                assertEquals(if (state.value.guard == null) 0 else 1, scene.text("Discard").size)
                assertEquals(if (state.value.guard == null) 1 else 0, scene.nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Save") == true })
                state.value = state.value.copy(guard = InspectorExit.Close)
                settle()
                assertNull(scene.description("Save"))
                assertEquals(if (state.value.guard == null) 0 else 1, scene.text("Discard").size)
                assertEquals(if (state.value.guard == null) 1 else 0, scene.nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Save") == true })
                assertTrue(scene.nodes().any { it.config.getOrNull(SemanticsProperties.PaneTitle) == "Unsaved changes" })
                var keep = scene.text("Keep editing").single()
                while (keep.config.getOrNull(SemanticsActions.OnClick) == null) keep = assertNotNull(keep.parent)
                assertTrue(keep.config[SemanticsActions.OnClick].action!!.invoke())
                settle()
                assertEquals(GuardChoice.Keep, resolved)
                assertNotNull(scene.description("Save"))
                assertEquals(if (state.value.guard == null) 0 else 1, scene.text("Discard").size)
                assertEquals(if (state.value.guard == null) 1 else 0, scene.nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Save") == true })
            }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        return semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    }

    private fun ImageComposeScene.description(label: String) = nodes().firstOrNull {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }

    private fun ImageComposeScene.text(label: String) = nodes().filter {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
    }
}
