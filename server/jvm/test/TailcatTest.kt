package kinuvo.server.jvm

import kinuvo.server.plugin.TailcatServing
import kinuvo.server.plugin.TailcatTunnel
import kinuvo.protocol.device.PairingCode
import kinuvo.server.DeviceAuth
import kinuvo.server.NetworkListener
import kinuvo.server.NetworkSecurity
import kinuvo.client.KinuvoClient
import kinuvo.testing.server.TestServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.io.files.Path
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*

class TailcatTest {
    @Test
    fun optionsAndPairingRemainBackwardsCompatible() {
        assertFalse(Json.decodeFromString<ServeOptions>("{}").tailcat)
        val options = ServeOptions(tailcat = true)
        assertEquals(options, Json.decodeFromString<ServeOptions>(Json.encodeToString(options)))
        val old = """{"code":"code","expiresAt":"later","port":8787}"""
        assertNull(Json.decodeFromString<PairingCode>(old).tailcatAddress)
    }

    @Test
    fun startPublishesAndCloseClearsAddressEvenAfterFailure() {
        val dir = Files.createTempDirectory("tailcat-test").toFile()
        try {
            val auth = DeviceAuth(File(dir, "devices.json"))
            var closed = 0
            val native = object : TailcatServing {
                override fun serve(state: Path, port: Int): TailcatTunnel {
                    assertEquals(1234, port)
                    return object : TailcatTunnel {
                        override val address = "tc-test"
                        override fun close() { closed++ }
                    }
                }
                override fun reset(state: Path) = Unit
            }
            val exposure = TailcatExposure(auth, native)
            assertFailsWith<IllegalStateException> {
                try {
                    exposure.start(Path(dir.path), 1234)
                    assertEquals("tc-test", auth.pairing(false, false)!!.tailcatAddress)
                    error("later startup step failed")
                } finally { exposure.close() }
            }
            exposure.close()
            assertNull(auth.tailcatAddress)
            assertEquals(1, closed)
            val failing = object : TailcatServing by native {
                override fun serve(state: Path, port: Int): TailcatTunnel = throw ServeException("failed")
            }
            val failed = TailcatExposure(auth, failing)
            assertFailsWith<ServeException> { failed.start(Path(dir.path), 1234) }
            failed.close()
            assertNull(auth.tailcatAddress)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun tunnelListenerRejectsSpoofedProxyAndRequiresPairing(): Unit = runBlocking {
        withTimeout(20_000) {
            val server = TestServer.start()
            val network = NetworkSecurity(trustedProxies = emptySet())
            val listener = NetworkListener(server.runtime, server.auth, network, remoteOnly = true)
            val port = listener.start("127.0.0.1", emptyList())
            try {
                assertFalse(network.secureProxy("127.0.0.1", "https"))
                assertEquals("127.0.0.1", network.source("127.0.0.1", "203.0.113.1"))
                HttpClient.newHttpClient().use { http ->
                    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/auth/session"))
                        .header("Origin", "https://example.com")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "example.com")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build()
                    assertEquals(403, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                }
                val invalid = KinuvoClient("ws://127.0.0.1:$port/ws", "invalid", token = "invalid")
                try { assertFails { invalid.connect() } } finally { invalid.close() }
                server.auth.refreshCode(management = false)
                val paired = KinuvoClient("ws://127.0.0.1:$port/ws", "remote", pairingCode = server.auth.pairingCode)
                try {
                    paired.connect()
                    assertFalse(paired.lastConnect!!.management)
                } finally { paired.close() }
                assertEquals(0, server.auth.port)
            } finally { listener.stop(); server.close() }
        }
    }
}
