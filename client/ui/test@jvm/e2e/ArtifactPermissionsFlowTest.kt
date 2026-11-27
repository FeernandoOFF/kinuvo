package kinuvo.ui.e2e

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import kinuvo.protocol.agent.AgentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ArtifactPermissionsFlowTest {
    private val manifest = """{"capabilities":{"network":["https://socket.example.com","wss://socket.example.com"],"popups":true}}"""

    @Test fun theOwnerAllowsWhatAFolderAsksForFromTheMenu() = appTest("artifact-permissions", desktop = true, script = {
        on("Make a deck") {
            toolCall("artifact", """{"action":"create","title":"Live deck","type":"html","content":"<p>deck</p>"}""")
            reply("Saved the deck.")
        }
    }) {
        pair()
        tap("Message")
        type("Make a deck")
        tap("Send")
        await("Saved the deck.")
        val deck = kotlinx.coroutines.runBlocking { server.harness.artifacts.let { it.writeFiles(it.list().single().id, AgentId("e2e"), null, mapOf("manifest.json" to manifest.encodeToByteArray())) } }
        tap(label("^Open$") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        await("More")
        screenshot("viewer-with-request")
        settle()
        // The panel's menu is the last More, after the chat's own.
        ui.onAllNodes(label("More")).onLast().performClick()
        settle()
        tap("Permissions \\(asked for\\)")
        await("Allow everything it asks for")
        await("https://socket.example.com")
        screenshot("sheet-nothing-allowed")
        tap("wss://socket.example.com")
        until("the Server to record the grant") { server.harness.artifacts.let { runCatching { kotlinx.coroutines.runBlocking { it.permissions(deck.id) } }.getOrNull()?.effective?.network == listOf("wss://socket.example.com") } }
        tap("Allow everything it asks for")
        until("everything to be allowed") { kotlinx.coroutines.runBlocking { server.harness.artifacts.permissions(deck.id) }.everything }
        screenshot("sheet-everything-allowed")
        val granted = kotlinx.coroutines.runBlocking { server.harness.artifacts.permissions(deck.id) }
        assertEquals(listOf("https://socket.example.com", "wss://socket.example.com"), granted.effective.network)
        assertTrue(granted.effective.popups)
    }
}
