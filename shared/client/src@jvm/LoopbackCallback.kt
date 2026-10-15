package kinuvo.client

import com.sun.net.httpserver.HttpServer
import kinuvo.protocol.McpRedirect
import kotlinx.coroutines.CompletableDeferred
import java.net.InetAddress
import java.net.InetSocketAddress

/** Catches the browser's return from the OAuth sign-in with [state][oauthState] on localhost ([McpRedirect.Loopback]). */
class LoopbackCallback private constructor(private val servers: List<HttpServer>, private val url: CompletableDeferred<String>) : AutoCloseable {
    /** The full URL the browser was sent back to. */
    suspend fun await(): String = url.await()

    override fun close() = servers.forEach { it.stop(0) }

    companion object {
        /** Listens on IPv4 and IPv6 localhost; null when the port is taken. */
        fun start(state: String?, port: Int = McpRedirect.LOOPBACK_PORT): LoopbackCallback? {
            val url = CompletableDeferred<String>()
            val servers = listOf("127.0.0.1", "::1").mapNotNull { host ->
                runCatching { HttpServer.create(InetSocketAddress(InetAddress.getByName(host), port), 0) }.getOrNull()
            }
            if (servers.isEmpty()) return null
            servers.forEach { server ->
                server.createContext("/callback") { exchange ->
                    val returned = "http://localhost:$port${exchange.requestURI}"
                    // Anything else on localhost (another tab, an older sign-in) mustn't end this one.
                    val ours = exchange.requestURI.path == "/callback" && oauthState(returned) == state
                    val page = (if (ours) PAGE else STRAY).toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                    exchange.sendResponseHeaders(if (ours) 200 else 400, page.size.toLong())
                    exchange.responseBody.use { it.write(page) }
                    if (ours) url.complete(returned)
                }
                server.start()
            }
            return LoopbackCallback(servers, url)
        }

        private const val STRAY = "<!doctype html><title>Kinuvo</title><body style=\"font-family:system-ui;padding:3em\"><h2>Not the sign-in Kinuvo is waiting for</h2><p>Start signing in again.</p>"
        private const val PAGE = "<!doctype html><title>Kinuvo</title><body style=\"font-family:system-ui;padding:3em\"><h2>Back to Kinuvo</h2><p>You can close this tab.</p>"
    }
}
