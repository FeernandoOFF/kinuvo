package kinuvo.ui.e2e

import kinuvo.app.diagnostics.LogSharing
import kinuvo.ui.fileRevealer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareLogsFlowTest {
    private val revealed = mutableListOf<String>()

    private class FakeLogSharing(var crashed: Boolean) : LogSharing {
        var bundles = 0
        override fun crashedLastTime() = crashed
        override fun dismissCrash() { crashed = false }
        override suspend fun bundle(): String { bundles++; return "/test/kinuvo-logs.zip" }
    }

    @AfterTest fun restore() { fileRevealer = {} }

    @Test fun sharesTheLogsFromSettings() {
        val sharing = FakeLogSharing(crashed = false)
        fileRevealer = { revealed += it }
        appTest("share-logs", logSharing = sharing) {
            startChat()
            tap("Agents and sessions")
            tap("Home")
            tap("Settings")
            scrollTo("Share logs")
            screenshot("settings")
            tap("Share logs")
            await("Share logs")
        }
        assertEquals(1, sharing.bundles)
        assertEquals(listOf("/test/kinuvo-logs.zip"), revealed)
    }

    @Test fun offersToShareAfterACrashAtLaunch() {
        val sharing = FakeLogSharing(crashed = true)
        fileRevealer = { revealed += it }
        appTest("share-logs-crash", logSharing = sharing) {
            await("Kinuvo closed unexpectedly")
            screenshot("crash-prompt")
            tap("Share logs")
            gone("Kinuvo closed unexpectedly")
        }
        assertEquals(1, sharing.bundles)
        assertEquals(listOf("/test/kinuvo-logs.zip"), revealed)
        assertTrue(!sharing.crashed)
    }

    @Test fun dismissingTheCrashPromptSharesNothing() {
        val sharing = FakeLogSharing(crashed = true)
        fileRevealer = { revealed += it }
        appTest("share-logs-dismiss", logSharing = sharing) {
            await("Kinuvo closed unexpectedly")
            tap("Cancel")
            gone("Kinuvo closed unexpectedly")
        }
        assertEquals(0, sharing.bundles)
        assertTrue(revealed.isEmpty())
        assertTrue(!sharing.crashed)
    }
}
