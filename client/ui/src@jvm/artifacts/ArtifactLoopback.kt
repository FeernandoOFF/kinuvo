package kinuvo.ui.artifacts

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The desktop web view cannot register schemes, so each open Artifact gets a listener of its own on `127.0.0.1`: its port is its origin, and its address is
 * `http://127.0.0.1:<port>/<token>`. A request needs the token, which never leaves the process, and the right `Host`, so neither other software on the
 * machine nor a browser page rebinding a name to loopback can read an Artifact. An IP address, as macOS app transport security rejects `*.localhost` names.
 */
internal object ArtifactLoopback {
    private val listeners = ConcurrentHashMap<String, ServerSocket>()
    private val workers = Executors.newCachedThreadPool { Thread(it, "artifact-loopback").apply { isDaemon = true } }
    private val random = SecureRandom()

    fun open(site: ArtifactSite): ArtifactOrigin {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val origin = ArtifactOrigin("http://127.0.0.1:${server.localPort}/$token", site)
        listeners[origin.base] = server
        Thread({ accept(server, origin) }, "artifact-loopback-accept").apply { isDaemon = true }.start()
        return origin
    }

    fun close(origin: ArtifactOrigin) {
        listeners.remove(origin.base)?.close()
    }

    private fun accept(server: ServerSocket, origin: ArtifactOrigin) {
        while (!server.isClosed) {
            val connection = runCatching { server.accept() }.getOrNull() ?: continue
            workers.execute { runCatching { connection.use { serve(it, server.localPort, origin) } } }
        }
    }

    private fun serve(connection: Socket, port: Int, origin: ArtifactOrigin) {
        connection.soTimeout = 5_000
        val request = readHead(connection.getInputStream().buffered()) ?: return
        val lines = request.split("\r\n")
        val (method, target) = lines.first().split(' ').let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        val host = lines.drop(1).firstNotNullOfOrNull { line -> line.takeIf { it.startsWith("host:", ignoreCase = true) }?.substringAfter(':')?.trim() }
        val response = when {
            host != "127.0.0.1:$port" -> OriginResponse(421, "text/plain", emptyMap(), byteArrayOf())
            method != "GET" && method != "HEAD" -> OriginResponse(405, "text/plain", emptyMap(), byteArrayOf())
            !target.startsWith("/") -> OriginResponse(400, "text/plain", emptyMap(), byteArrayOf())
            else -> runBlocking { origin.serve("http://127.0.0.1:$port$target") }
        }
        val out = connection.getOutputStream()
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${REASONS[response.status] ?: "Error"}\r\n")
            append("Content-Type: ${response.contentType}\r\nContent-Length: ${response.body.size}\r\nConnection: close\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        out.write(head.encodeToByteArray())
        if (method != "HEAD") out.write(response.body)
        out.flush()
    }

    private val REASONS = mapOf(200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 421 to "Misdirected Request", 502 to "Bad Gateway")

    /** The request line and headers, up to a limit; null when the request is not whole by then. */
    private fun readHead(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var tail = 0
        while (bytes.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            bytes.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) return bytes.toString(Charsets.ISO_8859_1).dropLast(4)
        }
        return null
    }
}
