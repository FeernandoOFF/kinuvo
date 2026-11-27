package kinuvo.ui

import kinuvo.protocol.artifact.ArtifactFile
import kinuvo.protocol.artifact.ArtifactFiles
import kinuvo.ui.artifacts.*
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArtifactLoopbackTest {
    private fun port(origin: ArtifactOrigin) = origin.base.substringAfter("127.0.0.1:").substringBefore('/').toInt()

    private fun request(port: Int, target: String, host: String = "127.0.0.1:$port", method: String = "GET"): String =
        Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
            socket.getOutputStream().write("$method $target HTTP/1.1\r\nHost: $host\r\nAccept: */*\r\n\r\n".encodeToByteArray())
            ByteArrayOutputStream().also { socket.getInputStream().copyTo(it) }.toString(Charsets.UTF_8)
        }

    private fun get(origin: ArtifactOrigin, path: String, method: String = "GET") = request(port(origin), origin.base.substringAfter("127.0.0.1:${port(origin)}") + path, method = method)

    @Test
    fun anArtifactIsServedOnItsOwnLoopbackPortBehindAToken() {
        val origin = ArtifactLoopback.open(ArtifactSite.page("notes-1a2b", "<p>home</p>"))
        try {
            assertTrue(Regex("http://127\\.0\\.0\\.1:\\d+/[0-9a-f]{32}").matches(origin.base), origin.base)
            val response = get(origin, "/index.html?v=1")
            assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response)
            assertTrue("Content-Type: text/html; charset=utf-8\r\n" in response)
            assertTrue("Content-Security-Policy: ${artifactPolicy(own = true)}\r\n" in response)
            assertTrue("X-Content-Type-Options: nosniff\r\n" in response)
            assertTrue(response.endsWith("\r\n\r\n<p>home</p>"))
            assertTrue(get(origin, "/missing.js").startsWith("HTTP/1.1 404"))
            assertTrue(get(origin, "/../index.html").startsWith("HTTP/1.1 403"))
            assertTrue(get(origin, "/index.html", method = "POST").startsWith("HTTP/1.1 405"))
            val head = get(origin, "/index.html", method = "HEAD")
            assertTrue(head.startsWith("HTTP/1.1 200") && head.endsWith("\r\n\r\n"))
        } finally {
            ArtifactLoopback.close(origin)
        }
    }

    @Test
    fun theGrantsOfAnArtifactWidenItsPolicyOnTheWire() {
        val access = ArtifactAccess(listOf("https://api.example.com", "wss://api.example.com"))
        val origin = ArtifactLoopback.open(ArtifactSite.page("notes-1a2b", "<p>home</p>", access))
        try {
            val policy = get(origin, "/index.html").lines().first { it.startsWith("Content-Security-Policy: ") }
            assertTrue("connect-src 'self' https://api.example.com wss://api.example.com;" in policy, policy)
            origin.site = ArtifactSite.page("notes-1a2b", "<p>home</p>")
            assertTrue("connect-src 'self';" in get(origin, "/index.html"))
        } finally {
            ArtifactLoopback.close(origin)
        }
    }

    @Test
    fun aRequestWithoutTheTokenOrTheRightHostGetsNothing() {
        val a = ArtifactLoopback.open(ArtifactSite.page("a", "<p>a</p>"))
        val b = ArtifactLoopback.open(ArtifactSite.page("b", "<p>b</p>"))
        try {
            assertNotEquals(port(a), port(b))
            assertEquals("<p>b</p>", get(b, "/index.html").substringAfter("\r\n\r\n"))
            val port = port(a)
            val token = a.base.substringAfterLast('/')
            for (target in listOf("/index.html", "/", "/${"0".repeat(32)}/index.html", "/${b.base.substringAfterLast('/')}/index.html", "/$token", "/$token.evil/index.html", "//$token/index.html")) {
                val response = request(port, target)
                assertTrue(response.startsWith("HTTP/1.1 403") || response.startsWith("HTTP/1.1 404"), "$target ${response.lines().first()}")
                assertTrue("<p>" !in response, target)
            }
            for (host in listOf("localhost:$port", "a.localhost:$port", "evil.example:$port", "127.0.0.1", "127.0.0.1:${port + 1}")) {
                val response = request(port, "/$token/index.html", host = host)
                assertTrue(response.startsWith("HTTP/1.1 421"), host)
                assertTrue("<p>" !in response, host)
            }
        } finally {
            ArtifactLoopback.close(a)
            ArtifactLoopback.close(b)
        }
    }

    @Test
    fun aFolderServesItsFilesFetchedOnDemandWithTheirTypes() {
        val wasm = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)
        val asked = mutableListOf<String>()
        val listing = ArtifactFiles("app-0001", "index.html", listOf(ArtifactFile("index.html", 3, "text/html", "a"), ArtifactFile("pkg/m.wasm", 8, "application/wasm", "b")))
        val origin = ArtifactLoopback.open(ArtifactSite.folder("app-0001", listing, "<p>app</p>") { path -> asked += path; wasm })
        try {
            val page = get(origin, "/")
            assertTrue(page.startsWith("HTTP/1.1 200 OK\r\n") && page.endsWith("\r\n\r\n<p>app</p>"), page)
            assertEquals(emptyList(), asked)
            val response = get(origin, "/pkg/m.wasm")
            assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response)
            assertTrue("Content-Type: application/wasm\r\n" in response && "Content-Length: 8\r\n" in response)
            assertTrue("Content-Security-Policy" !in response)
            assertEquals(listOf("pkg/m.wasm"), asked)
            assertTrue(get(origin, "/pkg/other.wasm").startsWith("HTTP/1.1 404"))
            assertTrue(get(origin, "/pkg/../../x").startsWith("HTTP/1.1 403"))
        } finally {
            ArtifactLoopback.close(origin)
        }
    }

    @Test
    fun closingAnOriginStopsServingIt() {
        val origin = ArtifactLoopback.open(ArtifactSite.page("gone-0001", "<p>x</p>"))
        val port = port(origin)
        ArtifactLoopback.close(origin)
        assertFailsWith<java.io.IOException> { request(port, "/index.html") }
    }

    @Test
    fun anOriginSeesItsArtifactsLatestContentAtTheSameAddress() {
        val origin = ArtifactLoopback.open(ArtifactSite.page("live-0001", "<p>one</p>"))
        try {
            val base = origin.base
            origin.site = ArtifactSite.page("live-0001", "<p>two</p>")
            assertEquals(base, origin.base)
            assertEquals("<p>two</p>", get(origin, "/index.html").substringAfter("\r\n\r\n"))
        } finally {
            ArtifactLoopback.close(origin)
        }
    }
}
