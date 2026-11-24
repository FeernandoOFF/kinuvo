package kinuvo.ui

import kinuvo.app.connection.loopbackHttpResponse
import kinuvo.app.connection.loopbackMatchesState
import kinuvo.app.connection.loopbackRequestPath
import kinuvo.client.oauthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.minutes

/** Bind before asking the Server for an authorize URL; the Server retains the exact chosen redirect. */
internal suspend fun providerLoopbackSignIn(
    authorize: suspend (String) -> String,
    opened: (String, Boolean) -> Unit,
    open: suspend (String) -> Unit,
): String? = withTimeout(10.minutes) {
    withContext(Dispatchers.IO) {
        val address = InetAddress.getByName("127.0.0.1")
        val listener = runCatching { ServerSocket(1455, 8, address) }.getOrElse { ServerSocket(0, 8, address) }
        listener.use { server ->
            server.soTimeout = 500
            val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
            val url = authorize(redirect)
            val state = oauthState(url)
            opened(url, false)
            open(url)
            while (true) {
                currentCoroutineContext().ensureActive()
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                try {
                    socket.use {
                        it.soTimeout = 1000
                        val input = it.getInputStream()
                        val deadline = System.nanoTime() + 2_000_000_000L
                        val context = currentCoroutineContext()
                        var remaining = 16_384
                        fun line(): String {
                            val value = StringBuilder()
                            while (true) {
                                context.ensureActive()
                                if (--remaining < 0 || System.nanoTime() > deadline) throw IOException("HTTP request is too large or slow")
                                val c = input.read()
                                if (c < 0) throw IOException("Incomplete HTTP request")
                                if (c == 10) return value.toString().trimEnd('\r')
                                value.append(c.toChar())
                            }
                        }
                        val path = loopbackRequestPath(line()).orEmpty()
                        while (line().isNotEmpty()) { }
                        val callback = "http://127.0.0.1:${server.localPort}$path"
                        val ours = loopbackMatchesState(path, callback, state)
                        val page = if (ours) "Back to Kinuvo. You can close this tab." else "This is not the sign-in Kinuvo is waiting for."
                        it.getOutputStream().write(loopbackHttpResponse(ours, page).toByteArray())
                        if (ours) return@withContext callback
                    }
                } catch (_: IOException) {
                    // Browser preconnects and incomplete requests must not close the listener.
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }
}
