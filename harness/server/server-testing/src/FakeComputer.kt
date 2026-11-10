package kinuvo.harness.server.testing

import kinuvo.client.KinuvoClient
import kinuvo.protocol.ComputerElement
import kinuvo.protocol.ComputerRequest
import kinuvo.protocol.ComputerResult
import kinuvo.protocol.ComputerScreen
import kinuvo.protocol.DeviceToolKind
import kinuvo.protocol.DeviceToolStatus

/** The screen a [FakeComputer] answers with. */
val FakeScreen = ComputerScreen("fake", 540, 1200, app = "com.example.chat", platform = "Android", screenshot = TEST_CARD_PNG,
    elements = listOf(ComputerElement(1, "Button", "Send", 400, 1100, 100, 60, clickable = true)))

/** A paired Device that lets Agents and clients control its screen; every request is recorded and answered with [screen]. */
class FakeComputer internal constructor(val client: KinuvoClient, val requests: MutableList<ComputerRequest>)

/**
 * Pairs and connects a Device called [name] that offers its screen as a Computer. Call before the client under test
 * pairs, or it is handed a fresh [pairingCode].
 */
suspend fun TestServer.computer(name: String = "Pixel", screen: ComputerScreen = FakeScreen): FakeComputer {
    val requests = mutableListOf<ComputerRequest>()
    val client = client(name, deviceTools = { setOf(DeviceToolKind.Computer) }, computer = { request ->
        synchronized(requests) { requests += request }
        ComputerResult(DeviceToolStatus.Ready, screen = screen)
    })
    client.connect()
    return FakeComputer(client, requests)
}
