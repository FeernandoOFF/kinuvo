package kinuvo.ui.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.browser.BrowserTab
import kinuvo.testing.server.FakeBrowser
import kinuvo.testing.server.FakeBrowserPlugin
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SidePanelFlowTest {
    private val browser = FakeBrowserPlugin(FakeBrowser(listOf(BrowserTab("tab-docs", AgentId("e2e"), "Kinuvo docs", "https://docs.kinuvo.dev"))))

    @Test fun agentSettingsOpenInTheSidePanelAndCollapseToItsStrip() = appTest("side-panel", desktop = true) {
        sendFirstMessage()
        await(reply)
        tap("E2E Agent's settings")
        await("Agent settings for every Session")
        screenshot("agent-settings")
        tap("Collapse panel")
        gone("Agent settings for every Session")
        await("Expand panel")
        screenshot("collapsed")
        tap("Expand panel")
        await("Agent settings for every Session")
    }

    @Test fun tabsSwitchResizeAndHideOnHome() = appTest("side-panel-tabs", plugins = listOf(browser), desktop = true, script = {
        on("Make a report") {
            toolCall("artifact", """{"action":"create","title":"Weekly report","type":"md","content":"# Weekly report\n\n- Shipped the **Side panel**\n- Browser, Computer and Artifact tabs\n- Drag to resize, collapse to the strip"}""")
            reply("Saved the weekly report.")
        }
    }) {
        pair()
        tap("Message")
        type("Make a report")
        tap("Send")
        await("Saved the weekly report.")
        screenshot("chat")
        tap("Open")
        await("Drag to resize, collapse to the strip")
        screenshot("artifact-tab")
        tap("Browser")
        await("Kinuvo docs")
        screenshot("browser-tab")
        tap("E2E Agent's settings")
        await("Agent settings for every Session")
        screenshot("three-tabs")
        tap(hasContentDescription("Weekly report") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        screenshot("switched-to-artifact")
        drag(480f, -240f, "wider")
        drag(708f, 600f, "narrower")
        await("Expand panel")
        screenshot("collapsed")
        tap("Expand panel")
        screenshot("expanded")
        tap("Home")
        screenshot("home-hides-panel")
    }

    /** Drags the edge of a panel [from] dp wide by [dx] dp in steps, a screenshot at each. */
    private suspend fun AppTest.drag(from: Float, dx: Float, name: String) {
        val d = ui.density.density
        val steps = 6
        var x = 0f
        ui.onRoot().performMouseInput {
            x = width - (44f + 1f + from + 4f) * d
            moveTo(Offset(x, height / 2f))
            press()
        }
        repeat(steps) { i ->
            ui.onRoot().performMouseInput { moveTo(Offset(x + dx * d * (i + 1) / steps, height / 2f)) }
            screenshot("$name-${i + 1}")
        }
        ui.onRoot().performMouseInput { release() }
        settle()
    }
}
