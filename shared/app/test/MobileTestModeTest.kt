package kinuvo.app

import kinuvo.app.connection.*
import kotlin.test.*

class MobileTestModeTest {
    private val android = """{"schema":"1","applicationId":"dev.kinuvo.android","runId":"test-1","expiresAt":"2000","websocketUrl":"ws://127.0.0.1:18081/ws"}"""

    @Test
    fun validatesIdentityExpiryAndLoopbackTransport() {
        assertNotNull(MobileTestOverride.read(android, "dev.kinuvo.android", 1000, true))
        assertNull(MobileTestOverride.read(android, "other.app", 1000, true))
        assertNull(MobileTestOverride.read(android, "dev.kinuvo.android", 2000, true))
        for ((old, replacement) in listOf("127.0.0.1" to "remote.example", "ws://" to "wss://", "18081" to "99999", "test-1" to "", "\"1\"" to "\"2\"")) {
            assertNull(MobileTestOverride.read(android.replace(old, replacement), "dev.kinuvo.android", 1000, true))
        }
        assertNull(MobileTestOverride.read("broken", "dev.kinuvo.android", 1000, true))
        assertNotNull(MobileTestOverride.read("""{"e2eRunId":"ios-1","e2eExpiresAt":"2000","e2eWebsocketUrl":"ws://127.0.0.1:18081/ws"}""", "dev.kinuvo.ios", 1000, false))
    }

    @Test
    fun testLaunchesUseOrdinaryRemoteServersAndIsolatedStorage() {
        val config = MobileTestOverride.read(android, "dev.kinuvo.android", 1000, true)!!
        val first = MobileTestMode(config)
        val servers = first.servers!!
        assertTrue(servers.onboardingComplete)
        assertTrue(servers.all.isEmpty())
        val remote = servers.remember(Server.Remote(config.websocketUrl), "test-token")
        servers.select(remote)
        assertEquals(remote, servers.current)
        val second = MobileTestMode(config)
        assertTrue(second.servers!!.all.isEmpty())
        assertNull(second.credentials.read(config.websocketUrl))
        assertNull(MobileTestMode(null).servers)
    }
}
