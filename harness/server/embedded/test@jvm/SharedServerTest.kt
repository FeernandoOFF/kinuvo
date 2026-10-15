package kinuvo.harness.server

import ai.koog.prompt.streaming.StreamFrame
import kinuvo.client.KinuvoClient
import kinuvo.harness.core.*
import kinuvo.harness.core.store.*
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.*
import kotlin.uuid.Uuid

/** The phone's Server shared on the network, which needs the JVM and Android listener. */
class SharedServerTest {
    private val home = Path(SystemTemporaryDirectory, "kinuvo-shared-${Uuid.random()}")
    private val provider = object : ModelProvider {
        override val id = "test"
        override suspend fun models() = listOf(ModelInfo("local"))
        override fun model(config: AgentConfig) = Model { _, _, _ -> flowOf(StreamFrame.TextDelta("Reply."), StreamFrame.End()) }
    }

    @AfterTest
    fun cleanup() { deleteTree(home) }

    @Test
    fun otherDevicesPairOverTheNetworkWhileTheAppKeepsItsOwnConnection(): Unit = runBlocking {
        withTimeout(10_000) {
            val auth = DeviceAuth(java.io.File(home.toString(), "server-devices.json"))
            val server = EmbeddedServerHost.create(home, listOf(provider), continuous = true, peers = { SharedPeers(it, auth) })
            val listener = NetworkListener(server.runtime, auth)
            val port = listener.start("127.0.0.1", listOf(0))
            val app = KinuvoClient("local://kinuvo", "Pixel", token = server.token, connection = server::connect, deviceTools = { setOf(DeviceToolKind.Computer) })
            var remote: KinuvoClient? = null
            try {
                app.connect()
                assertTrue(app.lastConnect!!.management)
                val code = app.call(Api.Pairing, PairingParams()).code
                remote = KinuvoClient("ws://127.0.0.1:$port/ws", "Laptop", pairingCode = code).also { it.connect() }
                assertEquals("default", remote.call(Api.Agents, Unit).single().id)
                assertEquals(listOf("Laptop"), app.call(Api.PairedDevices, Unit).map { it.name })
                assertEquals(listOf("Pixel"), server.runtime.harness.computers.list().map { it.name })
                auth.pairable = false
                assertFailsWith<IllegalStateException> { app.call(Api.Pairing, PairingParams()) }
            } finally { remote?.close(); app.close(); listener.stop(); server.close() }
        }
    }
}
