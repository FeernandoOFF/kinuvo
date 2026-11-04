package kinuvo.ui.e2e

import androidx.compose.ui.test.isEnabled
import kotlin.test.Test

class SessionFlowTest {
    @Test fun expiredPairingCodeStaysOnTheConnectScreen() = appTest("pairing-rejected", "mobile-rejected") {
        openPairing("000000")
        await("Pairing code expired")
        await("Connect to a Server")
        gone("Start a Session with E2E Agent")
        screenshot("rejected")
        requests("mobile/connect-rejected", 1)
    }

    @Test fun favoritesAndRenamesTheOpenSession() = appTest("session-details", "mobile-session-details") {
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
        await("Hello from WireMock")
        requests("ws/create-session", 1)
        requests("ws/chat-send", 1)
        requests("mobile/session-favorite", 1)
        requests("mobile/session-rename", 1)
    }

    /** Background and resume use the lifecycle; the OS-level Home press and process retention stay in Maestro. */
    @Test fun replyReceivedElsewhereSurvivesReturnAndResume() = appTest("session-return", "mobile-return") {
        startChat()
        openHome()
        tap("Board")
        await("Plan mobile tests")
        push(sessionEntry(10, 2, """{"type":"assistant","text":"Reply received while on Board","messageId":"away-reply"}"""))
        returnToChat()
        await("Hello from Maestro")
        await("Reply received while on Board")
        screenshot("returned")
        background()
        requestsAtLeast("mobile/presence-inactive")
        foreground()
        await("Hello from Maestro")
        await("Hello from WireMock")
        await("Reply received while on Board")
        requests("mobile/connect-features", 1)
        requests("ws/create-session", 1)
        requests("ws/chat-send", 1)
        requests("ws/catch-up", 1)
        requestsAtLeast("mobile/tasks")
        requestsAtLeast("mobile/presence-active", 2)
    }

    @Test fun computerLiveViewTakeOverAndHandBack() = appTest("computers", "mobile-computers") {
        pair()
        openHome()
        tap("Computers")
        gone("Back")
        await("Watching")
        screenshot("computers")
        tap("Pixel")
        await("Watching")
        tap("Take over")
        computerHolder("e2e-device", priority = 1)
        push("""{"type":"event","event":"screens.changed","payload":null,"seq":100}""")
        push("""{"type":"event","event":"screens.frame","seq":102,"payload":{"screen":{"source":"computer","id":"phone"},"subscription":"computer-sub","epoch":"computer-epoch","sequence":2,"sample":{"image":"iVBORw0KGgoAAAANSUhEUgAAAIAAAABQCAIAAABeYuqzAAAAqElEQVR4nO3RsQkAMAzAsPz/bIdC556RwQIfYNCc+7TYrB/EAwCgHQAA7QAAaAcAQDsAANoBANAOAIB2AAC0AwCgHQAA7QAAaAcAQDsAANoBANAOAIB2AAC0AwCgHQAA7QAAaAcAQDsAANoBANAOAIB2AAC0AwCgHQAA7QAAaAcAQDsAANoBANAOAIB2AAC0AwCgHQAA7QAAaAcAQDsAANoBANAOAIB2H2laxqC4EjqVAAAAAElFTkSuQmCC","width":128,"height":80,"geometry":"fixture"}}}""")
        await("In control")
        await(label("Switch apps").and(isEnabled()))
        screenshot("in-control")
        tap("Keys")
        screenshot("keys")
        tap("Home")
        tap("Hand back")
        computerHolder(null, priority = 0)
        push("""{"type":"event","event":"screens.changed","payload":null,"seq":101}""")
        await("Watching")
        requestsAtLeast("mobile/computers")
        requestsAtLeast("mobile/computer-watch")
        requests("mobile/computer-take", 1)
        requests("mobile/computer-home", 1)
        requests("mobile/computer-hand-back", 1)
    }

    /** Swaps the `screens.list` answer to report who holds control, as the Server would after a takeover. */
    private fun AppTest.computerHolder(holder: String?, priority: Int) {
        val control = if (holder != null) """{"generation":1,"holder":"$holder"}""" else """{"generation":2}"""
        server.stub("mobile/computers-${if (holder != null) "held" else "released"}", "screens.list",
            """[{"ref":{"source":"computer","id":"phone"},"title":"Pixel","status":"Ready","active":false,"control":$control,""" +
                """"capabilities":{"delivery":"Continuous","inputs":["tap","type","key","scroll"],"keys":["Back","Home","Recents"]}}]""", priority)
    }

    @Test fun browserDockShowsTheLiveScreen() = appTest("browser", "mobile-browser") {
        startChat()
        tap("Browser")
        await("Private site")
        await("Live screen")
        await("Take over")
        screenshot("browser")
        tap("Take over")
        server.stub("mobile/browser-held", "screens.list",
            """[{"ref":{"source":"browser","id":"browser-tab","owner":{"agentId":"e2e","sessionId":"e2e-session"}},"title":"Private site","currentUrl":"http://lab.internal",""" +
                """"status":"Ready","capabilities":{"delivery":"Continuous","inputs":["tap","type","key","scroll"],"tabs":true,"navigation":true},"control":{"generation":1,"holder":"e2e-device"}}]""", 1)
        push("""{"type":"event","event":"screens.changed","payload":null,"seq":100}""")
        await("In control")
        await("Tabs & address")
        screenshot("in-control")
        tap("Back")
        await("Hello from WireMock")
        requestsAtLeast("mobile/browser-tabs")
        requestsAtLeast("mobile/browser-frame")
        requestsAtLeast("mobile/browser-ack")
    }
}
