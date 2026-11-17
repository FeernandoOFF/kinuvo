package kinuvo.tailcat

import kinuvo.client.KinuvoClient
import kinuvo.client.tailcatConnection
import kinuvo.protocol.device.Links
import kinuvo.server.NetworkListener
import kinuvo.server.NetworkSecurity
import kinuvo.testing.server.TestServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.*

class TailcatTest {
    @Test
    fun nativeBinding() = runBlocking {
        if (System.getenv("KINUVO_TAILCAT_NATIVE") != "1") return@runBlocking
        val state = Files.createTempDirectory("tailcat-binding").toFile()
        try {
            Tailcat.reset(Path(state.path))
            assertFailsWith<IllegalStateException> { Tailcat.open("invalid") }
        } finally { state.deleteRecursively() }
    }

    @Test
    fun liveNativeWebSocket(): Unit = runBlocking {
        if (System.getenv("KINUVO_TAILCAT_LIVE") != "1") return@runBlocking
        withTimeout(90_000) {
            val server = TestServer.start()
            val listener = NetworkListener(server.runtime, server.auth, NetworkSecurity(trustedProxies = emptySet()), remoteOnly = true)
            try {
                val port = listener.start("127.0.0.1", emptyList())
                Tailcat.serve(Path(server.home.toString(), "tailcat"), port).use { tunnel ->
                    val link = requireNotNull(Links.parsePair(Links.tailcatPair(tunnel.address, server.pairingCode)))
                    val client = KinuvoClient("tailcat:live", "remote", pairingCode = link.code, connection = { tailcatConnection(link.server, Tailcat) })
                    val token = try { client.connect().token } finally { client.close() }
                    val again = KinuvoClient("tailcat:live", "remote", token = token, connection = { tailcatConnection(link.server, Tailcat) })
                    try { assertEquals(token, again.connect().token) } finally { again.close() }
                }
            } finally { listener.stop(); server.close() }
        }
    }
}
