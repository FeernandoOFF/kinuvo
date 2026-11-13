package kinuvo.server

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
    fun trustedProxiesAcceptCidrRangesAndRejectMalformedOnes() {
        val security = NetworkSecurity(trustedProxies = setOf("10.0.0.0/8", "192.168.1.5", "fd00::/8", "2001:db8::1"))
        for (peer in listOf("10.0.2.6", "192.168.1.5", "fd12::7", "2001:db8::1")) assertTrue(security.secureProxy(peer, "https"), peer)
        for (peer in listOf("11.0.0.1", "192.168.1.6", "fe00::1", "2001:db8::2", "127.0.0.1")) assertFalse(security.secureProxy(peer, "https"), peer)
        assertFalse(security.secureProxy("10.0.2.6", "http"))
        assertEquals("203.0.113.9", security.source("10.0.2.6", "198.51.100.1, 203.0.113.9"))
        for (bad in listOf("10.0.0.0/33", "fd00::/129", "10.0.0.0/", "10.0.0.0/-1", "10.0.0.0/8/8", "10.0.0/8", "example.com/8", "10.0.0.0/x", "/8"))
            assertFailsWith<IllegalArgumentException>(bad) { NetworkSecurity(trustedProxies = setOf(bad)) }
        assertTrue(NetworkSecurity(trustedProxies = setOf("0.0.0.0/0")).secureProxy("8.8.8.8", "https"))
    }

    @Test
    fun behindProxyDefaultsTrustLoopbackAndPrivateRangesOnly() {
        val proxied = NetworkSecurity(behindProxy = true)
        for (peer in listOf("127.0.0.1", "::1", "10.0.2.6", "172.16.0.1", "172.31.255.255", "192.168.0.9", "fc00::1", "fdff::1")) assertTrue(proxied.secureProxy(peer, "https"), peer)
        for (peer in listOf("172.32.0.1", "172.15.0.1", "8.8.8.8", "203.0.113.1", "2001:db8::1")) assertFalse(proxied.secureProxy(peer, "https"), peer)
        val strict = NetworkSecurity()
        assertTrue(strict.secureProxy("127.0.0.1", "https"))
        assertFalse(strict.secureProxy("10.0.2.6", "https"))
        assertFalse(NetworkSecurity(behindProxy = true, trustedProxies = setOf("10.0.0.0/8")).secureProxy("127.0.0.1", "https"))
    }

    @Test
    fun forwardedHostOriginsAreAcceptedOnlyFromTrustedHttpsProxies() {
        val security = NetworkSecurity(behindProxy = true)
        fun accepts(origin: String?, peer: String = "10.0.2.6", proto: String? = "https", host: String = "internal:8787", forwarded: String? = "Kinuvo.Example.com") =
            security.acceptsBrowser(origin, host, emptySet(), peer, proto, forwarded)
        assertTrue(accepts("https://kinuvo.example.com"))
        assertTrue(accepts("https://kinuvo.example.com:443"))
        assertTrue(accepts("https://kinuvo.example.com", forwarded = "evil, kinuvo.example.com:443"))
        assertTrue(accepts("https://kinuvo.example.com", host = "kinuvo.example.com", forwarded = null))
        assertTrue(accepts("https://kinuvo.example.com:8443", forwarded = "kinuvo.example.com:8443"))
        assertFalse(accepts("https://kinuvo.example.com:8443"))
        assertFalse(accepts("https://evil.test"))
        assertFalse(accepts("http://kinuvo.example.com"))
        assertFalse(accepts("https://kinuvo.example.com", proto = "http"))
        assertFalse(accepts("https://kinuvo.example.com", proto = null))
        assertFalse(accepts("https://kinuvo.example.com", peer = "203.0.113.1"))
        assertFalse(accepts("https://kinuvo.example.com/"))
        assertFalse(accepts("https://kinuvo.example.com", forwarded = "kinuvo.example.com/x"))
        assertFalse(accepts("https://kinuvo.example.com", host = "", forwarded = null))
        assertFalse(NetworkSecurity().acceptsBrowser("https://kinuvo.example.com", "internal", emptySet(), "127.0.0.1", "https", "kinuvo.example.com"))
        assertFalse(NetworkSecurity(behindProxy = true, trustedProxies = setOf("127.0.0.1")).acceptsBrowser("https://kinuvo.example.com", "internal", emptySet(), "10.0.2.6", "https", "kinuvo.example.com"))
    }

    @Test
    fun configuredPublicUrlKeepsExactMatchPrecedenceAndLearnedOriginIsAdvertisedOnly() {
        val configured = NetworkSecurity("https://kinuvo.example.com", behindProxy = true)
        assertFalse(configured.acceptsBrowser("https://other.example.com", "internal", emptySet(), "10.0.2.6", "https", "other.example.com"))
        assertTrue(configured.acceptsBrowser("https://kinuvo.example.com", "internal", emptySet(), "10.0.2.6", "https", "other.example.com"))
        assertNull(configured.learnedOrigin)

        val learning = NetworkSecurity(behindProxy = true)
        assertNull(learning.learnedOrigin)
        assertFalse(learning.acceptsBrowser("https://a.example.com", "internal", emptySet(), "203.0.113.1", "https", "a.example.com"))
        assertNull(learning.learnedOrigin)
        assertTrue(learning.acceptsBrowser("https://A.example.com:443", "internal", emptySet(), "10.0.2.6", "https", "a.example.com:443"))
        assertEquals("https://a.example.com", learning.learnedOrigin)
        assertTrue(learning.acceptsBrowser("https://b.example.com:8443", "internal", emptySet(), "10.0.2.6", "https", "b.example.com:8443"))
        assertEquals("https://b.example.com:8443", learning.learnedOrigin)
        assertFalse(learning.acceptsBrowser("https://a.example.com", "internal", emptySet(), "10.0.2.6", "https", "b.example.com"))
        assertEquals("https://b.example.com:8443", learning.learnedOrigin)
        assertFalse(learning.acceptsBrowser("https://learned.example.com", "internal", emptySet(), "10.0.2.6", "http", "learned.example.com"))
        val plain = NetworkSecurity()
        assertTrue(plain.acceptsBrowser("http://localhost:8787", "localhost:8787", setOf("localhost"), "127.0.0.1", null, null))
        assertNull(plain.learnedOrigin)
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
