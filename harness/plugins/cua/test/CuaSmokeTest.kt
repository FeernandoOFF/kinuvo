package kinuvo.harness.cua

import kinuvo.protocol.ComputerRequest
import kinuvo.protocol.DeviceToolStatus
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.*

/** Captures this machine's screen through the installed cua-driver; runs with KINUVO_CUA_SMOKE=1. */
class CuaSmokeTest {
    @Test
    fun capturesThisDesktop() = runBlocking {
        if (System.getenv("KINUVO_CUA_SMOKE") != "1") return@runBlocking
        // Without this, converting screenshots makes the test JVM an app in front of the one being captured.
        System.setProperty("java.awt.headless", "true")
        val driver = requireNotNull(findDriver()) { "cua-driver isn't installed" }
        val computer = DesktopComputer { McpDriver(driver) }
        try {
            val result = computer.perform(ComputerRequest())
            assertEquals(DeviceToolStatus.Ready, result.status, result.message)
            val screen = assertNotNull(result.screen)
            println("${screen.width}x${screen.height} app=${screen.app} elements=${screen.elements.size}")
            java.io.File(System.getProperty("java.io.tmpdir"), "kinuvo-cua-smoke.jpg").writeBytes(Base64.decode(assertNotNull(screen.screenshot)))
        } finally { computer.close() }
    }
}
