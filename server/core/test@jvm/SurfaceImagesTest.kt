package kinuvo.server

import java.net.InetAddress
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SurfaceImagesTest {
    @Test fun rejectsLocalAndSpecialAddresses() {
        listOf("0.0.0.0", "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "224.0.0.1", "::", "::1", "fc00::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "2002:7f00:1::", "2001::1").forEach {
            assertFalse(publicImageAddress(InetAddress.getByName(it)), it)
        }
    }
    @Test fun acceptsPublicAddresses() {
        listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111").forEach { assertTrue(publicImageAddress(InetAddress.getByName(it)), it) }
    }
    @Test fun rejectsLiteralAndDnsLocalTargetsBeforeConnecting() = runTest {
        listOf("http://127.0.0.1/image.png", "http://[::1]/image.png", "http://localhost/image.png").forEach {
            val failure = assertFails { fetchSurfaceImage(it) }
            assertTrue(failure.message.orEmpty().contains("public"), failure.toString())
        }
    }
    @Test fun capsBodiesEvenWithoutContentLength() {
        assertContentEquals(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(1, 2)).readBytesCapped())
        assertFails { ByteArrayInputStream(ByteArray(MAX_SURFACE_IMAGE_BYTES + 1)).readBytesCapped() }
    }
    @Test fun onlyHttpWithoutCredentials() {
        listOf("file:///etc/passwd", "ftp://example.com/image", "https://user:pass@example.com/image").forEach { assertFails { imageUrl(it) } }
        assertEquals("example.com", imageUrl("https://example.com/image").host)
    }
}
