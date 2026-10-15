package kinuvo.app

import kinuvo.app.connection.*
import kinuvo.app.agent.*

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kinuvo.protocol.PairingCode

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
}

private object FakeEmbedded : EmbeddedServer {
    override suspend fun endpoint() = Endpoint("ws://127.0.0.1:1/ws", "local")
}

class ServersTest {
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
    fun remembersLastView() {
        val prefs = MemoryPreferences()
        Servers(prefs, credentials = prefs.credentials).lastView = LastView("work", "s1")
        assertEquals(LastView("work", "s1"), Servers(prefs, credentials = prefs.credentials).lastView)
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
        servers.lastView = LastView("work", "s1")
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
