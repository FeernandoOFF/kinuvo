package kinuvo.protocol.artifact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ArtifactNetworkTest {
    @Test
    fun encryptedOriginsAreKeptNormalized() {
        assertEquals("https://api.example.com", ArtifactNetwork.origin("https://api.example.com"))
        assertEquals("https://api.example.com", ArtifactNetwork.origin(" HTTPS://API.Example.com/ "))
        assertEquals("wss://rt.example.com:8443", ArtifactNetwork.origin("wss://rt.example.com:8443"))
        assertEquals("https://*.example.com", ArtifactNetwork.origin("https://*.example.com"))
        assertEquals("wss://*.example.co.uk:443", ArtifactNetwork.origin("wss://*.example.co.uk:443"))
        assertEquals("https://192.168.1.5:8443", ArtifactNetwork.origin("https://192.168.1.5:8443"))
    }

    @Test
    fun cleartextLoopbackWildcardsAndAnythingBeyondAnOriginAreRefused() {
        listOf(
            "http://api.example.com", "ws://api.example.com", "*", "https://*", "https:", "https://", "https://*.com", "https://a.*.example.com", "https://*example.com",
            "https://localhost", "https://app.localhost", "https://127.0.0.1", "https://127.1.2.3:8443", "https://0.0.0.0", "https://[::1]",
            "https://api.example.com/path", "https://api.example.com?x=1", "https://api.example.com#x", "https://user@api.example.com", "https://api.example.com:0",
            "https://api.example.com:65536", "https://api.example.com:", "https://api.example.com:80a", "https://-a.example.com", "https://a_b.example.com",
            "https://api.example.com; script-src *", "https://api.example.com https://evil.com", "https://exa%6dple.com", "ftp://example.com",
            "https://example", "", "   ",
        ).forEach { assertNull(ArtifactNetwork.origin(it), it) }
    }

    @Test
    fun aListIsNormalizedDistinctAndBounded() {
        assertEquals(listOf("https://a.example.com", "wss://a.example.com"), ArtifactNetwork.checked(listOf("https://A.example.com", "wss://a.example.com", "https://a.example.com/")))
        assertFailsWith<IllegalArgumentException> { ArtifactNetwork.checked(listOf("https://a.example.com", "http://b.example.com")) }
        assertFailsWith<IllegalArgumentException> { ArtifactNetwork.checked((1..ArtifactNetwork.MAX_ORIGINS + 1).map { "https://h$it.example.com" }) }
    }

    @Test
    fun permissionsSayWhatIsStillWaiting() {
        val asks = ArtifactCapabilities(network = listOf("https://a.example.com"), popups = true)
        assertEquals(false, ArtifactPermissions("a").asking)
        assertEquals(true, ArtifactPermissions("a", requested = asks).pending)
        assertEquals(false, ArtifactPermissions("a", requested = asks, effective = asks).pending)
        assertEquals(true, ArtifactPermissions("a", requested = asks, effective = asks).everything)
        assertEquals(true, ArtifactPermissions("a", requested = asks, effective = asks.copy(popups = false)).pending)
        assertEquals(false, ArtifactPermissions("a", requested = asks, effective = asks.copy(popups = false)).everything)
    }
}
