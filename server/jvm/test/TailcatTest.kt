package kinuvo.server.jvm

import kotlinx.io.files.Path
import kinuvo.server.plugin.TailcatInvite
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*

class TailcatTest {
    private class FakeTunnel : TailcatTunnel {
        var invites = 0
        var open = true
        val closedInvites = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        var phone: String? = null
        var payloads = mutableListOf<String>()
        override fun invite(code: String, payload: String, ttlSeconds: Int, boundPhones: Set<String>): TailcatInvite {
            invites++
            payloads += payload
            assertTrue(ttlSeconds in 10..600)
            val text = "kinuvo1:$code"
            return object : TailcatInvite {
                override val text = text
                override val expiresAt = 0L
                override val open get() = this@FakeTunnel.open
                override fun close() { closedInvites += text }
            }
        }
        override fun phoneOf(code: String) = phone.also { phone = null }
        override fun revoke(phone: String) { revoked += phone }
        override fun close() = Unit
    }

    private fun withAuth(block: (DeviceAuth) -> Unit) {
        val dir = Files.createTempDirectory("tailcat-test").toFile()
        try { block(DeviceAuth(Path(dir.path, "devices.json"))) } finally { dir.deleteRecursively() }
    }

    @Test
    fun optionsAndPairingRemainBackwardsCompatible() {
        assertFalse(Json.decodeFromString<ServeOptions>("{}").tailcat)
        val options = ServeOptions(tailcat = true)
        assertEquals(options, Json.decodeFromString<ServeOptions>(Json.encodeToString(options)))
        val old = """{"code":"code","expiresAt":"later","port":8787}"""
        assertNull(Json.decodeFromString<PairingCode>(old).tailcatInvite)
    }

    @Test
    fun pairingOffersOneInviteWindowPerCodeWhileTheTunnelServes() = withAuth { auth ->
        assertNull(auth.pairing(false, false)!!.tailcatInvite)
        val tunnel = FakeTunnel()
        auth.tailcat(tunnel)
        assertTrue(auth.tailcatActive)
        val first = auth.pairing(false, false)!!
        assertEquals("kinuvo1:${first.code}", first.tailcatInvite)
        assertEquals("""{"code":"${first.code}"}""", tunnel.payloads.single())
        assertEquals(first.tailcatInvite, auth.pairing(false, false)!!.tailcatInvite)
        assertEquals(1, tunnel.invites)
        tunnel.open = false
        auth.pairing(false, false)
        assertEquals(2, tunnel.invites)
        tunnel.open = true
        val refreshed = auth.pairing(true, false)!!
        assertNotEquals(first.code, refreshed.code)
        assertEquals("kinuvo1:${refreshed.code}", refreshed.tailcatInvite)
        assertEquals(3, tunnel.invites)
        assertEquals(2, tunnel.closedInvites.size)
        auth.tailcat(null)
        assertFalse(auth.tailcatActive)
        assertEquals(3, tunnel.closedInvites.size)
        assertNull(auth.pairing(false, false)!!.tailcatInvite)
    }

    @Test
    fun onlyTheMatchingTunnelPeerInheritsThePhoneBinding() = withAuth { auth ->
        val tunnel = FakeTunnel()
        auth.tailcat(tunnel)
        for (peer in listOf(null, "nodekey:other", "nodekey:phone")) {
            val code = auth.refreshCode()
            tunnel.phone = "nodekey:phone"
            assertNotNull(auth.authenticate("Device", null, code, peer))
            assertNull(tunnel.phone)
            assertNull(auth.authenticate("Replay", null, code, "nodekey:phone"))
        }
        assertEquals(listOf(null, null, "nodekey:phone"), auth.devices().map { it.tailcatPhone })
    }

    @Test
    fun revokingADeviceRevokesItsTunnelPhone() = withAuth { auth ->
        val tunnel = FakeTunnel()
        auth.tailcat(tunnel)
        tunnel.phone = "nodekey:phone"
        auth.refreshCode()
        val tunneled = auth.authenticate("Phone", null, auth.pairingCode, "nodekey:phone")!!
        tunnel.phone = null
        auth.refreshCode()
        auth.authenticate("Laptop", null, auth.pairingCode)!!
        val devices = auth.devices()
        assertEquals(listOf("nodekey:phone", null), devices.map { it.tailcatPhone })
        assertTrue(auth.revoke(devices[1].id))
        assertTrue(tunnel.revoked.isEmpty())
        assertTrue(auth.revoke(devices[0].id))
        assertEquals(listOf("nodekey:phone"), tunnel.revoked)
        assertFalse(auth.valid(tunneled))
        assertFalse(auth.revoke(devices[0].id))
        assertEquals(1, tunnel.revoked.size)
        tunnel.phone = "nodekey:later"
        auth.refreshCode()
        auth.authenticate("Later", null, auth.pairingCode, "nodekey:later")
        auth.tailcat(null)
        val offline = mutableListOf<String>()
        auth.offlineTailcatRevoke = { offline += it }
        auth.revoke(auth.devices().single().id)
        assertEquals(listOf("nodekey:later"), offline)
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
