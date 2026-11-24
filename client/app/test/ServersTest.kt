package kinuvo.app

import kinuvo.app.agent.*
import kinuvo.app.connection.*

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kinuvo.client.TailcatForward
import kinuvo.client.TailcatForwarder
import kinuvo.client.TailcatPairing
import kotlinx.coroutines.runBlocking
import kinuvo.protocol.device.Links
import kinuvo.protocol.device.PairingCode
import kinuvo.protocol.agent.AgentId
import kinuvo.protocol.session.SessionId

internal class MemoryCredentials : DeviceCredentials {
    private val values = mutableMapOf<String, String>()
    override fun read(server: String) = values[server]
    override fun write(server: String, token: String) { values[server] = token }
    override fun delete(server: String) { values.remove(server) }
}

internal class MemoryPreferences : Preferences {
    val credentials = MemoryCredentials()
    private val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
    override fun clear() = values.clear()
}

private object FakeEmbedded : EmbeddedServer {
    override suspend fun endpoint() = Endpoint("ws://127.0.0.1:1/ws", "local")
}

class ServersTest {
    @Test fun resetClearsEmbeddedSecretsAndSavedConnections() = runTest {
        val prefs = MemoryPreferences()
        var cleared = false
        val embedded = object : EmbeddedServer {
            override suspend fun endpoint() = Endpoint("local://test", "local")
            override suspend fun erase() { cleared = true }
        }
        val servers = Servers(prefs, embedded, prefs.credentials)
        servers.remember(Server.Remote("ws://remote"), "secret")
        servers.onboardingComplete = true
        servers.reset()
        assertTrue(cleared)
        assertNull(prefs.credentials.read("ws://remote"))
        assertFalse(servers.onboardingComplete)
        assertEquals(listOf(Server.Embedded), servers.all)
    }

    @Test fun resetDoesNotClaimSuccessWhenEmbeddedSecretsCannotBeCleared() = runTest {
        val prefs = MemoryPreferences()
        val embedded = object : EmbeddedServer {
            override suspend fun endpoint() = Endpoint("local://test", "local")
            override suspend fun erase() { error("Keychain locked") }
        }
        val servers = Servers(prefs, embedded, prefs.credentials)
        servers.onboardingComplete = true
        assertFailsWith<IllegalStateException> { servers.reset() }
        assertTrue(servers.onboardingComplete)
    }

    private var link = "private-link-one"
    private var code = "111111"
    private val forwarder = object : TailcatForwarder {
        override val supported = true
        override suspend fun pair(invite: String, device: String) = TailcatPairing(link, """{"code":"$code"}""", "Kitchen Mac")
        override suspend fun open(link: String): TailcatForward = error("Not used")
    }
    private val invite = "kinuvo1:" + "a".repeat(140)

    @Test fun tailcatPairingStoresTheLinkInCredentialsAndKeepsTheServerOnRePair() = runBlocking {
        val prefs = MemoryPreferences()
        val servers = Servers(prefs, credentials = prefs.credentials, tailcat = forwarder)
        val pairing = servers.pairTailcat(invite, "Phone")
        assertEquals("111111", pairing.code)
        assertEquals(RemoteTransport.Tailcat, pairing.server.transport)
        assertNull(pairing.server.token)
        val first = servers.remember(pairing.server, "first", "home", "Kitchen") as Server.Remote
        servers.select(first)
        assertEquals("private-link-one", first.tailcatLink)
        link = "private-link-two"
        val updated = servers.remember(servers.pairTailcat(invite, "Phone").server, "second", "home", "New name") as Server.Remote
        assertEquals(first.key, updated.key)
        assertEquals("Kitchen", updated.name)
        assertEquals("private-link-two", updated.tailcatLink)
        assertEquals(updated, servers.current)
        assertEquals(listOf(updated), servers.all)
        assertFalse(prefs["servers"]!!.contains(invite))
        assertFalse(prefs["servers"]!!.contains("private-link"))
        assertFalse(prefs["servers"]!!.contains("second"))
        assertFalse(updated.toString().contains("private-link"))
        assertEquals(updated.tailcatLink, (servers.unpair(updated)).tailcatLink)
        val unpaired = servers.all.single() as Server.Remote
        assertNull(unpaired.token)
        assertEquals(updated.tailcatLink, unpaired.tailcatLink)
        servers.forget(unpaired)
        assertNull(prefs.credentials.read("${updated.key}.link"))
        assertNull(servers.current)
    }

    @Test fun tailcatPairingNeedsAnInviteAndACodeFromTheServer() = runBlocking {
        val prefs = MemoryPreferences()
        val servers = Servers(prefs, credentials = prefs.credentials, tailcat = forwarder)
        assertFailsWith<IllegalArgumentException> { servers.pairTailcat("tc" + "a".repeat(140), "Phone") }
        val missing = Servers(prefs, credentials = prefs.credentials, tailcat = object : TailcatForwarder by forwarder {
            override suspend fun pair(invite: String, device: String) = TailcatPairing("link", "", "Mac")
        })
        assertFailsWith<IllegalStateException> { missing.pairTailcat(invite, "Phone") }
        assertTrue(servers.all.isEmpty())
    }

    @Test fun tailcatPairingIsAnAdditionalChoice() {
        val info = pairingInfo("tailcat:opaque", PairingCode("123456", "2026-10-10T04:00:00Z", 8787, tailcatInvite = invite))
        assertEquals(Links.tailcatPair(invite), info.tailcatLink)
        assertEquals(emptyList(), info.bases)
    }

    @Test
    fun remembersPairedServersAndUpdatesTokens() {
        val prefs = MemoryPreferences()
        val servers = Servers(prefs, credentials = prefs.credentials)
        assertNull(servers.current)
        servers.select(servers.remember(Server.Remote("ws://one:8787/ws"), "old"))
        servers.remember(Server.Remote("ws://two:8787/ws"), "two")
        servers.remember(Server.Remote("ws://one:8787/ws"), "new")
        assertFalse(prefs["servers"]!!.contains("token"))
        val again = Servers(prefs, credentials = prefs.credentials)
        assertEquals(listOf(Server.Remote("ws://two:8787/ws", "two"), Server.Remote("ws://one:8787/ws", "new")), again.all)
        assertEquals(Server.Remote("ws://one:8787/ws", "new"), again.current)
    }

    @Test
    fun embeddedIsJustAnotherServer() = runTest {
        val prefs = MemoryPreferences()
        val servers = Servers(prefs, FakeEmbedded, prefs.credentials)
        assertEquals(Server.Embedded, servers.current)
        assertEquals(Endpoint("ws://127.0.0.1:1/ws", "local"), servers.endpoint(Server.Embedded))
        assertEquals(Server.Embedded, servers.remember(Server.Embedded, "local"))
        assertEquals(listOf(Server.Embedded), servers.all)

        servers.select(servers.remember(Server.Remote("ws://mini:8787/ws"), "tok"))
        assertEquals(Server.Remote("ws://mini:8787/ws", "tok"), servers.current)
        servers.select(Server.Embedded)
        assertNull(Servers(prefs, credentials = prefs.credentials).current, "embedded choice without an embedded server")
    }

    @Test
    fun findsSavedServersByServerId() {
        val prefs = MemoryPreferences()
        val servers = Servers(prefs, FakeEmbedded, prefs.credentials)
        val office = servers.remember(Server.Remote("ws://office:8787/ws"), "tok", "office")
        servers.remember(Server.Embedded, "local", "phone")
        assertEquals(office, servers.find("office"))
        assertEquals(Server.Embedded, servers.find("phone"))
        assertNull(servers.find("other"))
        assertNull(Servers(prefs, credentials = prefs.credentials).find("phone"))
    }

    @Test
    fun remembersLastView() {
        val prefs = MemoryPreferences()
        Servers(prefs, credentials = prefs.credentials).lastView = LastView(AgentId("work"), SessionId("s1"))
        assertEquals(LastView(AgentId("work"), SessionId("s1")), Servers(prefs, credentials = prefs.credentials).lastView)
    }

    @Test
    fun mergesServerIdentityAndPreservesTheLocalAlias() {
        val prefs = MemoryPreferences()
        val servers = Servers(prefs, credentials = prefs.credentials)
        val lan = servers.remember(Server.Remote("ws://192.168.1.2:8787/ws"), "first", "home", "Mac mini") as Server.Remote
        servers.rename(lan, "Kitchen")
        val vpn = servers.remember(Server.Remote("wss://home.ts.net:443/ws"), "second", "home", "Mac mini")
        assertEquals(listOf(Server.Remote("wss://home.ts.net:443/ws", "second", "Kitchen", "home")), servers.all)
        servers.select(vpn)
        servers.lastView = LastView(AgentId("work"), SessionId("s1"))
        servers.forget(vpn as Server.Remote)
        assertNull(servers.current)
        assertNull(servers.lastView)
        assertEquals(emptyList(), servers.all)
    }

    @Test
    fun keepsServerAliasesWhenUnpaired() {
        val prefs = MemoryPreferences()
        val servers = Servers(prefs, credentials = prefs.credentials)
        val server = servers.remember(Server.Remote("ws://home:8787/ws"), "new", "home", "Mac mini") as Server.Remote
        assertEquals("home", server.serverId)
        assertEquals("Mac mini", server.label)
        val renamed = servers.rename(server, "  Office  ")
        assertEquals("Office", servers.unpair(renamed).name)
        assertNull((servers.all.single() as Server.Remote).token)
        assertEquals("home:8787", servers.rename(renamed, " ").label)
        assertNull((servers.remember(server, "next", "home", "Mac mini") as Server.Remote).name)
    }

    @Test
    fun pairingAddressesPreferTheConnectedHostAndExcludeLoopback() {
        val code = PairingCode("123456", "2026-10-10T04:00:00Z", 8787, listOf("192.168.1.2"))
        assertEquals(listOf("https://home.ts.net:443", "http://192.168.1.2:8787"), pairingInfo("wss://home.ts.net:443/ws", code).bases)
        assertEquals(listOf("http://192.168.1.2:8787"), pairingInfo("ws://127.0.0.1:8787/ws", code).bases)
        assertEquals(listOf("http://192.168.1.2:8787"), pairingInfo("ws://[::1]:8787/ws", code).bases)
        assertEquals(listOf("http://[2001:db8::1]:8787"), pairingInfo("local:test", code.copy(addresses = listOf("2001:db8::1"))).bases)
        val servers = MemoryPreferences().let { Servers(it, credentials = it.credentials) }
        servers.setPairingBase("home", "http://192.168.1.2:8787")
        assertEquals("http://192.168.1.2:8787", servers.pairingBase("home"))
    }
}
