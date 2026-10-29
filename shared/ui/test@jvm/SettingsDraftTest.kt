package kinuvo.ui

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import kinuvo.app.agent.*
import kinuvo.protocol.*
import kinuvo.ui.design.*
import com.composables.ui.components.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kinuvo.ui.agent.*
import kotlin.test.*

class SettingsDraftTest {
    @Test fun confirmedSaveNavigatesWithoutAskingToDiscard() {
        val edits = SettingsEdits()
        edits.dirty = true
        edits.saved()
        var navigated = false
        edits.leave { navigated = true }
        assertTrue(navigated)
        assertNull(edits.pending)
    }

    @Test fun discardClearsCachedDraftBeforeNavigating() {
        val edits = SettingsEdits()
        var cachedDraft: String? = "Unsaved text"
        edits.onDiscard = { cachedDraft = null }
        edits.dirty = true
        edits.leave { assertNull(cachedDraft) }
        edits.discard()
        assertNull(cachedDraft)
        assertFalse(edits.dirty)
        assertNull(edits.pending)
    }

    @Test fun aBlockAtEitherScopeWinsOverAnAllow() {
        for (server in ToolRule.entries) for (agent in ToolRule.entries) {
            val result = effectiveToolRule(server, agent)
            if (server == ToolRule.Block || agent == ToolRule.Block) assertEquals(ToolRule.Block, result)
            else if (server == ToolRule.Allow || agent == ToolRule.Allow) assertEquals(ToolRule.Allow, result)
            else assertEquals(ToolRule.Ask, result)
        }
    }

    @Test fun nestedInspectorPagesHaveOneHeaderAndRestoreTheParentScroll() = javax.swing.SwingUtilities.invokeAndWait {
        for (sheet in listOf(false, true)) {
            val nested = mutableStateOf(false)
            val agent = AgentInfo("echo", "Echo", "host")
            val details = AgentDetails(agent, AgentSettings("model", "medium", "default", "host"), "Identity", "Soul")
            val state = InspectorState(agent.id, details = details, draft = AgentDraft.of(details))
            ImageComposeScene(390, 844, Density(1f), content = {
                HarnessTheme(dark = false) {
                    val section: (@Composable () -> Unit)? = if (nested.value) ({
                        CompositionLocalProvider(LocalModalNavigation provides ModalNavigation({ nested.value = false })) {
                            DetailPane(null, "Workspace") { Text("Folder") }
                        }
                    }) else null
                    AgentInspectorContent(state, InspectorHost(listOf(agent), null, section = section), InspectorActions(), sheet = sheet)
                }
            }).use { scene ->
                var time = 0L
                fun settle() { repeat(10) { scene.render(time.also { time += 100_000_000 }) } }
                fun nodes(): List<SemanticsNode> {
                    fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
                    return scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
                }
                fun descriptions(label: String) = nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
                fun scroll() = nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                settle()
                assertTrue(descriptions("Back").isEmpty())
                assertTrue(descriptions("Close Agent settings").isEmpty())
                assertTrue(scroll().config[SemanticsActions.ScrollBy].action!!.invoke(0f, 300f))
                settle()
                val position = scroll().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue(position > 0f)
                nested.value = true
                settle()
                val back = descriptions("Back").single()
                assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "Agent settings · every Session" } == true })
                var button = back
                while (button.config.getOrNull(SemanticsActions.OnClick) == null) button = assertNotNull(button.parent)
                button.config[SemanticsActions.OnClick].action!!.invoke()
                settle()
                assertFalse(nested.value)
                assertEquals(position, scroll().config[SemanticsProperties.VerticalScrollAxisRange].value())
            }
        }
    }

    @Test fun revertingAnEditClearsTheDismissalGuard() = javax.swing.SwingUtilities.invokeAndWait {
        val value = mutableStateOf("Original")
        val saved = mutableStateOf(false)
        val edits = SettingsEdits()
        ImageComposeScene(390, 844, Density(1f), content = {
            CompositionLocalProvider(LocalSettingsEdits provides edits) {
                TrackSettingsEdits(formChanged(value.value, saved.value))
            }
        }).use { scene ->
            var time = 0L
            fun settle() { repeat(10) { scene.render(time.also { time += 100_000_000 }) } }
            settle()
            assertFalse(edits.dirty)
            value.value = "Changed"
            settle()
            assertTrue(edits.dirty)
            var left = false
            edits.leave { left = true }
            assertFalse(left)
            assertNotNull(edits.pending)
            edits.pending = null
            value.value = "Original"
            settle()
            assertFalse(edits.dirty)
            edits.leave { left = true }
            assertTrue(left)
        }
    }

    @Test fun successfulSaveBecomesTheNewDraftBaseline() = javax.swing.SwingUtilities.invokeAndWait {
        val value = mutableStateOf("Original")
        val saved = mutableStateOf(false)
        var dirty = false
        ImageComposeScene(390, 844, Density(1f), content = {
            val changed = formChanged(value.value, saved.value)
            SideEffect { dirty = changed }
        }).use { scene ->
            var time = 0L
            fun settle() { repeat(10) { scene.render(time.also { time += 100_000_000 }) } }
            settle()
            value.value = "Changed"
            settle()
            assertTrue(dirty)
            saved.value = true
            settle()
            assertFalse(dirty)
            value.value = "Another edit"
            settle()
            assertTrue(dirty)
            value.value = "Changed"
            settle()
            assertFalse(dirty)
        }
    }
}
