package kinuvo.tailcat

import kinuvo.client.AuthException
import kinuvo.client.ConnectionState
import kinuvo.client.KinuvoClient
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.device.TailcatPayload
import kinuvo.client.tailcatConnection
import kinuvo.server.NetworkListener
import kinuvo.server.NetworkSecurity
import kinuvo.testing.server.TestServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlin.test.*

class TailcatTest {
    @Test
    fun nativeBinding() = runBlocking {
        if (!Tailcat.supported) return@runBlocking
        val state = Files.createTempDirectory("tailcat-binding").toFile()
        try {
            Tailcat.reset(Path(state.path))
            Tailcat.revoke(Path(state.path), "nodekey:unknown")
            assertFailsWith<IllegalStateException> { Tailcat.open("invalid") }
            assertFailsWith<IllegalStateException> { Tailcat.pair("invalid", "Test") }
            val tunnel = Tailcat.serve(Path(state.path), 9, "Test")
            try {
                assertFailsWith<IllegalStateException> { Tailcat.reset(Path(state.path)) }
                assertNull(tunnel.phoneOf("unknown"))
            } finally { tunnel.close() }
            Tailcat.reset(Path(state.path))
        } finally { state.deleteRecursively() }
    }

    @Test
    fun livePairingBindsThePhoneToTheDeviceAndRevokingUnpairsIt(): Unit = runBlocking {
        if (System.getenv("KINUVO_TAILCAT_LIVE") != "1") return@runBlocking
        withTimeout(180_000) {
            val server = TestServer.start()
            val listener = NetworkListener(server.runtime, server.auth, NetworkSecurity(trustedProxies = emptySet()), remoteOnly = true)
            try {
                val port = listener.start("127.0.0.1", emptyList())
                val tunnel = Tailcat.serve(Path(server.home.toString(), "tailcat"), port, "Test")
                server.auth.tailcat(tunnel)
                try {
                    val invite = assertNotNull(server.auth.pairing(true, false)?.tailcatInvite)
                    val pairing = Tailcat.pair(invite, "Phone")
                    val code = ProtocolJson.decodeFromString(TailcatPayload.serializer(), pairing.payload).code
                    val client = KinuvoClient("tailcat:live", "remote", pairingCode = code, connection = { tailcatConnection(pairing.link, Tailcat) })
                    val token = try { client.connect().token } finally { client.close() }
                    val again = KinuvoClient("tailcat:live", "remote", token = token, connection = { tailcatConnection(pairing.link, Tailcat) })
                    try { assertEquals(token, again.connect().token) } finally { again.close() }
                    val device = server.auth.devices().single()
                    assertNotNull(device.tailcatPhone)

                    val forward = Tailcat.open(pairing.link)
                    try {
                        Socket("127.0.0.1", forward.port).use { socket ->
                            socket.soTimeout = 5_000
                            assertFailsWith<SocketTimeoutException> { socket.getInputStream().read() }
                        }
                        assertTrue(server.auth.revoke(device.id))
                        Socket("127.0.0.1", forward.port).use { socket ->
                            socket.soTimeout = 30_000
                            assertEquals(-1, socket.getInputStream().read())
                        }
                        val revoked = KinuvoClient("tailcat:live", "remote", token = token, connection = { tailcatConnection(pairing.link, Tailcat) })
                        try {
                            assertFailsWith<AuthException> { revoked.connect() }
                            assertTrue((revoked.state.value as ConnectionState.Disconnected).rejected)
                        } finally { revoked.close() }
                    } finally { forward.close() }
                } finally { server.auth.tailcat(null); tunnel.close() }
            } finally { listener.stop(); server.close() }
        }
    }
}
