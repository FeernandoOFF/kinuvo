package kinuvo.protocol

import kotlin.test.*

class TransportTest {
    @Test
    fun publicConnectionsRequireEncryptionWithoutConfusingPrivatePrefixesWithHostnames() {
        listOf("127.0.0.1", "192.168.1.2", "172.16.0.1", "100.64.0.1", "[::1]", "[fd12::1]", "home.local", "home", "desktop-1").forEach {
            requireSecureTransport("ws://$it:8787/ws")
        }
        listOf("example.com", "127.0.0.1.evil.test", "172.32.0.1", "100.128.0.1", "8.8.8.8").forEach {
            assertFailsWith<IllegalArgumentException> { requireSecureTransport("ws://$it/ws") }
            requireSecureTransport("wss://$it/ws")
        }
        assertFailsWith<IllegalArgumentException> { requireSecureTransport("wss://user:password@example.com/ws") }
    }

    @Test
    fun noncanonicalNumericAddressesCannotBypassEncryption() {
        listOf("134744072", "0x08080808", "010.0.0.1", "0177.0.0.1", "8.8.8", "%38%2e%38%2e%38%2e%38", "０x０8080808").forEach {
            assertFalse(privateNetworkHost(it))
            assertFailsWith<IllegalArgumentException> { requireSecureTransport("ws://$it:8787/ws") }
        }
    }
}
