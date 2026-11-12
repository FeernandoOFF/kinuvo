package kinuvo.ui.e2e

import androidx.compose.ui.test.isEnabled
import kinuvo.testing.server.FakeBrowser
import kinuvo.testing.server.FakeBrowserPlugin
import kinuvo.testing.server.computer
import kinuvo.protocol.Api
import kinuvo.protocol.browser.BrowserTab
import kinuvo.protocol.computer.ComputerActionKind
import kinuvo.protocol.session.SendParams
import kinuvo.protocol.agent.AgentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionFlowTest {
    @Test fun expiredPairingCodeStaysOnTheConnectScreen() = appTest("pairing-rejected") {
        openPairing("000000")
        await("Invalid or expired pairing code")
        await("Connect to a Server")
        gone("Start a Session with E2E Agent")
        screenshot("rejected")
        requests(Api.Connect, 1)
        requests(Api.Agents, 0)
    }

    @Test fun favoritesAndRenamesTheOpenSession() = appTest("session-details") {
        startChat()
        tap("More")
        gone("Settings")
        screenshot("session-menu")
        tap("Add to favorites")
        tap("More")
        await("Remove from favorites")
        tap("Rename")
        await("Rename Session")
        clear()
        type("Trip plans")
        screenshot("rename")
        tap("Save")
        gone("Rename Session")
        tap("Agents and sessions")
        await("FAVORITES")
        screenshot("favorites")
        tap("Trip plans")
        await(REPLY)
        requests(Api.CreateSession, 1)
        requests(Api.Send, 1)
        requests(Api.UpdateSession, 2)
        assertEquals(listOf(true to null, null to "Trip plans"), server.spy.params(Api.UpdateSession).map { it.favorite to it.title })
    }

    /** Background and resume use the lifecycle; the OS-level Home press and process retention stay in Maestro. */
    @Test fun replyReceivedElsewhereSurvivesReturnAndResume() = appTest("session-return",
        script = { on("From the laptop") { reply("Reply received while on Board") } },
        scenario = { task("Plan mobile tests") },
    ) {
        startChat()
        openHome()
        tap("Board")
        await("Plan mobile tests")
        val laptop = server.client("Laptop").also { it.connect() }
        val session = server.harness.store.sessions(AgentId("e2e")).single()
        laptop.call(Api.Send, SendParams(AgentId("e2e"), session.id, "From the laptop"))
        returnToChat()
        await("Hello from Maestro")
        await("Reply received while on Board")
        screenshot("returned")
        background()
        requestsAtLeast(Api.Presence, 2)
        assertEquals(false, server.spy.params(Api.Presence).last().active)
        foreground()
        await("Hello from Maestro")
        await(REPLY)
        await("Reply received while on Board")
        requests(Api.CreateSession, 1)
        requestsAtLeast(Api.Tasks)
        until("the app to be active again") { server.spy.params(Api.Presence).last().active }
        assertTrue(server.spy.params(Api.Presence).count { it.active } >= 2)
        assertEquals(1, server.spy.params(Api.Send).count { it.text == "Hello from Maestro" })
    }

    @Test fun computerLiveViewTakeOverAndHandBack() = appTest("computers") {
        pair()
        val pixel = server.computer("Pixel")
        openHome()
        tap("Computers")
        gone("Back")
        await("Watching")
        screenshot("computers")
        tap("Pixel")
        await("Watching")
        tap("Take over")
        await("In control")
        await(label("Switch apps").and(isEnabled()))
        screenshot("in-control")
        tap("Keys")
        screenshot("keys")
        tap("Home")
        tap("Hand back")
        await("Watching")
        requestsAtLeast(Api.Screens)
        requestsAtLeast(Api.ScreenWatch)
        requests(Api.ScreenControl, 2)
        assertEquals(listOf(true, false), server.spy.params(Api.ScreenControl).map { it.take })
        requests(Api.ScreenInput, 1)
        assertTrue(pixel.requests.any { it.action?.kind == ComputerActionKind.Key && it.action?.key.equals("home", ignoreCase = true) })
    }

    @Test fun browserDockShowsTheLiveScreen() = appTest("browser",
        plugins = listOf(FakeBrowserPlugin(FakeBrowser(listOf(BrowserTab("browser-tab", AgentId("e2e"), "Private site", "http://lab.internal"))))),
    ) {
        startChat()
        tap("Browser")
        await("Private site")
        await("Live screen")
        await("Take over")
        screenshot("browser")
        tap("Take over")
        await("In control")
        await("Tabs & address")
        screenshot("in-control")
        tap("Back")
        await(REPLY)
        requestsAtLeast(Api.Screens)
        requestsAtLeast(Api.ScreenWatch)
        requestsAtLeast(Api.ScreenAck)
        assertEquals(true, server.spy.params(Api.ScreenControl).first().take)
    }
}
