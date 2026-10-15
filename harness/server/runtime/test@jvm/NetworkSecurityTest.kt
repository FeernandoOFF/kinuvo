package kinuvo.harness.server

import kotlin.test.*

class NetworkSecurityTest {
    @Test
    fun originsAreExactAndForwardedAddressesRequireTrustedProxies() {
        val security = NetworkSecurity("https://kinuvo.example.com")
        assertTrue(security.acceptsOrigin("https://kinuvo.example.com", "ignored", emptySet()))
        assertFalse(security.acceptsOrigin("https://kinuvo.example.com.evil.test", "ignored", emptySet()))
        assertFalse(security.acceptsOrigin("http://kinuvo.example.com", "ignored", emptySet()))
        assertFalse(security.acceptsOrigin("null", "ignored", emptySet()))
        assertFalse(security.acceptsOrigin("https://kinuvo.example.com/", "ignored", emptySet()))
        assertTrue(security.acceptsOrigin(null, "ignored", emptySet()))
        assertEquals("203.0.113.1", security.source("203.0.113.1", "10.0.0.1"))
        assertEquals("203.0.113.2", security.source("127.0.0.1", "10.0.0.1, 203.0.113.2"))
        assertFailsWith<IllegalArgumentException> { security.source("127.0.0.1", "attacker.example.com") }
        assertFalse(security.secureProxy("203.0.113.1", "https"))
        assertFailsWith<IllegalArgumentException> { NetworkSecurity("http://kinuvo.example.com") }
        assertFailsWith<IllegalArgumentException> { NetworkSecurity("https://kinuvo.example.com/path") }
        val local = NetworkSecurity()
        assertTrue(local.acceptsOrigin("http://localhost:8787", "localhost:8787", setOf("localhost")))
        assertFalse(local.acceptsOrigin("http://evil.test:8787", "evil.test:8787", setOf("localhost")))
    }

    @Test
    fun attemptsAndConnectionsAreBoundedAcrossSources() {
        var time = 0L
        val limits = NetworkLimits { time }
        repeat(10) { assertTrue(limits.authenticate("source")) }
        assertFalse(limits.authenticate("source"))
        repeat(5) { source -> repeat(10) { assertTrue(limits.authenticate("$source")) } }
        assertFalse(limits.authenticate("another"))
        time += 60_000
        assertTrue(limits.authenticate("source"))
        repeat(8) { assertTrue(limits.open("source")) }
        assertFalse(limits.open("source"))
        limits.close("source")
        assertTrue(limits.open("source"))
        repeat(7) { source -> repeat(8) { assertTrue(limits.open("other$source")) } }
        assertFalse(limits.open("too-many"))
    }
}
